import type { FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import {
  clearAuthCookies,
  isWebClient,
  PROFILE_COOKIE,
  setProfileCookie,
  setSessionCookie,
} from "../lib/session-cookie.js";
import { PROFILE_TOKEN_TTL_MS, verifyProfileToken } from "../lib/profile-token.js";
import { prisma } from "../lib/prisma.js";
import { bearerToken, isApiToken } from "../lib/auth.js";
import { hashPin, pinMatches } from "../lib/pin-hash.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { invalidateDeviceCache } from "../lib/device-token.js";
import { invalidateProfileCache } from "../lib/profile.js";
import { adoptLegacyAiConfig, claimLegacyIntegrations } from "../lib/integration-owner.js";
import { resetTraktClient } from "../lib/trakt-client.js";
import { ADMIN_REQUIRED_RESPONSE, hashSecret, isAdminRequest, requireAdmin } from "../lib/request-auth.js";
import {
  createSession,
  findAdminAccountId,
  mintInviteCode,
  SESSION_COOKIE_MAX_AGE_SEC,
  SESSION_MAX_AGE_MS,
} from "../lib/sessions.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { listAccountEvents, recordAccountEvent, requestOrigin } from "../lib/account-events.js";
import {
  deleteAccountAndData,
  isValidPassword,
  MAX_PASSWORD_LENGTH,
  PASSWORD_ERROR,
  removeTotp,
  setTemporaryPassword,
} from "../lib/account-admin.js";
import { decryptSecret, encryptSecret, SECRET_CONTEXT } from "../lib/secret-box.js";
import {
  challengeMatchesAccount,
  generateRecoveryCodes,
  generateTotpSecret,
  hashRecoveryCode,
  matchTotp,
  mintTotpChallenge,
  normalizeRecoveryCode,
  normalizeTotpCode,
  readTotpChallenge,
  totpUri,
} from "../lib/totp.js";

// Sign-in for the website. Invite-only: the first account is the admin, made
// with API_TOKEN (`POST /auth/bootstrap`); everyone else redeems a single-use
// invite the admin created (`POST /auth/register`). Passwords use the same
// scrypt hashing as profile PINs.
//
// Recovery: the admin can give a member a temporary password and hand the
// admin role on; a forgotten admin password is reset from the server's shell
// with `fetch reset-password` (src/cli.ts, docs/troubleshooting.md).
//
// Two-factor sign-in is optional, per account and off by default (#564). With
// it on, a right password at `POST /auth/login` earns a challenge rather than a
// session, and `POST /auth/login/totp` takes that challenge with a code from
// the authenticator, or one of the recovery codes, to finish signing in. A lost
// authenticator is recovered with a recovery code, by the admin, or with
// `fetch reset-2fa`. TVs sign in by pairing and are not asked.

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

type AccountRow = {
  id: string;
  username: string;
  isAdmin: boolean;
  mustChangePassword?: boolean;
  totpSecret?: string | null;
};
const publicAccount = (account: AccountRow) => ({
  id: account.id,
  username: account.username,
  isAdmin: account.isAdmin,
  mustChangePassword: account.mustChangePassword ?? false,
  totpEnabled: Boolean(account.totpSecret),
});

type TotpAccount = {
  id: string;
  username: string;
  totpSecret: string | null;
  totpLastUsedStep: number | null;
  totpRecoveryCodes: string[];
};

/**
 * Checks a second-factor code for an account with two-factor on: a code from
 * the authenticator, or failing that a recovery code, which is used up. Each
 * is accepted once (a code's time step is recorded; a recovery code is
 * removed) with a conditional write, so two requests racing with the same code
 * can't both get in.
 *
 * The caller counts a failure towards the lockout.
 */
const checkSecondFactor = async (
  account: TotpAccount,
  body: Record<string, unknown>
): Promise<"code" | "recovery_code" | null> => {
  const code = normalizeTotpCode(body.code);
  if (code && account.totpSecret) {
    // An undecryptable secret means API_TOKEN changed since it was saved; only
    // a recovery code (or the admin, or `fetch reset-2fa`) gets past it then.
    const secret = decryptSecret(SECRET_CONTEXT.accountTotpSecret, account.totpSecret);
    const step = secret ? matchTotp(secret, code, Date.now(), account.totpLastUsedStep) : null;
    if (step !== null) {
      const { count } = await prisma.account.updateMany({
        where: {
          id: account.id,
          OR: [{ totpLastUsedStep: null }, { totpLastUsedStep: { lt: step } }],
        },
        data: { totpLastUsedStep: step },
      });
      if (count === 1) return "code";
    }
    return null;
  }

  const recovery = normalizeRecoveryCode(body.recoveryCode ?? body.code);
  if (recovery) {
    const hash = hashRecoveryCode(recovery);
    if (!account.totpRecoveryCodes.includes(hash)) return null;
    const { count } = await prisma.account.updateMany({
      where: { id: account.id, totpRecoveryCodes: { has: hash } },
      data: { totpRecoveryCodes: account.totpRecoveryCodes.filter((stored) => stored !== hash) },
    });
    return count === 1 ? "recovery_code" : null;
  }
  return null;
};

/** The recovery codes to show once, and their hashes to keep. */
const freshRecoveryCodes = () => {
  const codes = generateRecoveryCodes();
  return { codes, hashes: codes.map((code) => hashRecoveryCode(normalizeRecoveryCode(code)!)) };
};

const WRONG_CODE_RESPONSE = { error: "That code isn't right", code: "totp_incorrect" } as const;

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

const firstHeader = (value: string | string[] | undefined): string | undefined =>
  (Array.isArray(value) ? value[0] : value)?.trim() || undefined;

const bodyOf = (request: FastifyRequest) => (request.body ?? {}) as Record<string, unknown>;

/**
 * Starts a session for `account` and answers with it. The website (which says
 * so with `X-Fetch-Client: web`) gets it as an HttpOnly cookie and never sees
 * the token; any other client gets the token in the body, as it always has.
 * The website's profile cookie belonged to whoever was signed in before, so it
 * goes.
 */
const signedIn = async (request: FastifyRequest, reply: FastifyReply, account: AccountRow, status: 200 | 201) => {
  const { token, sessionId } = await createSession(account.id, requestOrigin(request));
  await recordAccountEvent(account.id, "sign_in", { request });
  if (isWebClient(request)) {
    clearAuthCookies(request, reply, [PROFILE_COOKIE]);
    setSessionCookie(request, reply, token, SESSION_COOKIE_MAX_AGE_SEC);
    return reply.code(status).send({ account: publicAccount(account), sessionId });
  }
  return reply.code(status).send({ token, account: publicAccount(account), sessionId });
};

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
    return signedIn(request, reply, account, 201);
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

    // Two-factor on: the password was right, but that earns a challenge, not a
    // session. The failed-attempt count is left as it is, since wrong codes
    // count towards the same lockout and a right password must not reset it
    // between guesses at the code.
    if (account.totpSecret) {
      return reply.code(200).send({ totpRequired: true, challenge: mintTotpChallenge(account) });
    }

    await clearAttempts(attemptKey);
    return signedIn(request, reply, account, 200);
  });

  // The second step of signing in with two-factor on: the challenge from
  // `POST /auth/login` and a code from the authenticator, or a recovery code.
  app.post("/auth/login/totp", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const expired = () =>
      reply.code(401).send({ error: "Sign-in timed out. Enter your password again.", code: "totp_challenge_invalid" });

    const accountId = readTotpChallenge(body.challenge);
    if (!accountId || !UUID_V4_PATTERN.test(accountId)) return expired();
    const account = await prisma.account.findUnique({ where: { id: accountId } });
    if (!account?.totpSecret || !challengeMatchesAccount(body.challenge as string, account)) return expired();

    const attemptKey = loginAttemptKey(account.username, request);
    const lockout = await getLockout(attemptKey);
    if (lockout.locked) return reply.code(429).send(lockedOut(lockout.retryAfterSec));

    const used = await checkSecondFactor(account, body);
    if (!used) {
      await recordFailure(attemptKey);
      await recordAccountEvent(account.id, "totp_failed", { request });
      return reply.code(401).send(WRONG_CODE_RESPONSE);
    }

    await clearAttempts(attemptKey);
    if (used === "recovery_code") {
      await recordAccountEvent(account.id, "recovery_code_used", {
        request,
        detail: `${account.totpRecoveryCodes.length - 1} left`,
      });
    }
    return signedIn(request, reply, account, 200);
  });

  // Moves a browser's session from a bearer token into the cookie. The website
  // used to keep its token in localStorage; on its first load after the switch
  // it sends that token here once, gets the same session back as a cookie, and
  // deletes its copy — so nobody is signed out by the upgrade. The session is
  // not re-issued: its expiry, and revoking it from another device, work as
  // they always did. Called with the cookie already in place it just answers.
  app.post("/auth/session", async (request, reply) => {
    if (request.auth?.kind !== "session") {
      return reply.code(400).send({ error: "Only a signed-in session can be moved into a cookie" });
    }
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    const token = request.authVia === "bearer" ? bearerToken(request) : null;
    if (token) setSessionCookie(request, reply, token, SESSION_COOKIE_MAX_AGE_SEC);
    // A PIN-protected profile's access token moves the same way, if it is
    // still good, so the upgrade doesn't ask for the PIN again either.
    const profileId = firstHeader(request.headers["x-profile-id"]);
    const profileToken = firstHeader(request.headers["x-profile-token"]);
    if (token && profileId && profileToken && UUID_V4_PATTERN.test(profileId)) {
      // Checked against the profile's PIN as it is now, like any use of the
      // token, and only for one of this account's own profiles.
      const profile = await prisma.profile.findUnique({
        where: { id: profileId },
        select: { id: true, pinHash: true, accountId: true },
      });
      if (
        profile &&
        profile.accountId === request.auth.accountId &&
        verifyProfileToken(profileToken, profile.id, profile.pinHash)
      ) {
        setProfileCookie(request, reply, profileToken, PROFILE_TOKEN_TTL_MS / 1000);
      }
    }
    return { account: publicAccount(account), sessionId: request.auth.sessionId };
  });

  app.post("/auth/logout", async (request, reply) => {
    if (request.auth?.kind === "session") {
      await prisma.session.deleteMany({ where: { id: request.auth.sessionId } });
    }
    // Always, whatever the request authenticated with: a browser signing out
    // must not keep a cookie behind, even one the server has already forgotten.
    clearAuthCookies(request, reply);
    return reply.code(204).send();
  });

  // Who is signed in. `API_TOKEN` is not an account, so it answers null with
  // admin rights, which is what the web app uses to offer the first-run setup.
  // `sessionId` is the session row's id — not a credential — which the website
  // partitions its offline cache by.
  app.get("/auth/me", async (request) => {
    if (request.auth?.kind !== "session") {
      return { account: null, isAdmin: isAdminRequest(request), sessionId: null };
    }
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    return {
      account: account ? publicAccount(account) : null,
      isAdmin: isAdminRequest(request),
      sessionId: request.auth.sessionId,
    };
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
    clearAuthCookies(request, reply);
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
    } else {
      // This browser's own sign-in: its cookie names nothing now.
      clearAuthCookies(request, reply);
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
  // ─── Two-factor sign-in (the account's own) ───

  app.get("/auth/totp", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to see two-factor sign-in" });
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    return {
      enabled: Boolean(account.totpSecret),
      enabledAt: account.totpEnabledAt?.toISOString() ?? null,
      recoveryCodesLeft: account.totpSecret ? account.totpRecoveryCodes.length : 0,
    };
  });

  // Starts setting up two-factor: a new secret, held aside until a code from
  // it is confirmed. The password is asked again, so a browser left signed in
  // can't be used to tie the account to someone else's phone.
  app.post("/auth/totp/setup", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to set up two-factor sign-in" });
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    if (account.totpSecret) {
      return reply.code(409).send({ error: "Two-factor sign-in is already on", code: "totp_already_enabled" });
    }
    const proof = await verifyOwnPassword(request, account, bodyOf(request).currentPassword);
    if (!proof.ok) return reply.code(proof.status).send(proof.body);

    const secret = generateTotpSecret();
    await prisma.account.update({
      where: { id: account.id },
      data: { totpPendingSecret: encryptSecret(SECRET_CONTEXT.accountTotpPendingSecret, secret) },
    });
    return { secret, uri: totpUri(secret, account.username) };
  });

  // Finishes setting up: one code from the new secret turns two-factor on and
  // answers with the recovery codes, which are shown this once.
  app.post("/auth/totp/enable", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to set up two-factor sign-in" });
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    if (account.totpSecret) {
      return reply.code(409).send({ error: "Two-factor sign-in is already on", code: "totp_already_enabled" });
    }
    const pending = account.totpPendingSecret
      ? decryptSecret(SECRET_CONTEXT.accountTotpPendingSecret, account.totpPendingSecret)
      : null;
    if (!pending) {
      return reply.code(400).send({ error: "Start setting up two-factor sign-in again", code: "totp_setup_missing" });
    }
    const code = normalizeTotpCode(bodyOf(request).code);
    const step = code ? matchTotp(pending, code, Date.now()) : null;
    if (step === null) return reply.code(400).send(WRONG_CODE_RESPONSE);

    const { codes, hashes } = freshRecoveryCodes();
    await prisma.account.update({
      where: { id: account.id },
      data: {
        totpSecret: encryptSecret(SECRET_CONTEXT.accountTotpSecret, pending),
        totpPendingSecret: null,
        totpEnabledAt: new Date(),
        totpLastUsedStep: step,
        totpRecoveryCodes: hashes,
      },
    });
    await recordAccountEvent(account.id, "totp_enabled", { request });
    return { recoveryCodes: codes };
  });

  // Turning two-factor off, or replacing the recovery codes, asks for the
  // password and a current code (or a recovery code).
  const reauthWithSecondFactor = async (request: FastifyRequest, reply: FastifyReply) => {
    if (request.auth?.kind !== "session") {
      void reply.code(400).send({ error: "Sign in to change two-factor sign-in" });
      return null;
    }
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) {
      void reply.code(404).send({ error: "Account not found" });
      return null;
    }
    if (!account.totpSecret) {
      void reply.code(400).send({ error: "Two-factor sign-in is off", code: "totp_not_enabled" });
      return null;
    }
    const body = bodyOf(request);
    const proof = await verifyOwnPassword(request, account, body.currentPassword);
    if (!proof.ok) {
      void reply.code(proof.status).send(proof.body);
      return null;
    }
    const attemptKey = loginAttemptKey(account.username, request);
    const used = await checkSecondFactor(account, body);
    if (!used) {
      await recordFailure(attemptKey);
      await recordAccountEvent(account.id, "totp_failed", { request });
      void reply.code(401).send(WRONG_CODE_RESPONSE);
      return null;
    }
    if (used === "recovery_code") await recordAccountEvent(account.id, "recovery_code_used", { request });
    return account;
  };

  app.post("/auth/totp/disable", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const account = await reauthWithSecondFactor(request, reply);
    if (!account) return reply;
    await removeTotp(account.id);
    await recordAccountEvent(account.id, "totp_disabled", { request });
    return reply.code(204).send();
  });

  app.post("/auth/totp/recovery-codes", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const account = await reauthWithSecondFactor(request, reply);
    if (!account) return reply;
    const { codes, hashes } = freshRecoveryCodes();
    await prisma.account.update({ where: { id: account.id }, data: { totpRecoveryCodes: hashes } });
    return { recoveryCodes: codes };
  });

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
    return signedIn(request, reply, account, 201);
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

  // Turns off two-factor sign-in for a member who lost their authenticator and
  // their recovery codes. The admin's own password is asked again, as for a
  // temporary password; the admin turns their own off in Account.
  app.post<{ Params: { id: string } }>(
    "/auth/accounts/:id/totp/remove",
    { preHandler: requireAdmin, ...CREDENTIAL_RATE_LIMIT },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
      if (request.auth?.kind === "session" && request.auth.accountId === request.params.id) {
        return reply.code(400).send({ error: "Turn off your own two-factor sign-in in Account" });
      }
      const target = await prisma.account.findUnique({ where: { id: request.params.id } });
      if (!target) return reply.code(404).send({ error: "Account not found" });
      if (!target.totpSecret) return reply.code(400).send({ error: "That account doesn't use two-factor sign-in" });
      const proof = await verifyAdminReauth(request, bodyOf(request).adminPassword);
      if (!proof.ok) return reply.code(proof.status).send(proof.body);

      await removeTotp(target.id);
      await recordAccountEvent(target.id, "totp_disabled", { request, detail: "By the admin" });
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
