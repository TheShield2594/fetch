import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { prisma } from "../lib/prisma.js";
import { hashPin, pinMatches } from "../lib/pin-hash.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { invalidateDeviceCache } from "../lib/device-token.js";
import { invalidateProfileCache } from "../lib/profile.js";
import { adoptLegacyAiConfig, claimLegacyIntegrations, deleteOwnerIntegrations } from "../lib/integration-owner.js";
import { resetTraktClient } from "../lib/trakt-client.js";
import { deleteProfileConnectionState } from "../lib/profile-connections.js";
import { hashSecret, isAdminRequest, requireAdmin } from "../lib/request-auth.js";
import { createSession, findAdminAccountId, mintInviteCode } from "../lib/sessions.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { isMailConfigured, normalizeEmail, webLink } from "../lib/mailer.js";
import {
  isAccountEmailAvailable,
  sendEmailVerification,
  sendInviteEmail,
  sendPasswordReset,
} from "../lib/account-email.js";

// Sign-in for the website. Invite-only: the first account is the admin, made
// with API_TOKEN (`POST /auth/bootstrap`); everyone else redeems a single-use
// invite the admin created (`POST /auth/register`). Passwords use the same
// scrypt hashing as profile PINs.
//
// An account may also have an email address. It is optional, signs in in place
// of the username, and — once confirmed through a mailed link, which needs SMTP
// (lib/mailer.ts) — is where password resets and, if the account turns them
// on, notifications go.

const USERNAME_PATTERN = /^[a-z0-9_.-]{3,32}$/;
const USERNAME_ERROR = "Username must be 3–32 characters: letters, numbers, dots, dashes or underscores";
const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_LENGTH = 256;
const PASSWORD_ERROR = `Password must be between ${MIN_PASSWORD_LENGTH} and ${MAX_PASSWORD_LENGTH} characters`;

const DEFAULT_INVITE_DAYS = 7;
const MAX_INVITE_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

const WEB_PUBLIC_BASE = (process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.replace(/\/+$/, "");
const EMAIL_ERROR = "Enter a valid email address";
const EMAIL_TAKEN = { error: "That email address is already used by another account", code: "email_taken" };

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

type AccountRow = {
  id: string;
  username: string;
  isAdmin: boolean;
  email: string | null;
  emailVerifiedAt: Date | null;
  emailNotifications: boolean;
};
const publicAccount = (account: AccountRow) => ({
  id: account.id,
  username: account.username,
  isAdmin: account.isAdmin,
  email: account.email,
  emailVerified: account.emailVerifiedAt !== null,
  emailNotifications: account.emailNotifications,
});

/**
 * An optional email field: `undefined` when it was left out or blank, `null`
 * when it was given but isn't an address, otherwise the normalized address.
 */
const optionalEmail = (value: unknown): string | null | undefined =>
  value === undefined || value === null || (typeof value === "string" && value.trim() === "")
    ? undefined
    : normalizeEmail(value);

/**
 * Mails a confirmation for a newly set address. Never fails the request that
 * set it: the address is saved either way, and Settings can send it again.
 */
const verifyInBackground = (request: FastifyRequest, account: AccountRow) => {
  if (!account.email || account.emailVerifiedAt) return Promise.resolve(false);
  return sendEmailVerification({ id: account.id, username: account.username, email: account.email }).catch(
    (error: unknown) => {
      request.log.warn({ err: error }, "could not send the email confirmation");
      return false;
    }
  );
};

const bodyOf = (request: FastifyRequest) => (request.body ?? {}) as Record<string, unknown>;

const isUniqueViolation = (error: unknown) =>
  typeof error === "object" && error !== null && (error as { code?: unknown }).code === "P2002";

const authRoutes: FastifyPluginAsync = async (app) => {
  // Public. Tells the web app which screen to show before anyone is signed in.
  app.get("/auth/status", async () => {
    const accounts = await prisma.account.count();
    // `emailEnabled` says whether this server can send mail, so the web app
    // only offers what will actually arrive (confirmations, resets).
    return { setupRequired: accounts === 0, emailEnabled: isAccountEmailAvailable() };
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
    const email = optionalEmail(body.email);
    if (email === null) return reply.code(400).send({ error: EMAIL_ERROR });

    const passwordHash = await hashPin(body.password);
    const account = await prisma.$transaction(async (tx) => {
      if ((await tx.account.count()) > 0) return null;
      const created = await tx.account.create({ data: { username, passwordHash, isAdmin: true, email: email ?? null } });
      await tx.profile.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      // TVs paired before accounts existed see the profiles they always did.
      await tx.device.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      await tx.devicePairing.updateMany({ where: { accountId: null }, data: { accountId: created.id } });
      // So do the Trakt and Stremio accounts the install had connected.
      await claimLegacyIntegrations(tx, created.id);
      return created;
    });
    if (!account) return reply.code(409).send({ error: "An admin account already exists. Sign in instead." });

    invalidateProfileCache();
    resetTraktClient();
    await adoptLegacyAiConfig();
    const token = await createSession(account.id);
    const verificationSent = await verifyInBackground(request, account);
    return reply.code(201).send({ token, account: publicAccount(account), verificationSent });
  });

  // `username` may be the account's email address instead: a username can't
  // contain "@", so which one was meant is never ambiguous.
  app.post("/auth/login", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const identifier = typeof body.username === "string" ? body.username : "";
    const byEmail = identifier.includes("@");
    const email = byEmail ? normalizeEmail(identifier) : null;
    const name = byEmail ? null : normalizeUsername(identifier);
    // Over-long input is refused before it reaches scrypt.
    const password =
      typeof body.password === "string" && body.password.length <= MAX_PASSWORD_LENGTH ? body.password : "";
    const failed = () => reply.code(401).send({ error: "Incorrect username or password" });

    const account = email
      ? await prisma.account.findUnique({ where: { email } })
      : name
        ? await prisma.account.findUnique({ where: { username: name } })
        : null;
    if (!account) {
      await pinMatches(password || "x", DUMMY_PASSWORD_HASH);
      return failed();
    }
    // Lockouts count against the account, however it was named.
    const username = account.username;

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

  // ─── Email ───

  // Sets, changes or (with `email: null`) removes the account's address. Takes
  // the current password, like changing the password does: whoever controls
  // the address can reset the password, so it is as sensitive as the password.
  app.put("/auth/email", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to change an email address" });
    const body = bodyOf(request);
    const removing = body.email === null || (typeof body.email === "string" && body.email.trim() === "");
    const email = removing ? null : normalizeEmail(body.email);
    if (!removing && !email) return reply.code(400).send({ error: EMAIL_ERROR });

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
    await clearAttempts(loginAttemptKey(account.username));

    if (email === account.email) {
      return { account: publicAccount(account), verificationSent: false };
    }

    let updated: AccountRow;
    try {
      updated = await prisma.account.update({
        where: { id: account.id },
        data: {
          email,
          // A new address hasn't been confirmed, and an old link or reset must
          // not confirm it or act on it.
          emailVerifiedAt: null,
          emailVerifyTokenHash: null,
          emailVerifyExpiresAt: null,
          passwordResetTokenHash: null,
          passwordResetExpiresAt: null,
          ...(email ? {} : { emailNotifications: false }),
        },
      });
    } catch (error) {
      if (isUniqueViolation(error)) return reply.code(409).send(EMAIL_TAKEN);
      throw error;
    }
    const verificationSent = await verifyInBackground(request, updated);
    return { account: publicAccount(updated), verificationSent };
  });

  // Sends the confirmation link again.
  app.post("/auth/email/resend", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to confirm an email address" });
    if (!isAccountEmailAvailable()) {
      return reply.code(409).send({ error: "This server isn't set up to send email", code: "email_unavailable" });
    }
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account?.email) return reply.code(400).send({ error: "Add an email address first" });
    if (account.emailVerifiedAt) return reply.code(204).send();
    try {
      await sendEmailVerification({ id: account.id, username: account.username, email: account.email });
    } catch (error) {
      request.log.warn({ err: error }, "could not send the email confirmation");
      return reply.code(502).send({ error: "The mail server didn't accept the message. Try again later." });
    }
    return reply.code(204).send();
  });

  // Public: opened from the mailed link, perhaps on a device that isn't signed in.
  app.post("/auth/email/verify", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const token = typeof body.token === "string" ? body.token.trim() : "";
    const invalid = { error: "This confirmation link is invalid or has expired", code: "link_invalid" };
    if (!token) return reply.code(400).send(invalid);
    const confirmed = await prisma.account.updateMany({
      where: { emailVerifyTokenHash: hashSecret(token), emailVerifyExpiresAt: { gt: new Date() }, email: { not: null } },
      data: { emailVerifiedAt: new Date(), emailVerifyTokenHash: null, emailVerifyExpiresAt: null },
    });
    if (confirmed.count !== 1) return reply.code(400).send(invalid);
    return reply.code(204).send();
  });

  // Turns email notifications (new episodes, and anything else sent to a
  // profile's notification targets) on or off for the whole account.
  app.put("/auth/email/notifications", async (request, reply) => {
    if (request.auth?.kind !== "session") return reply.code(400).send({ error: "Sign in to change this" });
    const body = bodyOf(request);
    if (typeof body.enabled !== "boolean") return reply.code(400).send({ error: "enabled must be true or false" });
    const account = await prisma.account.findUnique({ where: { id: request.auth.accountId } });
    if (!account) return reply.code(404).send({ error: "Account not found" });
    if (body.enabled && (!account.email || !account.emailVerifiedAt)) {
      return reply.code(400).send({ error: "Confirm an email address first", code: "email_unverified" });
    }
    if (body.enabled && !isMailConfigured()) {
      return reply.code(409).send({ error: "This server isn't set up to send email", code: "email_unavailable" });
    }
    const updated = await prisma.account.update({
      where: { id: account.id },
      data: { emailNotifications: body.enabled },
    });
    return { account: publicAccount(updated) };
  });

  // Public. Always answers the same way, whether or not the address belongs to
  // anyone, and sends after answering, so neither the reply nor its timing
  // says which addresses have accounts. Only a confirmed address gets a reset:
  // an unconfirmed one may have been typed wrong, and is someone else's inbox.
  app.post("/auth/password/forgot", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const email = normalizeEmail(bodyOf(request).email);
    if (!email) return reply.code(400).send({ error: EMAIL_ERROR });
    if (!isAccountEmailAvailable()) {
      return reply.code(409).send({
        error: "This server isn't set up to send email. Ask whoever runs it to reset your password.",
        code: "email_unavailable",
      });
    }
    void (async () => {
      const account = await prisma.account.findUnique({ where: { email } });
      if (!account?.email || !account.emailVerifiedAt) return;
      await sendPasswordReset({ id: account.id, username: account.username, email: account.email });
    })().catch((error: unknown) => request.log.warn({ err: error }, "could not send a password reset"));
    return reply.code(202).send({ ok: true });
  });

  // Public. Sets a new password from a mailed link, signs out every device,
  // and signs this one in.
  app.post("/auth/password/reset", CREDENTIAL_RATE_LIMIT, async (request, reply) => {
    const body = bodyOf(request);
    const token = typeof body.token === "string" ? body.token.trim() : "";
    if (!isValidPassword(body.password)) return reply.code(400).send({ error: PASSWORD_ERROR });
    const invalid = { error: "This reset link is invalid, expired or already used", code: "link_invalid" };
    if (!token) return reply.code(400).send(invalid);

    const tokenHash = hashSecret(token);
    const account = await prisma.account.findUnique({ where: { passwordResetTokenHash: tokenHash } });
    if (!account || !account.passwordResetExpiresAt || account.passwordResetExpiresAt.getTime() <= Date.now()) {
      return reply.code(400).send(invalid);
    }

    const passwordHash = await hashPin(body.password);
    const updated = await prisma.$transaction(async (tx) => {
      // Claimed with a condition, so one link can't be used twice at once.
      const claimed = await tx.account.updateMany({
        where: { id: account.id, passwordResetTokenHash: tokenHash, passwordResetExpiresAt: { gt: new Date() } },
        data: { passwordHash, passwordResetTokenHash: null, passwordResetExpiresAt: null },
      });
      if (claimed.count !== 1) return null;
      await tx.session.deleteMany({ where: { accountId: account.id } });
      return tx.account.findUnique({ where: { id: account.id } });
    });
    if (!updated) return reply.code(400).send(invalid);

    await clearAttempts(loginAttemptKey(updated.username));
    const sessionToken = await createSession(updated.id);
    return { token: sessionToken, account: publicAccount(updated) };
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

    // Optional: who to email the link to.
    const email = optionalEmail(body.email);
    if (email === null) return reply.code(400).send({ error: EMAIL_ERROR });
    if (email && !isAccountEmailAvailable()) {
      return reply.code(409).send({ error: "This server isn't set up to send email", code: "email_unavailable" });
    }

    const code = mintInviteCode();
    const createdById = request.auth?.kind === "session" ? request.auth.accountId : await findAdminAccountId();
    const invite = await prisma.invite.create({
      data: { codeHash: hashSecret(code), expiresAt: new Date(Date.now() + days * DAY_MS), createdById, email: email ?? null },
    });
    const url = WEB_PUBLIC_BASE ? `${WEB_PUBLIC_BASE}/invite/${code}` : webLink(`/invite/${code}`);

    // The invite exists either way; a mail that didn't go out leaves the link
    // to copy by hand, which is what the response is for.
    let emailed = false;
    if (email && url) {
      const inviter = createdById
        ? await prisma.account.findUnique({ where: { id: createdById }, select: { username: true } })
        : null;
      try {
        await sendInviteEmail(email, url, invite.expiresAt, inviter?.username ?? null);
        emailed = true;
      } catch (error) {
        request.log.warn({ err: error }, "could not email an invite");
      }
    }
    return reply.code(201).send({
      invite: { id: invite.id, code, expiresAt: invite.expiresAt.toISOString(), email: invite.email },
      url,
      emailed,
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
        email: invite.email,
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
    const email = optionalEmail(body.email);
    if (email === null) return reply.code(400).send({ error: EMAIL_ERROR });

    const invalidInvite = { error: "This invite link is invalid, expired or already used", code: "invite_invalid" };
    const invite = await prisma.invite.findUnique({ where: { codeHash: hashSecret(code) } });
    if (!invite || invite.usedAt || invite.expiresAt.getTime() <= Date.now()) {
      return reply.code(400).send(invalidInvite);
    }

    if (email && (await prisma.account.findUnique({ where: { email }, select: { id: true } }))) {
      return reply.code(409).send(EMAIL_TAKEN);
    }

    const passwordHash = await hashPin(body.password);
    let account: AccountRow | null;
    try {
      account = await prisma.$transaction(async (tx) => {
        const created = await tx.account.create({
          data: {
            username,
            passwordHash,
            email: email ?? null,
            // The invite was mailed to this address and opened from it, so
            // it is already confirmed.
            emailVerifiedAt: email && invite.email === email ? new Date() : null,
          },
        });
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
      if (isUniqueViolation(error)) {
        // Which column clashed is in `meta`, shaped differently by driver
        // adapters than by the classic engine, so it is searched as text.
        const meta = JSON.stringify((error as { meta?: unknown }).meta ?? {});
        if (email && meta.includes("email")) return reply.code(409).send(EMAIL_TAKEN);
        return reply.code(409).send({ error: "That username is taken" });
      }
      throw error;
    }

    invalidateProfileCache();
    const token = await createSession(account.id);
    const verificationSent = await verifyInBackground(request, account);
    return reply.code(201).send({ token, account: publicAccount(account), verificationSent });
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

    const profileIds = (await prisma.profile.findMany({ where: { accountId: account.id }, select: { id: true } })).map(
      (profile) => profile.id
    );
    await prisma.$transaction(async (tx) => {
      // Kept in KV, keyed by id rather than tied to the rows by a foreign key.
      await deleteOwnerIntegrations(tx, account.id);
      await deleteProfileConnectionState(tx, profileIds);
      await tx.account.delete({ where: { id: account.id } });
    });
    for (const profileId of profileIds) resetTraktClient(profileId);
    invalidateProfileCache();
    // Its TVs went with it; their tokens must stop working now, not when the
    // lookup cache next expires.
    invalidateDeviceCache();
    return reply.code(204).send();
  });
};

class InviteAlreadyUsed extends Error {}

export default authRoutes;
