import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { prisma } from "../lib/prisma.js";
import { hashPin, pinMatches } from "../lib/pin-hash.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { invalidateProfileCache } from "../lib/profile.js";
import { hashSecret, isAdminRequest, requireAdmin } from "../lib/request-auth.js";
import { createSession, findAdminAccountId, mintInviteCode } from "../lib/sessions.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

// Sign-in for the website. Invite-only: the first account is the admin, made
// with API_TOKEN (`POST /auth/bootstrap`); everyone else redeems a single-use
// invite the admin created (`POST /auth/register`). Passwords use the same
// scrypt hashing as profile PINs.

const USERNAME_PATTERN = /^[a-z0-9_.-]{3,32}$/;
const USERNAME_ERROR = "Username must be 3–32 characters: letters, numbers, dots, dashes or underscores";
const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_LENGTH = 256;
const PASSWORD_ERROR = `Password must be between ${MIN_PASSWORD_LENGTH} and ${MAX_PASSWORD_LENGTH} characters`;

const DEFAULT_INVITE_DAYS = 7;
const MAX_INVITE_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

const WEB_PUBLIC_BASE = (process.env.CATALOGGY_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.replace(/\/+$/, "");

// The same budget as PIN verification: these take a password, and the server
// is reachable from the internet.
const CREDENTIAL_RATE_LIMIT = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };

/**
 * Compared against when the username doesn't exist, so a wrong username takes
 * as long to reject as a wrong password and the timing doesn't say which.
 */
const DUMMY_PASSWORD_HASH = await hashPin("not-a-real-password");

const loginAttemptKey = (username: string) => `loginattempts:${username}`;

const normalizeUsername = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const username = value.trim().toLowerCase();
  return USERNAME_PATTERN.test(username) ? username : null;
};

const isValidPassword = (value: unknown): value is string =>
  typeof value === "string" && value.length >= MIN_PASSWORD_LENGTH && value.length <= MAX_PASSWORD_LENGTH;

type AccountRow = { id: string; username: string; isAdmin: boolean };
const publicAccount = (account: AccountRow) => ({
  id: account.id,
  username: account.username,
  isAdmin: account.isAdmin,
});

const bodyOf = (request: FastifyRequest) => (request.body ?? {}) as Record<string, unknown>;

const isUniqueViolation = (error: unknown) =>
  typeof error === "object" && error !== null && (error as { code?: unknown }).code === "P2002";

const authRoutes: FastifyPluginAsync = async (app) => {
  // Public. Tells the web app which screen to show before anyone is signed in.
  app.get("/auth/status", async () => {
    const accounts = await prisma.account.count();
    return { setupRequired: accounts === 0 };
  });

  // The first account, made with API_TOKEN. It becomes the admin and takes
  // every profile that existed before accounts did.
  app.post("/auth/bootstrap", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "token") {
      return reply.code(403).send({ error: "Use the server's API token to create the first account" });
    }
    const body = bodyOf(request);
    const username = normalizeUsername(body.username);
    if (!username) return reply.code(400).send({ error: USERNAME_ERROR });
    if (!isValidPassword(body.password)) return reply.code(400).send({ error: PASSWORD_ERROR });

    const passwordHash = await hashPin(body.password);
    const account = await prisma.$transaction(async (tx) => {
      if ((await tx.account.count()) > 0) return null;
      const created = await tx.account.create({ data: { username, passwordHash, isAdmin: true } });
      await tx.profile.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      return created;
    });
    if (!account) return reply.code(409).send({ error: "An admin account already exists. Sign in instead." });

    invalidateProfileCache();
    const token = await createSession(account.id);
    return reply.code(201).send({ token, account: publicAccount(account) });
  });

  app.post("/auth/login", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const username = normalizeUsername(body.username);
    // Over-long input is refused before it reaches scrypt.
    const password =
      typeof body.password === "string" && body.password.length <= MAX_PASSWORD_LENGTH ? body.password : "";
    const failed = () => reply.code(401).send({ error: "Incorrect username or password" });

    const account = username ? await prisma.account.findUnique({ where: { username } }) : null;
    if (!account || !username) {
      await pinMatches(password || "x", DUMMY_PASSWORD_HASH);
      return failed();
    }

    const lockout = await getLockout(loginAttemptKey(username));
    if (lockout.locked) {
      return reply
        .code(429)
        .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
    }

    if (!password || !(await pinMatches(password, account.passwordHash))) {
      await recordFailure(loginAttemptKey(username));
      return failed();
    }

    await clearAttempts(loginAttemptKey(username));
    const token = await createSession(account.id);
    return { token, account: publicAccount(account) };
  });

  app.post("/auth/logout", async (request, reply) => {
    if (request.auth?.kind === "session") {
      await prisma.session.deleteMany({ where: { id: request.auth.sessionId } });
    }
    return reply.code(204).send();
  });

  // Who is signed in. `API_TOKEN` is not an account, so it answers null with
  // admin rights, which is what the web app uses to offer the first-run setup.
  app.get("/auth/me", async (request) => {
    if (request.auth?.kind !== "session") return { account: null, isAdmin: isAdminRequest(request) };
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    return { account: account ? publicAccount(account) : null, isAdmin: isAdminRequest(request) };
  });

  // Changing the password signs out every other device.
  app.post("/auth/password", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to change a password" });
    const body = bodyOf(request);
    if (!isValidPassword(body.newPassword)) return reply.code(400).send({ error: PASSWORD_ERROR });

    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });

    const lockout = await getLockout(loginAttemptKey(account.username));
    if (lockout.locked) {
      return reply
        .code(429)
        .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
    }
    const current = typeof body.currentPassword === "string" ? body.currentPassword : "";
    if (!current || !(await pinMatches(current, account.passwordHash))) {
      await recordFailure(loginAttemptKey(account.username));
      return reply.code(401).send({ error: "Incorrect current password" });
    }

    const sessionId = request.auth.sessionId;
    await prisma.$transaction([
      prisma.account.update({ where: { id: account.id }, data: { passwordHash: await hashPin(body.newPassword) } }),
      prisma.session.deleteMany({ where: { accountId: account.id, id: { not: sessionId } } }),
    ]);
    await clearAttempts(loginAttemptKey(account.username));
    return reply.code(204).send();
  });

  // ─── Invites (admin) ───

  // The code is returned once and only its digest is kept, so a lost link is
  // revoked and replaced rather than looked up again.
  app.post("/auth/invites", { preHandler: requireAdmin }, async (request, reply) => {
    const body = bodyOf(request);
    const days = body.expiresInDays === undefined ? DEFAULT_INVITE_DAYS : Number(body.expiresInDays);
    if (!Number.isInteger(days) || days < 1 || days > MAX_INVITE_DAYS) {
      return reply.code(400).send({ error: `expiresInDays must be a whole number from 1 to ${MAX_INVITE_DAYS}` });
    }

    const code = mintInviteCode();
    const createdById = request.auth?.kind === "session" ? request.auth.accountId : await findAdminAccountId();
    const invite = await prisma.invite.create({
      data: { codeHash: hashSecret(code), expiresAt: new Date(Date.now() + days * DAY_MS), createdById },
    });
    return reply.code(201).send({
      invite: { id: invite.id, code, expiresAt: invite.expiresAt.toISOString() },
      url: WEB_PUBLIC_BASE ? `${WEB_PUBLIC_BASE}/invite/${code}` : null,
    });
  });

  app.get("/auth/invites", { preHandler: requireAdmin }, async () => {
    const invites = await prisma.invite.findMany({
      orderBy: { createdAt: "desc" },
      include: { usedBy: { select: { username: true } } },
    });
    return {
      invites: invites.map((invite) => ({
        id: invite.id,
        createdAt: invite.createdAt.toISOString(),
        expiresAt: invite.expiresAt.toISOString(),
        usedAt: invite.usedAt?.toISOString() ?? null,
        usedBy: invite.usedBy?.username ?? null,
      })),
    };
  });

  app.delete<{ Params: { id: string } }>("/auth/invites/:id", { preHandler: requireAdmin }, async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    await prisma.invite.deleteMany({ where: { id: request.params.id } });
    return reply.code(204).send();
  });

  // Public. Redeems an invite: makes the account and its first profile, and
  // signs it in.
  app.post("/auth/register", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const code = typeof body.inviteCode === "string" ? body.inviteCode.trim() : "";
    const username = normalizeUsername(body.username);
    if (!code) return reply.code(400).send({ error: "inviteCode is required" });
    if (!username) return reply.code(400).send({ error: USERNAME_ERROR });
    if (!isValidPassword(body.password)) return reply.code(400).send({ error: PASSWORD_ERROR });

    const invalidInvite = { error: "This invite link is invalid, expired or already used", code: "invite_invalid" };
    const invite = await prisma.invite.findUnique({ where: { codeHash: hashSecret(code) } });
    if (!invite || invite.usedAt || invite.expiresAt.getTime() <= Date.now()) {
      return reply.code(400).send(invalidInvite);
    }

    const passwordHash = await hashPin(body.password);
    let account: AccountRow | null;
    try {
      account = await prisma.$transaction(async (tx) => {
        const created = await tx.account.create({ data: { username, passwordHash } });
        // Claimed with a condition rather than read-then-write, so two people
        // racing one link can't both get an account from it.
        const claimed = await tx.invite.updateMany({
          where: { id: invite.id, usedAt: null, expiresAt: { gt: new Date() } },
          data: { usedAt: new Date(), usedByAccountId: created.id },
        });
        if (claimed.count !== 1) throw new InviteAlreadyUsed();
        await tx.profile.create({ data: { name: username, accountId: created.id } });
        return created;
      });
    } catch (error) {
      if (error instanceof InviteAlreadyUsed) return reply.code(400).send(invalidInvite);
      if (isUniqueViolation(error)) return reply.code(409).send({ error: "That username is taken" });
      throw error;
    }

    invalidateProfileCache();
    const token = await createSession(account.id);
    return reply.code(201).send({ token, account: publicAccount(account) });
  });

  // ─── Accounts (admin) ───

  app.get("/auth/accounts", { preHandler: requireAdmin }, async () => {
    const accounts = await prisma.account.findMany({
      orderBy: { createdAt: "asc" },
      include: { _count: { select: { profiles: true } } },
    });
    return {
      accounts: accounts.map((account) => ({
        ...publicAccount(account),
        createdAt: account.createdAt.toISOString(),
        profileCount: account._count.profiles,
      })),
    };
  });

  // Deletes the account and everything its profiles own.
  app.delete<{ Params: { id: string } }>("/auth/accounts/:id", { preHandler: requireAdmin }, async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    const account = await prisma.account.findUnique({ where: { id: request.params.id } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    if (account.isAdmin) return reply.code(400).send({ error: "The admin account can't be deleted" });

    await prisma.account.delete({ where: { id: account.id } });
    invalidateProfileCache();
    return reply.code(204).send();
  });
};

class InviteAlreadyUsed extends Error {}

export default authRoutes;
