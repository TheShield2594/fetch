import type { FastifyBaseLogger, FastifyRequest } from "fastify";
import { deriveStremioProfileKey, matchesStremioProfileKey, parseProfilesResponse, UUID_V4_PATTERN } from "@fetch/shared";
import { apiGet } from "./api-client.js";
import { CATALOGGY_API_TOKEN, REQUIRE_SIGNED_URLS } from "./config.js";

/**
 * Which profile a request is acting as.
 *
 * Most API endpoints the addon calls are profile-scoped: the API's
 * `resolveProfile` auto-picks a profile only when exactly one exists and
 * otherwise demands an `x-profile-id` header. Without one, every call the
 * addon makes — reading lists, personal recommendations, series progress, and
 * crucially the /watch and /scrobble writes — starts failing the moment a
 * second profile is created.
 *
 * Stremio has no notion of "who is watching": the installed URL is the only
 * thing that distinguishes one installation from another, and Stremio derives
 * every resource URL from the base of the manifest URL it was installed with.
 * So the profile is bound to an *installation* via a path prefix —
 * /p/<uuid>/manifest.json makes Stremio fetch /p/<uuid>/catalog/…,
 * /p/<uuid>/meta/… and so on. Requests without the prefix (the URL every
 * existing install already uses) fall back to the oldest profile, matching the
 * default the API's own Stremio routes apply via `getDefaultProfileId`.
 */
export const PROFILE_ROUTE_PREFIX = "/p/:profileId";

/**
 * The same, with the profile's Stremio key after the id: /u/<uuid>/<key>/…
 *
 * `/p/<uuid>` names a profile but proves nothing, so anyone who can reach this
 * service could read that profile's lists and record watches as it. The key is
 * an HMAC of the profile id under `API_TOKEN` (`deriveStremioProfileKey`), so it
 * can only have come from someone signed in as that profile — Settings shows
 * it — and is checked here with no lookup. These are the URLs that are safe to
 * put on the internet; `ADDON_REQUIRE_SIGNED_URLS` turns the other two off.
 */
export const SIGNED_PROFILE_ROUTE_PREFIX = "/u/:profileId/:profileKey";

/** The signed prefix for `profileId`, for URLs this service mints. Null with no API token. */
export const signedProfilePath = (profileId: string): string | null =>
  CATALOGGY_API_TOKEN ? `/u/${profileId}/${deriveStremioProfileKey(CATALOGGY_API_TOKEN, profileId)}` : null;

/**
 * `profileId: null` means "no profile bound" — the header is omitted and the
 * API applies its single-profile fallback.
 */
export type ProfileScope = { ok: true; profileId: string | null } | { ok: false };

export const profilePathParam = (request: FastifyRequest): string | undefined => {
  const params = request.params as Record<string, unknown> | null;
  const value = params?.profileId;
  return typeof value === "string" ? value : undefined;
};

// GET /profiles is ordered oldest-first, so profiles[0] is the same profile the
// API's `getDefaultProfileId` would pick. Cached briefly so an un-prefixed
// install doesn't add a round trip to every request.
let cachedDefaultProfileId: { id: string | null; expiry: number } | null = null;
const DEFAULT_PROFILE_CACHE_TTL_MS = 60_000;

const fetchDefaultProfileId = async (logger: FastifyBaseLogger): Promise<string | null> => {
  const now = Date.now();
  if (cachedDefaultProfileId && now < cachedDefaultProfileId.expiry) return cachedDefaultProfileId.id;

  try {
    const payload = await apiGet("/profiles", null, parseProfilesResponse);
    const id = payload.profiles[0]?.id ?? null;
    cachedDefaultProfileId = { id, expiry: now + DEFAULT_PROFILE_CACHE_TTL_MS };
    return id;
  } catch (error) {
    logger.warn(error, "Failed to fetch profiles, falling back to cached/absent profile");
    return cachedDefaultProfileId?.id ?? null;
  }
};

export const resolveProfileScope = async (request: FastifyRequest): Promise<ProfileScope> => {
  const raw = profilePathParam(request);
  const params = request.params as Record<string, unknown> | null;
  const key = params?.profileKey;
  if (key !== undefined) {
    // A wrong key answers exactly as an unknown profile does.
    if (raw === undefined || !UUID_V4_PATTERN.test(raw)) return { ok: false };
    if (!matchesStremioProfileKey(key, CATALOGGY_API_TOKEN, raw)) return { ok: false };
    return { ok: true, profileId: raw };
  }
  if (REQUIRE_SIGNED_URLS) return { ok: false };
  if (raw !== undefined) {
    // A malformed id is never silently swapped for the default profile —
    // serving one profile's data under another's URL is exactly the kind of
    // quiet wrong answer this scoping exists to prevent.
    if (!UUID_V4_PATTERN.test(raw)) return { ok: false };
    return { ok: true, profileId: raw };
  }
  return { ok: true, profileId: await fetchDefaultProfileId(request.log) };
};
