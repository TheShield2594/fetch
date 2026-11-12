import { randomBytes } from "node:crypto";
import { type AuthPrincipal, hashSecret, SESSION_TOKEN_PREFIX } from "./request-auth.js";
import type { RequestOrigin } from "./account-events.js";
import { prisma } from "./prisma.js";

/** How long a sign-in lasts without being used. Each use pushes it out again. */
export const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/**
 * How long a sign-in lasts however often it is used. Without a ceiling a
 * browser that opens the site once a month would stay signed in for ever.
 */
export const SESSION_MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000;
/** A session's expiry is written back at most this often, not on every request. */
const SESSION_TOUCH_INTERVAL_MS = 60 * 60 * 1000;

const randomSecret = () => randomBytes(32).toString("base64url");

/** A session's expiry as of `now`: the idle window, never past the session's absolute lifetime. */
export const sessionExpiry = (createdAt: number, now: number): Date =>
  new Date(Math.min(now + SESSION_TTL_MS, createdAt + SESSION_MAX_AGE_MS));

export const createSession = async (accountId: string, origin?: RequestOrigin): Promise<string> => {
  const token = `${SESSION_TOKEN_PREFIX}${randomSecret()}`;
  const now = Date.now();
  await prisma.session.create({
    data: {
      accountId,
      tokenHash: hashSecret(token),
      expiresAt: sessionExpiry(now, now),
      userAgent: origin?.userAgent ?? null,
      ipAddress: origin?.ipAddress ?? null,
    },
  });
  return token;
};

/** A fresh invite code. Callers store `hashSecret(code)` and show `code` once. */
export const mintInviteCode = (): string => randomSecret();

/**
 * The session a token belongs to, or null if it is unknown or expired. An
 * expired row is deleted on the way out; `sweepExpiredSessions` removes the
 * ones whose token is never presented again.
 */
export const findSession = async (
  token: string
): Promise<Extract<AuthPrincipal, { kind: "session" }> | null> => {
  const session = await prisma.session.findUnique({
    where: { tokenHash: hashSecret(token) },
    select: {
      id: true,
      createdAt: true,
      expiresAt: true,
      lastUsedAt: true,
      account: { select: { id: true, isAdmin: true, mustChangePassword: true } },
    },
  });
  if (!session) return null;

  const now = Date.now();
  if (session.expiresAt.getTime() <= now || session.createdAt.getTime() + SESSION_MAX_AGE_MS <= now) {
    await prisma.session.deleteMany({ where: { id: session.id } });
    return null;
  }

  if (now - session.lastUsedAt.getTime() > SESSION_TOUCH_INTERVAL_MS) {
    await prisma.session.updateMany({
      where: { id: session.id },
      data: { lastUsedAt: new Date(now), expiresAt: sessionExpiry(session.createdAt.getTime(), now) },
    });
  }

  return {
    kind: "session",
    sessionId: session.id,
    accountId: session.account.id,
    isAdmin: session.account.isAdmin,
    mustChangePassword: session.account.mustChangePassword,
  };
};

/** Deletes sessions past their expiry, so a sign-in nobody returns to doesn't stay on record. */
export const sweepExpiredSessions = async (now: Date = new Date()): Promise<number> => {
  const { count } = await prisma.session.deleteMany({ where: { expiresAt: { lte: now } } });
  return count;
};

/** The admin account, if one has been created yet. */
export const findAdminAccountId = async (): Promise<string | null> => {
  const admin = await prisma.account.findFirst({
    where: { isAdmin: true },
    orderBy: { createdAt: "asc" },
    select: { id: true },
  });
  return admin?.id ?? null;
};
