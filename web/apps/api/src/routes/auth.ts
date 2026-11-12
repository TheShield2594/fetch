import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { prisma } from "../lib/prisma.js";
import { bearerToken, isApiToken } from "../lib/auth.js";
import { hashPin, pinMatches } from "../lib/pin-hash.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { invalidateDeviceCache } from "../lib/device-token.js";
import { invalidateProfileCache } from "../lib/profile.js";
import { adoptLegacyAiConfig, claimLegacyIntegrations } from "../lib/integration-owner.js";
import { resetTraktClient } from "../lib/trakt-client.js";
import { ADMIN_REQUIRED_RESPONSE, hashSecret, isAdminRequest, requireAdmin } from "../lib/request-auth.js";
import { createSession, findAdminAccountId, mintInviteCode, SESSION_MAX_AGE_MS } from "../lib/sessions.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { listAccountEvents, recordAccountEvent, requestOrigin } from "../lib/account-events.js";
import {
  deleteAccountAndData,
  isValidPassword,
  MAX_PASSWORD_LENGTH,
  PASSWORD_ERROR,
  setTemporaryPassword,
} from "../lib/account-admin.js";

// Sign-in for the website. Invite-only: the first account is the admin, made
// with API_TOKEN (`POST /auth/bootstrap`); everyone else redeems a single-use
// invite the admin created (`POST /auth/register`). Passwords use the same
// scrypt hashing as profile PINs.
//
// Recovery: the admin can give a member a temporary password and hand the
// admin role on; a forgotten admin password is reset from the server's shell
// with `fetch reset-password` (src/cli.ts, docs/troubleshooting.md).

const USERNAME_PATTERN = /^[a-z0-9_.-]{3,32}$/;
const USERNAME_ERROR = "Username must be 3–32 characters: letters, numbers, dots, dashes or underscores";

const DEFAULT_INVITE_DAYS = 7;
const MAX_INVITE_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

const WEB_PUBLIC_BASE = (process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.replace(/\/+$/, "");

// The same budget as PIN verification: these take a password, and the server
// is reachable from the internet.
const CREDENTIAL_RATE_LIMIT = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };

/**
 * Compared against when the username doesn't exist, so a wrong username takes
 * as long to reject as a wrong password and the timing doesn't say which.
 */
const DUMMY_PASSWORD_HASH = await hashPin("not-a-real-password");

/**
 * Wrong passwords are counted per (username, client IP). Keyed on the name
 * alone, anyone could keep an account — the admin's included — locked out
 * indefinitely from one address by guessing badly every so often; per IP, a
 * guesser locks out only themselves. The route's rate limit already bounds how
 * fast any one address can guess.
 */
const loginAttemptKey = (username: string, request: FastifyRequest) => `loginattempts:${username}:${request.ip}`;

const normalizeUsername = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const username = value.trim().toLowerCase();
  return USERNAME_PATTERN.test(username) ? username : null;
};

type AccountRow = { id: string; username: string; isAdmin: boolean; mustChangePassword?: boolean };
const publicAccount = (account: AccountRow) => ({
  id: account.id,
  username: account.username,
  isAdmin: account.isAdmin,
  mustChangePassword: account.mustChangePassword ?? false,
});

const lockedOut = (retryAfterSec: number) => ({
  error: "Too many incorrect attempts. Try again later.",
  retryAfterSec,
});

/**
 * Checks the signed-in account's own password, for the changes that ask for it
 * again (a new password or username, deleting the account, the admin's
 * recovery actions). Wrong guesses count towards the same lockout as signing
 * in from this address.
 */
const verifyOwnPassword = async (
  request: FastifyRequest,
  account: { username: string; passwordHash: string },
  raw: unknown
): Promise<{ ok: true } | { ok: false; status: 401 | 429; body: object }> => {
  const attemptKey = loginAttemptKey(account.username, request);
  const lockout = await getLockout(attemptKey);
  if (lockout.locked) return { ok: false, status: 429, body: lockedOut(lockout.retryAfterSec) };
  const password = typeof raw === "string" && raw.length <= MAX_PASSWORD_LENGTH ? raw : "";
  if (!password || !(await pinMatches(password, account.passwordHash))) {
    await recordFailure(attemptKey);
    return { ok: false, status: 401, body: { error: "Incorrect password", code: "password_incorrect" } };
  }
  await clearAttempts(attemptKey);
  return { ok: true };
};

/**
 * The admin's own password, re-entered for an action on someone else's
 * account. `API_TOKEN` is the operator and needs none.
 */
const verifyAdminReauth = async (request: FastifyRequest, raw: unknown) => {
  if (request.auth?.kind !== "session") return { ok: true as const };
  const admin = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
  if (!admin) return { ok: false as const, status: 401 as const, body: { error: "Account not found" } };
  return verifyOwnPassword(request, admin, raw);
};

const bodyOf = (request: FastifyRequest) => (request.body ?? {}) as Record<string, unknown>;

const isUniqueViolation = (error: unknown) =>
  typeof error === "object" && error !== null && (error as { code?: unknown }).code === "P2002";

const authRoutes: FastifyPluginAsync = async (app) => {
  // Public. Tells the web app which screen to show before anyone is signed in.
  // With `API_TOKEN` it also says whether more than one account exists: the
  // Stremio addon service asks, because the keyless addon URLs it can serve
  // are only safe while one account owns every profile. Anyone else is told
  // nothing about how many accounts there are.
  app.get("/auth/status", async (request) => {
    const accounts = await prisma.account.count();
    const token = bearerToken(request);
    if (token && isApiToken(token)) return { setupRequired: accounts === 0, multipleAccounts: accounts > 1 };
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
      // TVs paired before accounts existed see the profiles they always did.
      await tx.device.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      await tx.devicePairing.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      // So do the Trakt accounts the install had connected.
      await claimLegacyIntegrations(tx, created.id);
      return created;
    });
    if (!account) return reply.code(409).send({ error: "An admin account already exists. Sign in instead." });

    invalidateProfileCache();
    resetTraktClient();
    await adoptLegacyAiConfig();
    const token = await createSession(account.id, requestOrigin(request));
    await recordAccountEvent(account.id, "sign_in", { request });
    return reply.code(201).send({ token, account: publicAccount(account) });
  });

  app.post("/auth/login", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const username = normalizeUsername(body.username);
    // Over-long input is refused before it reaches scrypt.
    const password =
      typeof body.password === "string" && body.password.length <= MAX_PASSWORD_LENGTH ? body.password : "";
    const failed = () => reply.code(401).send({ error: "Incorrect username or password" });

    // A name that can't be valid can't exist either, so refusing it reveals
    // nothing and needs no attempt record.
    if (!username) {
      await pinMatches(password || "x", DUMMY_PASSWORD_HASH);
      return failed();
    }

    // The lockout is checked, and a failure recorded, the same way whether or
    // not the account exists: a 429 that only real names could earn would say
    // which names are real.
    const attemptKey = loginAttemptKey(username, request);
    const lockout = await getLockout(attemptKey);
    if (lockout.locked) return reply.code(429).send(lockedOut(lockout.retryAfterSec));

    // Exactly one hash comparison per attempt, whatever the username or the
    // password looks like: an empty or over-long password used to skip it for
    // a name that exists and run it for one that doesn't, which answered
    // faster for real names. The outcome is decided only afterwards.
    const account = await prisma.account.findUnique({ where: { username } });
    const matches = await pinMatches(password || "x", account?.passwordHash ?? DUMMY_PASSWORD_HASH);
    if (!account || !password || !matches) {
      await recordFailure(attemptKey);
      // Not awaited: the write happens only for names that exist, and waiting
      // for it would make those answers slower than the rest.
      if (account) void recordAccountEvent(account.id, "sign_in_failed", { request });
      return failed();
    }

    await clearAttempts(attemptKey);
    const token = await createSession(account.id, requestOrigin(request));
    await recordAccountEvent(account.id, "sign_in", { request });
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

  // Changing the username. The password is asked again, as for any change to
  // how the account signs in.
  app.patch("/auth/me", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to change your account" });
    const body = bodyOf(request);
    const username = normalizeUsername(body.username);
    if (!username) return reply.code(400).send({ error: USERNAME_ERROR });

    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    const proof = await verifyOwnPassword(request, account, body.currentPassword);
    if (!proof.ok) return reply.code(proof.status).send(proof.body);
    if (username === account.username) return { account: publicAccount(account) };

    try {
      const updated = await prisma.account.update({ where: { id: account.id }, data: { username } });
      await recordAccountEvent(account.id, "username_changed", { request, detail: `${account.username} → ${username}` });
      return { account: publicAccount(updated) };
    } catch (error) {
      if (isUniqueViolation(error)) return reply.code(409).send({ error: "That username is taken" });
      throw error;
    }
  });

  // Deleting your own account, with everything in it. The admin can't: the
  // install would be left with nobody to run it, so admin is handed on first.
  app.delete("/auth/me", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to delete your account" });
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    if (account.isAdmin) {
      return reply
        .code(400)
        .send({ error: "Make another account the admin before deleting this one", code: "admin_cannot_delete" });
    }
    const proof = await verifyOwnPassword(request, account, bodyOf(request).currentPassword);
    if (!proof.ok) return reply.code(proof.status).send(proof.body);

    await deleteAccountAndData(account.id);
    return reply.code(204).send();
  });

  // Changing the password signs out every other browser. TVs stay signed in
  // unless `signOutTvs` says otherwise: the password is not what they hold.
  app.post("/auth/password", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to change a password" });
    const body = bodyOf(request);
    if (!isValidPassword(body.newPassword)) return reply.code(400).send({ error: PASSWORD_ERROR });

    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });

    const attemptKey = loginAttemptKey(account.username, request);
    const lockout = await getLockout(attemptKey);
    if (lockout.locked) return reply.code(429).send(lockedOut(lockout.retryAfterSec));
    const current = typeof body.currentPassword === "string" ? body.currentPassword : "";
    if (!current || !(await pinMatches(current, account.passwordHash))) {
      await recordFailure(attemptKey);
      return reply.code(401).send({ error: "Incorrect current password" });
    }

    const sessionId = request.auth.sessionId;
    const signOutTvs = body.signOutTvs === true;
    await prisma.$transaction([
      prisma.account.update({
        where: { id: account.id },
        data: { passwordHash: await hashPin(body.newPassword), mustChangePassword: false },
      }),
      prisma.session.deleteMany({ where: { accountId: account.id, id: { not: sessionId } } }),
      ...(signOutTvs ? [prisma.device.deleteMany({ where: { accountId: account.id } })] : []),
    ]);
    if (signOutTvs) invalidateDeviceCache();
    await clearAttempts(attemptKey);
    await recordAccountEvent(account.id, "password_changed", {
      request,
      detail: signOutTvs ? "TVs signed out" : null,
    });
    return reply.code(204).send();
  });

  // ─── Where the account is signed in ───

  // Browsers and TVs in one list, newest use first. A browser is ended with
  // `DELETE /auth/sessions/:id`, a TV with `DELETE /devices/:id`.
  app.get("/auth/sessions", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to see your sign-ins" });
    const { accountId, sessionId } = request.auth;
    const [sessions, devices] = await Promise.all([
      // Both limits, as findSession applies them: a session from before the
      // absolute lifetime existed can still carry a later sliding expiry.
      prisma.session.findMany({
        where: {
          accountId,
          expiresAt: { gt: new Date() },
          createdAt: { gt: new Date(Date.now() - SESSION_MAX_AGE_MS) },
        },
        orderBy: { lastUsedAt: "desc" },
      }),
      prisma.device.findMany({ where: { accountId }, orderBy: { createdAt: "asc" } }),
    ]);
    return {
      sessions: [
        ...sessions.map((session) => ({
          kind: "browser" as const,
          id: session.id,
          current: session.id === sessionId,
          userAgent: session.userAgent,
          ipAddress: session.ipAddress,
          createdAt: session.createdAt.toISOString(),
          lastUsedAt: session.lastUsedAt.toISOString(),
          expiresAt: session.expiresAt.toISOString(),
        })),
        ...devices.map((device) => ({
          kind: "tv" as const,
          id: device.id,
          current: false,
          name: device.name,
          appVersion: device.appVersion,
          createdAt: device.createdAt.toISOString(),
          lastUsedAt: device.lastSeenAt?.toISOString() ?? null,
        })),
      ],
    };
  });

  app.delete<{ Params: { id: string } }>("/auth/sessions/:id", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to manage your sign-ins" });
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    const { count } = await prisma.session.deleteMany({
      where: { id: request.params.id, accountId: request.auth.accountId },
    });
    if (count === 0) return reply.code(404).send({ error: "Sign-in not found" });
    if (request.params.id !== request.auth.sessionId) {
      await recordAccountEvent(request.auth.accountId, "session_revoked", { request });
    }
    return reply.code(204).send();
  });

  // Ends every other browser's sign-in, and with `includeTvs` every TV's too.
  // This browser stays signed in; "Sign out" is how it leaves.
  app.post("/auth/sessions/revoke-others", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to manage your sign-ins" });
    const { accountId, sessionId } = request.auth;
    const includeTvs = bodyOf(request).includeTvs === true;
    const sessions = await prisma.session.deleteMany({ where: { accountId, id: { not: sessionId } } });
    const devices = includeTvs ? await prisma.device.deleteMany({ where: { accountId } }) : { count: 0 };
    // Cleared so a signed-out TV's next request is looked up again and refused.
    if (includeTvs) invalidateDeviceCache();
    await recordAccountEvent(accountId, "signed_out_everywhere", {
      request,
      detail: includeTvs ? "Browsers and TVs" : "Browsers",
    });
    return { browsers: sessions.count, tvs: devices.count };
  });

  // The account's recent security events: sign-ins, failed attempts, TV
  // pairings, password and PIN changes. The last 50, from the last 90 days.
  app.get("/auth/activity", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to see your activity" });
    return { events: await listAccountEvents(request.auth.accountId) };
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
    const token = await createSession(account.id, requestOrigin(request));
    await recordAccountEvent(account.id, "sign_in", { request });
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

    await deleteAccountAndData(account.id);
    return reply.code(204).send();
  });

  // A temporary password for a member who forgot theirs. They are signed out
  // everywhere and asked for a new one when they next sign in. The admin's own
  // password is asked again; the admin changes their own in Account.
  app.post<{ Params: { id: string } }>(
    "/auth/accounts/:id/password",
    { preHandler: requireAdmin, ...CREDENTIAL_RATE_LIMIT },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
      const body = bodyOf(request);
      if (!isValidPassword(body.newPassword)) return reply.code(400).send({ error: PASSWORD_ERROR });
      if (request.auth?.kind === "session" && request.auth.accountId === request.params.id) {
        return reply.code(400).send({ error: "Change your own password in Account" });
      }
      const target = await prisma.account.findUnique({ where: { id: request.params.id } });
      if (!target) return reply.code(404).send({ error: "Account not found" });
      const proof = await verifyAdminReauth(request, body.adminPassword);
      if (!proof.ok) return reply.code(proof.status).send(proof.body);

      await setTemporaryPassword(target.id, body.newPassword);
      await recordAccountEvent(target.id, "password_reset", { request, detail: "By the admin" });
      return reply.code(204).send();
    }
  );

  // Hands the admin role to another account. There is one admin at a time, so
  // the caller stops being it. The admin's password is asked again.
  app.post<{ Params: { id: string } }>(
    "/auth/accounts/:id/make-admin",
    { preHandler: requireAdmin, ...CREDENTIAL_RATE_LIMIT },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
      const target = await prisma.account.findUnique({ where: { id: request.params.id } });
      if (!target) return reply.code(404).send({ error: "Account not found" });
      if (target.isAdmin) return reply.code(400).send({ error: "That account is already the admin" });
      const proof = await verifyAdminReauth(request, bodyOf(request).adminPassword);
      if (!proof.ok) return reply.code(proof.status).send(proof.body);

      // One transfer at a time: two running at once under READ COMMITTED could
      // each demote the old admin and promote their own target, leaving two
      // admins. The advisory lock serialises them, and the checks are made
      // again under it, so the second one finds the state the first left.
      const outcome = await prisma.$transaction(async (tx) => {
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext('fetch:admin-transfer'))`;
        if (request.auth?.kind === "session") {
          const caller = await tx.account.findUnique({ where: { id: request.auth.accountId }, select: { isAdmin: true } });
          if (!caller?.isAdmin) return "not_admin" as const;
        }
        const current = await tx.account.findUnique({ where: { id: target.id }, select: { isAdmin: true } });
        if (!current) return "gone" as const;
        if (current.isAdmin) return "already" as const;
        const demoted = await tx.account.findMany({ where: { isAdmin: true }, select: { id: true, username: true } });
        await tx.account.updateMany({ where: { isAdmin: true }, data: { isAdmin: false } });
        await tx.account.update({ where: { id: target.id }, data: { isAdmin: true } });
        return demoted;
      });
      if (outcome === "not_admin") return reply.code(403).send(ADMIN_REQUIRED_RESPONSE);
      if (outcome === "gone") return reply.code(404).send({ error: "Account not found" });
      if (outcome === "already") return reply.code(400).send({ error: "That account is already the admin" });
      const previous = outcome;
      await recordAccountEvent(target.id, "admin_granted", {
        request,
        detail: previous.length ? `From ${previous.map((account) => account.username).join(", ")}` : null,
      });
      for (const account of previous) {
        await recordAccountEvent(account.id, "admin_transferred", { request, detail: `To ${target.username}` });
      }
      return reply.code(204).send();
    }
  );
};

class InviteAlreadyUsed extends Error {}

export default authRoutes;
