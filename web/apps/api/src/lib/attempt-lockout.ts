import { prisma } from "./prisma.js";

// Escalating lockout after repeated wrong guesses, kept in a KV row per key.
// Used for profile PINs ("pinattempts:<profileId>") and account passwords
// ("loginattempts:<username>"). The comments below were written for PINs; the
// reasoning is the same for a password.

const MAX_ATTEMPTS = 5;
const LOCKOUT_MS = 5 * 60 * 1000;
/** Ceiling on the doubling below — an hour, not a permanent lockout. */
const MAX_LOCKOUT_MS = 60 * 60 * 1000;
/**
 * Quiet period after which the escalation resets. Without it a household that
 * fat-fingers a PIN twice a year would eventually be starting from the long
 * end of the backoff; with it, only sustained guessing keeps the multiplier up.
 */
const LOCKOUT_DECAY_MS = 24 * 60 * 60 * 1000;

// `lockouts` is absent on rows written before it existed, and undefined is how
// they read back — the same thing, so the type says both.
type AttemptData = { count: number; lockedUntil: string; lockouts?: number | undefined };

export const getLockout = async (key: string): Promise<{ locked: boolean; retryAfterSec: number }> => {
  const row = await prisma.kV.findUnique({ where: { key } });
  if (!row) return { locked: false, retryAfterSec: 0 };

  try {
    const data = JSON.parse(row.value) as AttemptData;
    if (data.lockedUntil && new Date(data.lockedUntil).getTime() > Date.now()) {
      const retryAfterSec = Math.ceil((new Date(data.lockedUntil).getTime() - Date.now()) / 1000);
      return { locked: true, retryAfterSec };
    }
  } catch { /* invalid data — treat as not locked */ }

  return { locked: false, retryAfterSec: 0 };
};

export const recordFailure = async (key: string): Promise<void> => {
  const now = new Date();

  await prisma.$transaction(async (tx) => {
    // Lock the row for the duration of this transaction so concurrent
    // attempts can't read the same stale count. If the row doesn't exist
    // yet, we create it first and then lock it via the upsert below — the
    // unique constraint on KV.key ensures only one row wins the race.
    await tx.$executeRaw`SELECT value FROM "KV" WHERE key = ${key} FOR UPDATE`;

    const row = await tx.kV.findUnique({ where: { key } });
    let data: AttemptData = { count: 0, lockedUntil: "", lockouts: 0 };

    if (row) {
      try {
        const parsed = JSON.parse(row.value) as AttemptData;
        data = {
          count: Number.isInteger(parsed.count) ? parsed.count : 0,
          lockedUntil: typeof parsed.lockedUntil === "string" ? parsed.lockedUntil : "",
          lockouts: Number.isInteger(parsed.lockouts) ? (parsed.lockouts) : 0,
        };
      } catch { /* unreadable value — start the record over */ }

      // A quiet day wipes the slate, escalation tier included; otherwise an
      // expired lockout restarts the attempt count but keeps the tier, so a
      // griefer can't reset the backoff by waiting each lockout out.
      const sinceLastFailure = now.getTime() - row.updatedAt.getTime();
      if (sinceLastFailure > LOCKOUT_DECAY_MS) {
        data = { count: 0, lockedUntil: "", lockouts: 0 };
      } else if (data.lockedUntil && new Date(data.lockedUntil).getTime() <= Date.now()) {
        data = { count: 0, lockedUntil: "", lockouts: data.lockouts };
      }
    }

    data.count += 1;
    if (data.count >= MAX_ATTEMPTS) {
      // Each successive lockout lasts twice as long, up to an hour. A flat
      // 5 minutes let anyone holding the shared API_TOKEN re-lock a profile
      // every five minutes forever for the cost of five wrong guesses;
      // doubling makes sustaining that cost the griefer real time, while the
      // first mistake still only costs the owner five minutes. Verifying the
      // PIN clears the record entirely.
      const lockouts = (data.lockouts ?? 0) + 1;
      const lockoutMs = Math.min(LOCKOUT_MS * 2 ** (lockouts - 1), MAX_LOCKOUT_MS);
      data.lockedUntil = new Date(Date.now() + lockoutMs).toISOString();
      data.count = 0;
      data.lockouts = lockouts;
    }

    await tx.kV.upsert({
      where: { key },
      create: { key, value: JSON.stringify(data), updatedAt: now },
      update: { value: JSON.stringify(data), updatedAt: now },
    });
  });
};

export const clearAttempts = async (key: string): Promise<void> => {
  await prisma.kV.deleteMany({ where: { key } });
};
