import type { Prisma } from "@prisma/client";
import type { FastifyInstance, FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import { resolveKidsRulesWrite } from "../lib/kids-rules.js";
import { accountScope } from "../lib/request-auth.js";
import { findAdminAccountId } from "../lib/sessions.js";
import { prisma } from "../lib/prisma.js";
import { writeProfileGuarded } from "../lib/parental-write.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { mintProfileToken, PROFILE_TOKEN_TTL_MS } from "../lib/profile-token.js";
import { clearAuthCookies, isWebClient, PROFILE_COOKIE, setProfileCookie } from "../lib/session-cookie.js";
import {
  hasParentalAuthority,
  hasValidProfileToken,
  invalidateProfileCache,
  isActingAsProfile,
  mayEditProfile,
  loosensParentalLimits,
  ownsProfile,
  PARENTAL_AUTHORITY_RESPONSE,
} from "../lib/profile.js";
import { hashPin, isValidPinFormat, needsRehash, PIN_FORMAT_ERROR, pinMatches } from "../lib/pin-hash.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { parseProfileAttributes, PROFILE_VIEW_SELECT, toProfileView } from "../lib/profile-view.js";
import { deleteProfileConnectionState } from "../lib/profile-connections.js";
import { resetTraktClient } from "../lib/trakt-client.js";
import { recordAccountEvent } from "../lib/account-events.js";
import { clearProfilePin } from "../lib/account-admin.js";

const pinAttemptKey = (profileId: string) => `pinattempts:${profileId}`;
const getPinLockout = (profileId: string) => getLockout(pinAttemptKey(profileId));
const recordPinFailure = (profileId: string) => recordFailure(pinAttemptKey(profileId));
const clearPinAttempts = (profileId: string) => clearAttempts(pinAttemptKey(profileId));

/**
 * The answer to a successful verify. A TV (or any bearer client) gets the
 * profile token in the body and sends it back as `x-profile-token`. The website
 * gets it as an HttpOnly cookie instead and never sees it, which keeps it out of
 * reach of any script on the page — the same reason the session moved there.
 */
const verifiedResponse = (
  request: FastifyRequest,
  reply: FastifyReply,
  profile: { id: string; name: string },
  // The PIN hash as stored now, which the token is bound to.
  pinHash: string | null
) => {
  const profileToken = mintProfileToken(profile.id, pinHash);
  if (isWebClient(request)) {
    setProfileCookie(request, reply, profileToken, PROFILE_TOKEN_TTL_MS / 1000);
    return reply.code(200).send({ id: profile.id, name: profile.name });
  }
  return reply.code(200).send({ id: profile.id, name: profile.name, profileToken });
};

/**
 * Re-hashes a PIN that just verified against a pre-scrypt (unsalted SHA-256)
 * hash, so existing PINs move to the salted KDF the first time they're used
 * instead of every household having to set theirs again. Best-effort: a failed
 * write must not turn a correct PIN into a rejected one.
 *
 * Resolves to the hash now stored, which is what a profile token has to be
 * minted under.
 */
const upgradePinHashIfNeeded = async (
  app: FastifyInstance,
  profileId: string,
  pin: string,
  pinHash: string
): Promise<string> => {
  if (!needsRehash(pinHash)) return pinHash;
  try {
    const upgraded = await hashPin(pin);
    await prisma.profile.update({ where: { id: profileId }, data: { pinHash: upgraded } });
    invalidateProfileCache();
    return upgraded;
  } catch (error) {
    app.log.warn({ err: error, profileId }, "Failed to upgrade a legacy PIN hash");
    return pinHash;
  }
};

/**
 * A PIN-protected profile that is the *subject* of the request, not the one the
 * caller is in, wants its PIN or token. Deliberately not
 * `profile_verification_required`: the web client reads that code as "your
 * active profile's token lapsed" and drops the active profile, which would
 * throw someone out of their own profile for looking at another one.
 */
const TARGET_PROFILE_LOCKED_RESPONSE = {
  error: "Enter this profile's PIN to change or inspect it",
  code: "profile_pin_required",
} as const;

const lockedOutBody = async (profileId: string) => ({
  error: "Too many incorrect attempts. Try again later.",
  retryAfterSec: (await getPinLockout(profileId)).retryAfterSec,
});

/**
 * Checks a `currentPin` from a request body against a profile's PIN, counting a
 * wrong one towards the lockout. "missing" is no PIN sent at all, which is not
 * counted: the caller may not have known one was needed.
 */
const verifyCurrentPin = async (
  profileId: string,
  pinHash: string,
  raw: unknown
): Promise<"ok" | "missing" | "wrong" | "locked_out"> => {
  const currentPin = typeof raw === "string" ? raw.trim() : "";
  if (!currentPin) return "missing";
  if ((await getPinLockout(profileId)).locked) return "locked_out";
  if (!(await pinMatches(currentPin, pinHash))) {
    await recordPinFailure(profileId);
    return "wrong";
  }
  return "ok";
};

/**
 * The profile a route was asked about, or null when it doesn't exist or belongs
 * to another account. Callers answer both with the same 404.
 */
const findOwnedProfileRow = async (request: FastifyRequest, id: string) => {
  const profile = await prisma.profile.findUnique({ where: { id } });
  return profile && ownsProfile(request, profile) ? profile : null;
};

const profilesRoutes: FastifyPluginAsync = async (app) => {
  app.get("/profiles", async (request) => {
    const scope = accountScope(request);
    const profiles = await prisma.profile.findMany({
      where: scope ? { accountId: scope } : {},
      orderBy: { createdAt: "asc" },
      select: PROFILE_VIEW_SELECT,
    });

    return { profiles: profiles.map(toProfileView) };
  });

  app.post<{ Body: unknown }>("/profiles", async (request, reply) => {
    if (!request.body || typeof request.body !== "object") {
      return reply.code(400).send({ error: "name is required" });
    }

    const body = request.body as { name?: unknown; pin?: unknown };
    if (typeof body.name !== "string" || !body.name.trim()) {
      return reply.code(400).send({ error: "name is required" });
    }

    if (body.pin !== undefined && (typeof body.pin !== "string" || !body.pin.trim())) {
      return reply.code(400).send({ error: "pin must be a non-empty string when provided" });
    }

    const name = body.name.trim();
    const pin = typeof body.pin === "string" ? body.pin.trim() : undefined;
    if (pin !== undefined && !isValidPinFormat(pin)) {
      return reply.code(400).send({ error: PIN_FORMAT_ERROR });
    }
    const attributes = parseProfileAttributes(body);
    if (!attributes.ok) return reply.code(400).send({ error: attributes.error });
    // A kids profile adding an unrestricted profile would step around its own
    // limits, so adding one takes the same authority as loosening them.
    if (!(await hasParentalAuthority(request))) {
      return reply.code(403).send(PARENTAL_AUTHORITY_RESPONSE);
    }
    const kidsRules = attributes.kidsRules ? resolveKidsRulesWrite(null, attributes.kidsRules) : null;
    if (kidsRules && !kidsRules.ok) return reply.code(400).send({ error: kidsRules.error });
    const pinHash = pin ? await hashPin(pin) : null;

    // A profile made with API_TOKEN goes to the admin, who is the operator.
    const accountId = accountScope(request) ?? (await findAdminAccountId());
    const profile = await prisma.profile.create({
      data: { name, pinHash, accountId, ...attributes.data, ...(kidsRules?.ok ? kidsRules.data : {}) },
      select: PROFILE_VIEW_SELECT,
    });
    invalidateProfileCache();

    return reply.code(201).send({ profile: toProfileView(profile) });
  });

  // Locks the profile in use on this browser: expires the website's profile
  // cookie, so the PIN is needed again. The page can't do that itself — the
  // cookie is HttpOnly. A TV forgets its own `x-profile-token` instead.
  app.post("/profiles/lock", async (request, reply) => {
    clearAuthCookies(request, reply, [PROFILE_COOKIE]);
    return reply.code(204).send();
  });

  app.post<{ Params: { id: string }; Body: unknown }>(
    "/profiles/:id/verify",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) {
        return reply.code(400).send({ error: "id must be a valid UUID" });
      }

      const profile = await findOwnedProfileRow(request, request.params.id);
      if (!profile) return reply.code(404).send({ error: "Profile not found" });

      if (!profile.pinHash) return verifiedResponse(request, reply, profile, null);

      const lockout = await getPinLockout(profile.id);
      if (lockout.locked) {
        return reply
          .code(429)
          .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
      }

      const body = (request.body ?? {}) as { pin?: unknown };
      const pin = typeof body.pin === "string" ? body.pin.trim() : "";
      if (!pin || !(await pinMatches(pin, profile.pinHash))) {
        await recordPinFailure(profile.id);
        return reply.code(401).send({ error: "Incorrect PIN" });
      }

      const storedPinHash = await upgradePinHashIfNeeded(app, profile.id, pin, profile.pinHash);
      await clearPinAttempts(profile.id);
      return verifiedResponse(request, reply, profile, storedPinHash);
    },
  );

  app.patch<{ Params: { id: string }; Body: unknown }>("/profiles/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }

    const profile = await findOwnedProfileRow(request, request.params.id);
    if (!profile) return reply.code(404).send({ error: "Profile not found" });

    const body = (request.body ?? {}) as { name?: unknown; pin?: unknown; currentPin?: unknown };
    const attributes = parseProfileAttributes(body);
    if (!attributes.ok) return reply.code(400).send({ error: attributes.error });
    const data: { name?: string; pinHash?: string | null } & typeof attributes.data &
      Partial<Pick<Prisma.ProfileUpdateInput, "kidsRules" | "kidsRulesUpdatedAt">> = { ...attributes.data };

    if (body.name !== undefined) {
      if (typeof body.name !== "string" || !body.name.trim()) {
        return reply.code(400).send({ error: "name must be a non-empty string when provided" });
      }
      data.name = body.name.trim();
    }

    // Loosening a profile's parental limits is a grown-up's call, wherever the
    // request comes from: a kids profile may tighten its own, never lift them.
    if (loosensParentalLimits(profile, attributes.data) && !(await hasParentalAuthority(request))) {
      return reply.code(403).send(PARENTAL_AUTHORITY_RESPONSE);
    }

    // Kids rules are a grown-up's to set, tighter or looser: a kids profile
    // can't change its own. Of two writes, the one that changed them later
    // wins (lib/kids-rules.ts).
    if (attributes.kidsRules) {
      if (!(await hasParentalAuthority(request))) return reply.code(403).send(PARENTAL_AUTHORITY_RESPONSE);
      const kidsRules = resolveKidsRulesWrite(profile, attributes.kidsRules);
      if (!kidsRules.ok) return reply.code(400).send({ error: kidsRules.error });
      // Null: what is stored changed later than this write, so it stays.
      if (kidsRules.data) Object.assign(data, kidsRules.data);
    }

    // pin: omitted = leave as-is, null = remove PIN protection, string = set/change PIN.
    if (body.pin !== undefined) {
      let nextPin: string | null = null;
      if (body.pin === null) {
        data.pinHash = null;
      } else if (typeof body.pin === "string" && body.pin.trim()) {
        nextPin = body.pin.trim();
        if (!isValidPinFormat(nextPin)) {
          return reply.code(400).send({ error: PIN_FORMAT_ERROR });
        }
      } else {
        return reply.code(400).send({ error: "pin must be a non-empty string or null when provided" });
      }

      // Changing or removing an *existing* PIN requires proving you know it —
      // otherwise PIN protection is meaningless from Settings (anyone with app
      // access could strip it without ever seeing the PIN). Setting a PIN for
      // the first time needs no proof, since there's nothing to bypass yet —
      // but then it has to be *your* profile: a first PIN set on someone else's
      // locks them out of it, and removing it needs the PIN they never chose.
      if (!profile.pinHash && nextPin && !(await isActingAsProfile(request, profile.id))) {
        return reply.code(403).send({
          error: "Switch to this profile to set its PIN",
          code: "profile_not_active",
        });
      }

      if (profile.pinHash) {
        const lockout = await getPinLockout(profile.id);
        if (lockout.locked) {
          return reply
            .code(429)
            .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
        }

        const currentPin = typeof body.currentPin === "string" ? body.currentPin.trim() : "";
        if (!currentPin || !(await pinMatches(currentPin, profile.pinHash))) {
          await recordPinFailure(profile.id);
          return reply.code(401).send({ error: "Incorrect current PIN" });
        }
      }

      // Derived only once the caller has proved the current PIN — scrypt is
      // deliberately expensive, and a rejected request shouldn't buy any of it.
      if (nextPin) data.pinHash = await hashPin(nextPin);
    }

    if (Object.keys(data).length === 0) {
      if (attributes.kidsRules) {
        // Only kids rules older than the stored ones: answer with what is kept.
        const current = await prisma.profile.findUnique({ where: { id: profile.id }, select: PROFILE_VIEW_SELECT });
        if (current) return { profile: toProfileView(current) };
      }
      return reply.code(400).send({ error: "Nothing to update" });
    }

    // Any other change to a PIN-protected profile — its name, picture or
    // parental fields — needs the same proof that opening it does: its profile
    // token, or its PIN. (A PIN change has just checked `currentPin` above.)
    if (profile.pinHash && body.pin === undefined && !mayEditProfile(request, profile)) {
      const proof = await verifyCurrentPin(profile.id, profile.pinHash, body.currentPin);
      if (proof === "locked_out") return reply.code(429).send(await lockedOutBody(profile.id));
      if (proof === "missing") return reply.code(401).send(TARGET_PROFILE_LOCKED_RESPONSE);
      if (proof === "wrong") return reply.code(401).send({ error: "Incorrect current PIN" });
    }

    // The parental check above and this write are one step: a parental field
    // only lands if the limits are still the ones it was checked against, so
    // a grown-up tightening them meanwhile can't be undone by this request.
    const written = await writeProfileGuarded(profile.id, data, {
      checkAuthority: () => hasParentalAuthority(request),
      strict: true,
      initial: profile,
    });
    if (written.status === "missing") return reply.code(404).send({ error: "Profile not found" });
    if (written.status === "forbidden") return reply.code(403).send(PARENTAL_AUTHORITY_RESPONSE);
    if (written.status === "conflict") {
      return reply
        .code(409)
        .send({ error: "This profile's parental limits changed while saving. Try again.", code: "parental_limits_changed" });
    }
    // Strict mode writes all of `data` or nothing, and `data` isn't empty.
    const updated = written.profile!;
    invalidateProfileCache();

    // The PIN just changed (or was removed) — any outstanding lockout/attempt
    // count from the old PIN no longer applies.
    if (data.pinHash !== undefined) {
      await clearPinAttempts(profile.id);
      if (profile.accountId) {
        await recordAccountEvent(profile.accountId, data.pinHash === null ? "pin_removed" : "pin_changed", {
          request,
          detail: updated.name,
        });
      }
    }

    return { profile: toProfileView(updated) };
  });

  // A forgotten PIN. The admin clears it on a profile in their own account
  // after entering the account password again; anyone else, or a profile in
  // another account, is turned away. The operator's equivalent is
  // `fetch clear-pin` on the server (src/cli.ts).
  app.post<{ Params: { id: string }; Body: unknown }>(
    "/profiles/:id/clear-pin",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) {
        return reply.code(400).send({ error: "id must be a valid UUID" });
      }
      const auth = request.auth;
      if (auth?.kind !== "session" || !auth.isAdmin) {
        return reply.code(403).send({ error: "Only the admin can clear a forgotten PIN", code: "admin_required" });
      }
      const profile = await findOwnedProfileRow(request, request.params.id);
      if (!profile) return reply.code(404).send({ error: "Profile not found" });
      if (!profile.pinHash) return reply.code(400).send({ error: "This profile has no PIN" });

      const account = await prisma.account.findUnique({ where: { id: auth.accountId } });
      if (!account) return reply.code(404).send({ error: "Account not found" });
      // The same lockout as signing in from this address, so this is no
      // faster a way to guess the password.
      const attemptKey = `loginattempts:${account.username}:${request.ip}`;
      const lockout = await getLockout(attemptKey);
      if (lockout.locked) {
        return reply
          .code(429)
          .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
      }
      const body = (request.body ?? {}) as { password?: unknown };
      const password = typeof body.password === "string" && body.password.length <= 256 ? body.password : "";
      if (!password || !(await pinMatches(password, account.passwordHash))) {
        await recordFailure(attemptKey);
        return reply.code(401).send({ error: "Incorrect password", code: "password_incorrect" });
      }
      await clearAttempts(attemptKey);

      await clearProfilePin(profile.id);
      await recordAccountEvent(account.id, "pin_cleared", { request, detail: profile.name });
      const updated = await prisma.profile.findUniqueOrThrow({ where: { id: profile.id }, select: PROFILE_VIEW_SELECT });
      return { profile: toProfileView(updated) };
    }
  );

  // What deleting a profile takes with it, so the confirmation can say so in
  // numbers rather than "all of its data".
  app.get<{ Params: { id: string } }>("/profiles/:id/summary", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const profileId = request.params.id;
    const profile = await findOwnedProfileRow(request, profileId);
    if (!profile) return reply.code(404).send({ error: "Profile not found" });
    // What a locked profile holds is its own business until it is unlocked.
    if (profile.pinHash && !hasValidProfileToken(request, profile)) {
      return reply.code(401).send(TARGET_PROFILE_LOCKED_RESPONSE);
    }

    const [watchEvents, lists, listItems, playbackProgress, ratings, collections] = await Promise.all([
      prisma.watchEvent.count({ where: { profileId } }),
      prisma.list.count({ where: { profileId } }),
      prisma.listItem.count({ where: { list: { profileId } } }),
      prisma.playbackProgress.count({ where: { profileId } }),
      prisma.rating.count({ where: { profileId } }),
      prisma.profileCollections.findUnique({ where: { profileId }, select: { collections: true } }),
    ]);

    return {
      watchEvents,
      lists,
      listItems,
      playbackProgress,
      ratings,
      collections: Array.isArray(collections?.collections) ? collections.collections.length : 0,
    };
  });

  app.delete<{ Params: { id: string }; Body: unknown }>("/profiles/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }

    const profile = await findOwnedProfileRow(request, request.params.id);
    if (!profile) return reply.code(404).send({ error: "Profile not found" });

    // An account always keeps at least one profile, so it always has somewhere
    // to land after signing in.
    const profileCount = await prisma.profile.count({ where: { accountId: profile.accountId } });
    if (profileCount === 1) {
      return reply.code(400).send({ error: "Cannot delete the last profile" });
    }

    // Deleting a locked profile destroys everything it owns — every child model
    // cascades — so it needs at least the proof-of-PIN that changing that PIN
    // requires. Otherwise the household member the PIN is meant to keep out
    // could still wipe the profile, PIN and all, with the shared API token.
    //
    // An `x-profile-token` minted for *this* profile already proves the caller
    // cleared the PIN gate, so deleting the profile you are currently in does
    // not re-prompt; deleting someone else's locked profile needs its PIN.
    if (profile.pinHash && !hasValidProfileToken(request, profile)) {
      const lockout = await getPinLockout(profile.id);
      if (lockout.locked) {
        return reply
          .code(429)
          .send({ error: "Too many incorrect attempts. Try again later.", retryAfterSec: lockout.retryAfterSec });
      }

      const body = (request.body ?? {}) as { currentPin?: unknown };
      const currentPin = typeof body.currentPin === "string" ? body.currentPin.trim() : "";
      if (!currentPin || !(await pinMatches(currentPin, profile.pinHash))) {
        await recordPinFailure(profile.id);
        return reply.code(401).send({ error: "Incorrect current PIN" });
      }
    }

    // Its connections cascade; their sync state in KV is removed beside it.
    await prisma.$transaction(async (tx) => {
      await deleteProfileConnectionState(tx, [profile.id]);
      await tx.profile.delete({ where: { id: profile.id } });
    });
    resetTraktClient(profile.id);
    invalidateProfileCache();

    // Clean up any PIN attempt records for the deleted profile
    await clearPinAttempts(profile.id);

    return reply.code(204).send();
  });
};

export default profilesRoutes;
