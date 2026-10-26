import { randomBytes } from "node:crypto";
import { type AuthPrincipal, hashSecret, SESSION_TOKEN_PREFIX } from "./request-auth.js";
import { prisma } from "./prisma.js";

/** How long a sign-in lasts without being used. Each use pushes it out again. */
export const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** A session's expiry is written back at most this often, not on every request. */
const SESSION_TOUCH_INTERVAL_MS = 60 * 60 * 1000;

const randomSecret = () => randomBytes(32).toString("base64url");

export const createSession = async (accountId: string): Promise<string> => {
  const token = `${SESSION_TOKEN_PREFIX}${randomSecret()}`;
  await prisma.session.create({
    data: { accountId, tokenHash: hashSecret(token), expiresAt: new Date(Date.now() + SESSION_TTL_MS) },
  });
  return token;
};

/** A fresh invite code. Callers store `hashSecret(code)` and show `code` once. */
export const mintInviteCode = (): string => randomSecret();

/**
 * The session a token belongs to, or null if it is unknown or expired. An
 * expired row is deleted on the way out rather than left for a sweep.
 */
export const findSession = async (
  token: string
): Promise<Extract<AuthPrincipal, { kind: "session" }> | null> => {
  const session = await prisma.session.findUnique({
    where: { tokenHash: hashSecret(token) },
    select: { id: true, expiresAt: true, lastUsedAt: true, account: { select: { id: true, isAdmin: true } } },
  });
  if (!session) return null;

  const now = Date.now();
  if (session.expiresAt.getTime() <= now) {
    await prisma.session.deleteMany({ where: { id: session.id } });
    return null;
  }

  if (now - session.lastUsedAt.getTime() > SESSION_TOUCH_INTERVAL_MS) {
    await prisma.session.updateMany({
      where: { id: session.id },
      data: { lastUsedAt: new Date(now), expiresAt: new Date(now + SESSION_TTL_MS) },
    });
  }

  return { kind: "session", sessionId: session.id, accountId: session.account.id, isAdmin: session.account.isAdmin };
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
