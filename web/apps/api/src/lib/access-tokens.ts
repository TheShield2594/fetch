import { randomBytes } from "node:crypto";
import { ACCESS_TOKEN_PREFIX, hashSecret } from "./request-auth.js";
import { prisma } from "./prisma.js";

/**
 * Personal access tokens: a credential an account makes for something that
 * isn't a browser — a script, a Home Assistant integration, a cron job.
 *
 * Before these, such a caller had two choices: a session token, which stops
 * working after 90 days at the latest, or `API_TOKEN`, which is the operator's
 * key to every account and to the stored secrets. A token made here is neither.
 * It acts as the account that made it, on that account's profiles only; it is
 * never the admin; it can be limited to reading; it can't change the password,
 * end sign-ins, pair a TV or make another token; and it is revoked on its own,
 * by name, without touching anything else.
 *
 * Like a session, the token is shown once and stored only as its SHA-256.
 */

/** An account that needs more than this has a use for `API_TOKEN` instead. */
export const MAX_ACCESS_TOKENS_PER_ACCOUNT = 25;
export const MAX_ACCESS_TOKEN_NAME_LENGTH = 60;
/** The longest life a token can be given; leaving `expiresInDays` out means no expiry. */
export const MAX_ACCESS_TOKEN_DAYS = 3650;

/** `lastUsedAt` is written back at most this often, not on every request. */
const LAST_USED_WRITE_INTERVAL_MS = 5 * 60 * 1000;

export type AccessTokenSummary = {
  id: string;
  name: string;
  readOnly: boolean;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
};

const summarize = (row: {
  id: string;
  name: string;
  readOnly: boolean;
  createdAt: Date;
  lastUsedAt: Date | null;
  expiresAt: Date | null;
}): AccessTokenSummary => ({
  id: row.id,
  name: row.name,
  readOnly: row.readOnly,
  createdAt: row.createdAt.toISOString(),
  lastUsedAt: row.lastUsedAt?.toISOString() ?? null,
  expiresAt: row.expiresAt?.toISOString() ?? null,
});

const notExpired = (now: Date) => ({ OR: [{ expiresAt: null }, { expiresAt: { gt: now } }] });

/** The account's tokens that still work, oldest first. Never the token itself. */
export const listAccessTokens = async (accountId: string): Promise<AccessTokenSummary[]> => {
  const rows = await prisma.accessToken.findMany({
    where: { accountId, ...notExpired(new Date()) },
    orderBy: { createdAt: "asc" },
  });
  return rows.map(summarize);
};

export type CreateAccessTokenResult =
  | { ok: true; token: string; accessToken: AccessTokenSummary }
  | { ok: false; reason: "limit" };

/** Makes a token and returns it — the only time it is ever available. */
export const createAccessToken = async (
  accountId: string,
  input: { name: string; readOnly: boolean; expiresInDays: number | null }
): Promise<CreateAccessTokenResult> => {
  const now = new Date();
  const existing = await prisma.accessToken.count({ where: { accountId, ...notExpired(now) } });
  if (existing >= MAX_ACCESS_TOKENS_PER_ACCOUNT) return { ok: false, reason: "limit" };

  const token = `${ACCESS_TOKEN_PREFIX}${randomBytes(32).toString("base64url")}`;
  const row = await prisma.accessToken.create({
    data: {
      accountId,
      name: input.name,
      readOnly: input.readOnly,
      tokenHash: hashSecret(token),
      expiresAt:
        input.expiresInDays === null ? null : new Date(now.getTime() + input.expiresInDays * 24 * 60 * 60 * 1000),
    },
  });
  return { ok: true, token, accessToken: summarize(row) };
};

/** Whether a token of this account was removed. */
export const revokeAccessToken = async (accountId: string, id: string): Promise<{ name: string } | null> => {
  const row = await prisma.accessToken.findFirst({ where: { id, accountId }, select: { name: true } });
  if (!row) return null;
  await prisma.accessToken.deleteMany({ where: { id, accountId } });
  return row;
};

/**
 * The token a bearer value names, or null when it is unknown or has expired.
 * An expired row is deleted on the way out; `sweepExpiredAccessTokens` removes
 * the ones never presented again.
 */
export const findAccessToken = async (
  token: string
): Promise<{ id: string; accountId: string; readOnly: boolean } | null> => {
  const row = await prisma.accessToken.findUnique({
    where: { tokenHash: hashSecret(token) },
    select: { id: true, accountId: true, readOnly: true, expiresAt: true, lastUsedAt: true },
  });
  if (!row) return null;

  const now = Date.now();
  if (row.expiresAt && row.expiresAt.getTime() <= now) {
    await prisma.accessToken.deleteMany({ where: { id: row.id } });
    return null;
  }

  if (!row.lastUsedAt || now - row.lastUsedAt.getTime() > LAST_USED_WRITE_INTERVAL_MS) {
    // Best-effort: a token that authenticates must not fail because the note
    // of when it last did couldn't be written.
    await prisma.accessToken
      .updateMany({ where: { id: row.id }, data: { lastUsedAt: new Date(now) } })
      .catch(() => undefined);
  }

  return { id: row.id, accountId: row.accountId, readOnly: row.readOnly };
};

export const sweepExpiredAccessTokens = async (now: Date = new Date()): Promise<number> => {
  const { count } = await prisma.accessToken.deleteMany({ where: { expiresAt: { lte: now } } });
  return count;
};
