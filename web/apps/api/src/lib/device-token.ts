import { createHash, randomBytes, randomInt } from "node:crypto";
import { prisma } from "./prisma.js";

/**
 * Device tokens: the credential a paired TV holds instead of `API_TOKEN`.
 *
 * Each one is random, shown to the device exactly once (at the end of the
 * pairing poll) and stored only as a SHA-256 hash, so a database dump can't be
 * replayed as a login. A fixed prefix tells them apart from `API_TOKEN` without
 * a lookup, which keeps the browser's requests — still the common case — off
 * the database entirely.
 */
export const DEVICE_TOKEN_PREFIX = "fdt_";

export const generateDeviceToken = (): string => `${DEVICE_TOKEN_PREFIX}${randomBytes(32).toString("base64url")}`;

export const generatePollSecret = (): string => randomBytes(32).toString("base64url");

export const hashSecret = (value: string): string => createHash("sha256").update(value).digest("hex");

/**
 * The code a person reads off the TV and types on the website. No 0/O, 1/I/L
 * or U, so what is read aloud is what gets typed; eight characters from 30 is
 * about 6.5 × 10¹¹ codes, against a confirm route that allows ten guesses a
 * minute and codes that live for minutes.
 */
const PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
export const PAIRING_CODE_LENGTH = 8;

export const generatePairingCode = (): string =>
  Array.from({ length: PAIRING_CODE_LENGTH }, () => PAIRING_CODE_ALPHABET[randomInt(PAIRING_CODE_ALPHABET.length)]).join("");

/** Uppercases and drops the dash, spaces and anything else a person adds while typing. */
export const normalizePairingCode = (raw: string): string => raw.toUpperCase().replace(/[^A-Z0-9]/g, "");

/** `ABCD-EFGH`, the way the TV and the website show it. */
export const formatPairingCode = (code: string): string => `${code.slice(0, 4)}-${code.slice(4)}`;

export type AuthenticatedDevice = { id: string; name: string };

type CachedDevice = { device: AuthenticatedDevice | null; expiresAt: number; lastSeenWrittenAt: number };

/**
 * A short cache in front of the lookup, because a TV home screen is a burst of
 * requests. Revoking a device clears it here immediately (one process serves
 * the API), so revocation is not delayed by the TTL; the TTL only bounds how
 * long a miss — an unknown token — is remembered.
 */
const DEVICE_CACHE_TTL_MS = 30_000;
const LAST_SEEN_WRITE_INTERVAL_MS = 60_000;
const MAX_CACHED_DEVICES = 500;
const deviceCache = new Map<string, CachedDevice>();

export const invalidateDeviceCache = (): void => {
  deviceCache.clear();
};

/**
 * Resolves a device token to its device, or null when no device holds it. Also
 * records when the device was last seen, at most once a minute per device.
 */
export const findDeviceByToken = async (token: string): Promise<AuthenticatedDevice | null> => {
  const tokenHash = hashSecret(token);
  const now = Date.now();
  const cached = deviceCache.get(tokenHash);
  if (cached && cached.expiresAt > now) {
    if (cached.device && now - cached.lastSeenWrittenAt >= LAST_SEEN_WRITE_INTERVAL_MS) {
      cached.lastSeenWrittenAt = now;
      await touchLastSeen(cached.device.id);
    }
    return cached.device;
  }

  const row = await prisma.device.findUnique({ where: { tokenHash }, select: { id: true, name: true } });
  if (deviceCache.size >= MAX_CACHED_DEVICES) deviceCache.clear();
  deviceCache.set(tokenHash, { device: row, expiresAt: now + DEVICE_CACHE_TTL_MS, lastSeenWrittenAt: row ? now : 0 });
  if (row) await touchLastSeen(row.id);
  return row;
};

// Best-effort: a failed write must not turn a valid token into a rejected request.
const touchLastSeen = async (deviceId: string): Promise<void> => {
  try {
    await prisma.device.update({ where: { id: deviceId }, data: { lastSeenAt: new Date() } });
  } catch {
    /* the device may have been revoked between the lookup and this write */
  }
};
