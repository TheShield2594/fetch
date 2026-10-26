import type { FastifyInstance, FastifyPluginAsync, FastifyRequest } from "fastify";
import { accountScope } from "../lib/request-auth.js";
import { findAdminAccountId } from "../lib/sessions.js";
import { prisma } from "../lib/prisma.js";
import { clearAttempts, getLockout, recordFailure } from "../lib/attempt-lockout.js";
import { mintProfileToken } from "../lib/profile-token.js";
import { hasValidProfileToken, invalidateProfileCache, isActingAsProfile, ownsProfile } from "../lib/profile.js";
import { hashPin, isValidPinFormat, needsRehash, pinLengthError, pinMatches } from "../lib/pin-hash.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { parseProfileAttributes, PROFILE_VIEW_SELECT, toProfileView } from "../lib/profile-view.js";

const pinAttemptKey = (profileId: string) => `pinattempts:${profileId}`;
const getPinLockout = (profileId: string) => getLockout(pinAttemptKey(profileId));
const recordPinFailure = (profileId: string) => recordFailure(pinAttemptKey(profileId));
const clearPinAttempts = (profileId: string) => clearAttempts(pinAttemptKey(profileId));

/**
 * Re-hashes a PIN that just verified against a pre-scrypt (unsalted SHA-256)
 * hash, so existing PINs move to the salted KDF the first time they're used
 * instead of every household having to set theirs again. Best-effort: a failed
 * write must not turn a correct PIN into a rejected one.
 */
const upgradePinHashIfNeeded = async (
  app: FastifyInstance,
  profileId: string,
  pin: string,
  pinHash: string
): Promise<void> => {
  if (!needsRehash(pinHash)) return;
  try {
    await prisma.profile.update({ where: { id: profileId }, data: { pinHash: await hashPin(pin) } });
    invalidateProfileCache();
  } catch (error) {
    app.log.warn({ err: error, profileId }, "Failed to upgrade a legacy PIN hash");
  }
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
      return reply.code(400).send({ error: pinLengthError });
    }
    const attributes = parseProfileAttributes(body);
    if (!attributes.ok) return reply.code(400).send({ error: attributes.error });
    const pinHash = pin ? await hashPin(pin) : null;

    // A profile made with API_TOKEN goes to the admin, who is the operator.
    const accountId = accountScope(request) ?? (await findAdminAccountId());
    const profile = await prisma.profile.create({
      data: { name, pinHash, accountId, ...attributes.data },
      select: PROFILE_VIEW_SELECT,
    });
    invalidateProfileCache();

    return reply.code(201).send({ profile: toProfileView(profile) });
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

      if (!profile.pinHash) {
        return reply
          .code(200)
          .send({ id: profile.id, name: profile.name, profileToken: mintProfileToken(profile.id) });
      }

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

      await upgradePinHashIfNeeded(app, profile.id, pin, profile.pinHash);
      await clearPinAttempts(profile.id);
      return reply
        .code(200)
        .send({ id: profile.id, name: profile.name, profileToken: mintProfileToken(profile.id) });
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
    const data: { name?: string; pinHash?: string | null } & typeof attributes.data = { ...attributes.data };

    if (body.name !== undefined) {
      if (typeof body.name !== "string" || !body.name.trim()) {
        return reply.code(400).send({ error: "name must be a non-empty string when provided" });
      }
      data.name = body.name.trim();
    }

    // pin: omitted = leave as-is, null = remove PIN protection, string = set/change PIN.
    if (body.pin !== undefined) {
      let nextPin: string | null = null;
      if (body.pin === null) {
        data.pinHash = null;
      } else if (typeof body.pin === "string" && body.pin.trim()) {
        nextPin = body.pin.trim();
        if (!isValidPinFormat(nextPin)) {
          return reply.code(400).send({ error: pinLengthError });
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
      return reply.code(400).send({ error: "Nothing to update" });
    }

    const updated = await prisma.profile.update({ where: { id: profile.id }, data, select: PROFILE_VIEW_SELECT });
    invalidateProfileCache();

    // The PIN just changed (or was removed) — any outstanding lockout/attempt
    // count from the old PIN no longer applies.
    if (data.pinHash !== undefined) {
      await clearPinAttempts(profile.id);
    }

    return { profile: toProfileView(updated) };
  });

  // What deleting a profile takes with it, so the confirmation can say so in
  // numbers rather than "all of its data".
  app.get<{ Params: { id: string } }>("/profiles/:id/summary", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const profileId = request.params.id;
    const profile = await findOwnedProfileRow(request, profileId);
    if (!profile) return reply.code(404).send({ error: "Profile not found" });

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
    if (profile.pinHash && !hasValidProfileToken(request, profile.id)) {
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

    await prisma.profile.delete({ where: { id: profile.id } });
    invalidateProfileCache();

    // Clean up any PIN attempt records for the deleted profile
    await clearPinAttempts(profile.id);

    return reply.code(204).send();
  });
};

export default profilesRoutes;
