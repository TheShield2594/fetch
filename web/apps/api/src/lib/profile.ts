import type { FastifyReply, FastifyRequest } from "fastify";
import { matchesStremioProfileKey, STREMIO_PROFILE_KEY_HEADER } from "@fetch/shared";
import { accountScope } from "./request-auth.js";
import { findAdminAccountId } from "./sessions.js";
import { prisma } from "./prisma.js";
import { type CachedProfile, profileCache, profileCacheClear, profileLookups } from "./cache.js";
import { verifyProfileToken } from "./profile-token.js";
import { PROFILE_COOKIE, readCookie } from "./session-cookie.js";
import { isServiceRequest } from "./service-request.js";
import { UUID_V4_PATTERN } from "./types.js";
import { RATING_LEVELS } from "./profile-view.js";

declare module "fastify" {
  interface FastifyRequest {
    profileId?: string;
  }
}

/** Exported for the one route that reads it without going through `resolveProfile`. */
export const PROFILE_HEADER = "x-profile-id";
const PROFILE_TOKEN_HEADER = "x-profile-token";

/**
 * Resolves the active profile for a request from the `x-profile-id` header.
 *
 * If the header is absent and there is exactly one profile in the system,
 * that profile is used automatically — this keeps a brand-new single-profile
 * install working with zero frontend changes. Otherwise, when multiple
 * profiles exist, the header is required.
 */
// Used by surfaces that have no concept of multiple profiles (the Stremio
// addon protocol, scheduled background jobs) to pick a sensible default —
// the oldest profile, which is the seeded "Default" profile on existing
// single-tenant installs.
//
// Given an account, the default is that account's oldest profile. Without one
// it is the admin account's: the scheduled jobs and webhooks act for the
// install, and the admin is who set those integrations up. Before any account
// exists that is simply the oldest profile.
export const getDefaultProfileId = async (accountId?: string | null): Promise<string> => {
  const ownerId = accountId ?? (await findAdminAccountId());
  const profile = await prisma.profile.findFirst({
    where: ownerId ? { accountId: ownerId } : {},
    orderBy: { createdAt: "asc" },
  });
  if (profile) return profile.id;

  const created = await prisma.profile.create({ data: { name: "Default", accountId: ownerId } });
  invalidateProfileCache();
  return created.id;
};

/**
 * Whether this request may use `profile` at all. A signed-in account sees only
 * its own profiles; `API_TOKEN` sees every one.
 */
export const ownsProfile = (request: FastifyRequest, profile: { accountId: string | null }): boolean => {
  const scope = accountScope(request);
  return scope === null || profile.accountId === scope;
};

// Called by every route that creates, updates or deletes a profile — the cache
// below is a burst collapser, not a source of truth, so it is thrown away
// wholesale rather than surgically patched.
export const invalidateProfileCache = profileCacheClear;

// The "no x-profile-id header" probe is cached under its own key; the payload is
// the same shape as a by-ID hit so both paths share one cache. Every other key
// is a profile UUID, which cannot contain a colon, so the two spaces cannot
// collide. (It was a NUL-prefixed string, which made git treat this whole file
// as binary and print no diff for it.)
const PROFILE_PROBE_KEY = "probe:no-profile-header";

// Read-through with single-flight: a cache hit returns immediately, a miss with a
// lookup already in flight joins that lookup, and only the first caller of a cold
// key actually queries. `load` results are cached only when non-empty, so a miss
// (unknown profile ID) stays uncached — that is a 404 path, not a hot one.
const readThrough = async (
  key: string,
  load: () => Promise<CachedProfile[]>
): Promise<CachedProfile[]> => {
  const cached = profileCache.get(key);
  if (cached) return cached;

  const inFlight = profileLookups.get(key);
  if (inFlight) return inFlight;

  const lookup = load()
    .then((profiles) => {
      if (profiles.length > 0) profileCache.set(key, profiles);
      return profiles;
    })
    .finally(() => {
      profileLookups.delete(key);
    });

  profileLookups.set(key, lookup);
  return lookup;
};

const findProfileById = async (id: string): Promise<CachedProfile | null> => {
  const profiles = await readThrough(id, async () => {
    const profile = await prisma.profile.findUnique({
      where: { id },
      select: { id: true, pinHash: true, accountId: true },
    });
    return profile ? [profile] : [];
  });
  return profiles[0] ?? null;
};

// Per account, so "the only profile" means the only one this caller can see.
const findProfileCandidates = async (request: FastifyRequest): Promise<CachedProfile[]> => {
  const scope = accountScope(request);
  return readThrough(`${PROFILE_PROBE_KEY}:${scope ?? "all"}`, () =>
    prisma.profile.findMany({
      where: scope ? { accountId: scope } : {},
      select: { id: true, pinHash: true, accountId: true },
      take: 2,
    })
  );
};

/** The profile with this id, if it exists and this request may use it. */
export const findOwnedProfile = async (
  request: FastifyRequest,
  profileId: string
): Promise<CachedProfile | null> => {
  const profile = await findProfileById(profileId);
  return profile && ownsProfile(request, profile) ? profile : null;
};

// A PIN-protected profile requires a valid, unexpired profile-access token
// (minted by POST /profiles/:id/verify on correct PIN). This is what makes the
// PIN a real server-side boundary rather than a client-side prompt: a token
// holder can't reach a locked profile's data just by naming its UUID.
//
// Takes the profile row rather than its id: a token is only good for the PIN
// it was minted under, so the check needs the profile's current `pinHash`.
//
// A TV sends it as the `x-profile-token` header; the website holds it in an
// HttpOnly cookie it cannot read (lib/session-cookie.ts). The header wins.
export const hasValidProfileToken = (
  request: FastifyRequest,
  profile: { id: string; pinHash: string | null }
): boolean => {
  const headerValue = request.headers[PROFILE_TOKEN_HEADER];
  const token = (Array.isArray(headerValue) ? headerValue[0] : headerValue) || readCookie(request, PROFILE_COOKIE);
  return verifyProfileToken(token ?? undefined, profile.id, profile.pinHash);
};

/**
 * Whether this is the addon service acting for `profileId` on behalf of a
 * *signed* Stremio install URL (`/u/<profileId>/<key>/…`).
 *
 * Stremio cannot unlock a PIN, so a PIN-protected profile's install had no way
 * to reach its own lists: every catalog came back empty and the manifest 502'd.
 * But the signed URL is already a full credential for that profile — the key
 * is an HMAC of the profile id under `API_TOKEN`, and the only place to get it
 * is Settings while signed in as the profile, past its PIN. The API's own
 * `/addon/stremio/<key>` routes already treat it that way.
 *
 * Both halves are required: the service token proves the request is the addon
 * service (not merely an `API_TOKEN` holder), and the key proves the request it
 * is serving came in on that profile's signed URL. An unsigned `/p/<uuid>` URL
 * carries no key, so it still stops at the PIN.
 */
export const isSignedAddonRequestFor = (request: FastifyRequest, profileId: string): boolean => {
  if (!isServiceRequest(request)) return false;
  const headerValue = request.headers[STREMIO_PROFILE_KEY_HEADER];
  const key = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  return matchesStremioProfileKey(key, process.env.API_TOKEN, profileId);
};

export const PROFILE_LOCKED_RESPONSE = {
  error: "Profile PIN verification required",
  code: "profile_verification_required",
} as const;

/**
 * Whether `profileId` is PIN-protected and this request hasn't verified it.
 *
 * `resolveProfile` applies this to the `x-profile-id` header path. Surfaces that
 * choose a profile some other way have to apply it themselves — the Stremio
 * routes select one from a `?profileId=` query parameter, and fall back to the
 * default profile when it is absent. Neither goes through `resolveProfile`, so
 * without this the PIN stopped being a boundary the moment a caller stopped
 * using the header.
 */
export const isProfileLocked = async (
  request: FastifyRequest,
  profileId: string
): Promise<boolean> => {
  const profile = await findProfileById(profileId);
  return profile !== null && Boolean(profile.pinHash) && !hasValidProfileToken(request, profile);
};

/**
 * Whether the request is acting *as* `profileId` — the same selection
 * `resolveProfile` makes, without the replies.
 *
 * Used where a route may act on any profile but one particular change is only
 * yours to make: setting a first PIN takes no proof (there is nothing yet to
 * prove), so without this any holder of the shared token could PIN-lock a
 * household member out of their own profile, and removing it would then need
 * the PIN they never chose.
 *
 * The header path also needs a profile token minted for `profileId`: naming a
 * profile in `x-profile-id` is something any caller can type, and a token is
 * what switching to a profile hands out. For a profile with no PIN that token
 * is free to anyone signed in to the account (there is no secret to tell
 * household members apart), so this stops a stray request, not a member who
 * deliberately switches to the profile first.
 */
export const isActingAsProfile = async (
  request: FastifyRequest,
  profileId: string
): Promise<boolean> => {
  const headerValue = request.headers[PROFILE_HEADER];
  const rawProfileId = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  if (rawProfileId) {
    if (rawProfileId !== profileId) return false;
    const profile = await findProfileById(profileId);
    return profile !== null && hasValidProfileToken(request, profile);
  }

  // No header is the single-profile install, where the one profile is implied.
  const profiles = await findProfileCandidates(request);
  const [only, ...rest] = profiles;
  return rest.length === 0 && only?.id === profileId;
};

/** The parental fields a change is judged on. */
export type ParentalLimits = { isKidsProfile: boolean; maxRating: string | null; allowUnrated: boolean };

const ratingRank = (rating: string | null): number => {
  // No limit is looser than the loosest named one.
  if (rating === null) return RATING_LEVELS.length;
  const index = (RATING_LEVELS as readonly string[]).indexOf(rating);
  return index === -1 ? RATING_LEVELS.length : index;
};

/**
 * Whether going from `current` to `current` with `next` applied lifts any
 * limit: kids mode off, a higher (or no) rating cap, or unrated titles let in.
 * Tightening is never a loosening, so a kids profile may always make itself
 * stricter.
 */
export const loosensParentalLimits = (current: ParentalLimits, next: Partial<ParentalLimits>): boolean =>
  (current.isKidsProfile && next.isKidsProfile === false) ||
  (next.maxRating !== undefined && ratingRank(next.maxRating) > ratingRank(current.maxRating)) ||
  (!current.allowUnrated && next.allowUnrated === true);

const PARENTAL_KEYS = ["isKidsProfile", "maxRating", "allowUnrated"] as const;

/**
 * `next` without each parental field that, on its own, would loosen
 * `current`'s limits — field by field, so a tightening sent alongside still
 * applies. For write paths that carry other settings too (a TV's profile
 * push, a backup import) and should keep those while refusing the loosening.
 * Returns the trimmed copy and the keys it left out.
 */
export const withoutLoosenedLimits = <T extends Partial<ParentalLimits>>(
  current: ParentalLimits,
  next: T
): { data: T; dropped: (keyof ParentalLimits)[] } => {
  const data = { ...next };
  const dropped: (keyof ParentalLimits)[] = [];
  for (const key of PARENTAL_KEYS) {
    if (data[key] !== undefined && loosensParentalLimits(current, { [key]: data[key] })) {
      delete data[key];
      dropped.push(key);
    }
  }
  return { data, dropped };
};

/**
 * A second profile token, for a request that acts as one profile and changes
 * another: `x-profile-token` goes with `x-profile-id` (the profile acting, the
 * one parental authority is judged on), so the token of the profile being
 * changed travels here. A TV's profile push is the one caller: a grown-up
 * with a PIN changing another PIN-protected profile needs both.
 */
const TARGET_PROFILE_TOKEN_HEADER = "x-target-profile-token";

/**
 * Whether this request may change a profile's name, picture or parental
 * fields: anyone in the account for an open profile, and only a holder of its
 * profile token (so, someone who entered its PIN) for a PIN-protected one.
 * The token is taken from `x-profile-token`, or from `x-target-profile-token`
 * when the request is acting as a different profile.
 */
export const mayEditProfile = (request: FastifyRequest, profile: { id: string; pinHash: string | null }): boolean => {
  if (!profile.pinHash || hasValidProfileToken(request, profile)) return true;
  const headerValue = request.headers[TARGET_PROFILE_TOKEN_HEADER];
  const token = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  return verifyProfileToken(token, profile.id, profile.pinHash);
};

export const PARENTAL_AUTHORITY_RESPONSE = {
  error: "Switch to a grown-up's profile to loosen parental controls or add a profile",
  code: "parental_authority_required",
} as const;

/**
 * Whether this request may loosen a profile's parental controls or add a
 * profile to the account: the authority a kids profile must not have over
 * itself.
 *
 * - `API_TOKEN` is the operator, and may.
 * - Otherwise the request must be acting as a profile of the account
 *   (`x-profile-id`, or the only profile) that is not a kids profile, and if
 *   that profile has a PIN, with its profile token. A PIN on the grown-ups'
 *   profiles is what makes this a boundary: a child can switch to an unlocked
 *   one anyway.
 * - A request that names no usable profile may only when the account has no
 *   kids profile at all, so there is nothing to protect (a fresh account
 *   adding its second profile, a script).
 */
export const hasParentalAuthority = async (request: FastifyRequest): Promise<boolean> => {
  if (request.auth?.kind === "token") return true;
  const scope = accountScope(request);

  const headerValue = request.headers[PROFILE_HEADER];
  const rawProfileId = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  let actingId: string | null = null;
  if (rawProfileId) {
    if (UUID_V4_PATTERN.test(rawProfileId)) actingId = rawProfileId;
  } else {
    const [only, ...rest] = await findProfileCandidates(request);
    if (only && rest.length === 0) actingId = only.id;
  }

  if (actingId) {
    const acting = await prisma.profile.findUnique({
      where: { id: actingId },
      select: { id: true, accountId: true, pinHash: true, isKidsProfile: true },
    });
    if (acting && ownsProfile(request, acting)) {
      return !acting.isKidsProfile && (!acting.pinHash || hasValidProfileToken(request, acting));
    }
  }

  const kids = await prisma.profile.count({ where: { isKidsProfile: true, ...(scope ? { accountId: scope } : {}) } });
  return kids === 0;
};

export const resolveProfile = async (request: FastifyRequest, reply: FastifyReply) => {
  const headerValue = request.headers[PROFILE_HEADER];
  const rawProfileId = Array.isArray(headerValue) ? headerValue[0] : headerValue;

  if (rawProfileId) {
    if (!UUID_V4_PATTERN.test(rawProfileId)) {
      return reply.code(400).send({ error: "x-profile-id must be a valid UUID" });
    }
    // Another account's profile answers exactly as a missing one does, so a
    // profile id reveals nothing about who it belongs to.
    const profile = await findOwnedProfile(request, rawProfileId);
    if (!profile) {
      return reply.code(404).send({ error: "Profile not found" });
    }
    if (
      profile.pinHash &&
      !hasValidProfileToken(request, profile) &&
      !isSignedAddonRequestFor(request, profile.id)
    ) {
      return reply.code(401).send(PROFILE_LOCKED_RESPONSE);
    }
    request.profileId = rawProfileId;
    return;
  }

  const profiles = await findProfileCandidates(request);
  const [only, ...rest] = profiles;
  if (only && rest.length === 0) {
    if (only.pinHash && !hasValidProfileToken(request, only)) {
      return reply.code(401).send(PROFILE_LOCKED_RESPONSE);
    }
    request.profileId = only.id;
    return;
  }

  return reply.code(400).send({ error: "x-profile-id header is required" });
};

/**
 * `resolveProfile` for routes that name the profile in the path
 * (`/profiles/:id/addons`) rather than in the `x-profile-id` header — the
 * resources a TV syncs, where the profile being edited is the subject of the
 * request. The PIN gate is the same one: a locked profile needs its own
 * profile token, whoever is asking.
 */
export const resolveProfileParam = async (request: FastifyRequest, reply: FastifyReply) => {
  const { id } = request.params as { id?: string };
  if (!id || !UUID_V4_PATTERN.test(id)) {
    return reply.code(400).send({ error: "id must be a valid UUID" });
  }
  const profile = await findOwnedProfile(request, id);
  if (!profile) {
    return reply.code(404).send({ error: "Profile not found" });
  }
  if (profile.pinHash && !hasValidProfileToken(request, profile)) {
    return reply.code(401).send(PROFILE_LOCKED_RESPONSE);
  }
  request.profileId = id;
};
