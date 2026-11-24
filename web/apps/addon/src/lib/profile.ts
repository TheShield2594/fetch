import type { FastifyBaseLogger, FastifyRequest } from "fastify";
import { timingSafeEqual } from "node:crypto";
import {
  parseAuthStatusResponse,
  parseProfilesResponse,
  parseStremioKeyGenerationResponse,
  STREMIO_PROFILE_KEY_HEADER,
  UUID_V4_PATTERN,
} from "@fetch/shared";
import { type ApiProfile, apiGet, apiHeaders, apiUrl, fetchWithTimeout, NO_PROFILE } from "./api-client.js";
import { SIGNED_URL_MODE } from "./config.js";

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
 * it. This service holds no `API_TOKEN` (#637), so it can't derive the key; it
 * asks the API whether the key is the profile's current one, on every request
 * that carries one. These are the URLs that are safe to put on the internet. `ADDON_REQUIRE_SIGNED_URLS` turns the other two off, and
 * by default they are off as soon as the install has a second account.
 */
export const SIGNED_PROFILE_ROUTE_PREFIX = "/u/:profileId/:profileKey";
// The key also mixes in the profile's key generation (see below), so one
// profile's URL can be replaced without touching `API_TOKEN`.

// ─── Checking a key ───
//
// A profile's key also depends on its `stremioKeyGeneration`, which goes up
// each time someone replaces the profile's addon URL in Settings (#565). The
// API holds both that and `API_TOKEN`, so it is the API that says whether a key
// is current (`GET /profiles/:id/addon-url/verify`), and it is asked *now*: a
// replaced URL has to stop working right away, here as well as at the API, and
// this service caches whole responses that would otherwise carry on answering
// it.
//
// The last key confirmed per profile is kept for the rate limiter alone, which
// has to decide synchronously. It never authorizes a request: when the API
// can't be asked, signed URLs are refused. A key the API turns away is
// forgotten.
const confirmedKeys = new Map<string, string>();

const sameKey = (a: string, b: string): boolean =>
  a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

/** Whether `key` is the one last confirmed for `profileId`, for the rate limiter. */
export const isConfirmedStremioKey = (profileId: string, key: string): boolean => {
  const confirmed = confirmedKeys.get(profileId);
  return confirmed !== undefined && sameKey(confirmed, key);
};

// Stremio opens a handful of catalogs at once, all on the same URL: one lookup
// answers all of them.
const keyChecks = new Map<string, Promise<boolean>>();

const askApi = async (profileId: string, key: string, logger: FastifyBaseLogger): Promise<boolean> => {
  try {
    const response = await fetchWithTimeout(apiUrl(`/profiles/${profileId}/addon-url/verify`), {
      // In a header, not the path, so the key stays out of the API's access log.
      headers: { ...apiHeaders(NO_PROFILE), [STREMIO_PROFILE_KEY_HEADER]: key },
    });
    if (response.status === 404) {
      // A wrong key, a replaced one, or a profile that no longer exists.
      const confirmed = confirmedKeys.get(profileId);
      if (confirmed !== undefined && sameKey(confirmed, key)) confirmedKeys.delete(profileId);
      return false;
    }
    if (!response.ok) throw new Error(`API addon-url/verify returned ${response.status}`);
    parseStremioKeyGenerationResponse(await response.json());
    confirmedKeys.set(profileId, key);
    return true;
  } catch (error) {
    // Fail closed: only the API can say a key is current, so one it can't
    // confirm authorizes nothing, however recently it was seen.
    logger.warn(error, "Failed to check a profile's addon key; refusing its signed URL until the API answers");
    return false;
  }
};

/** Whether `key` is `profileId`'s current Stremio key, as the API says now. */
const isCurrentKey = (profileId: string, key: string, logger: FastifyBaseLogger): Promise<boolean> => {
  const lookupKey = `${profileId}:${key}`;
  const inFlight = keyChecks.get(lookupKey);
  if (inFlight) return inFlight;
  const lookup = askApi(profileId, key, logger).finally(() => keyChecks.delete(lookupKey));
  keyChecks.set(lookupKey, lookup);
  return lookup;
};

/**
 * `profileId: null` means "no profile bound" — the header is omitted and the
 * API applies its single-profile fallback. A successful scope is itself the
 * `ApiProfile` to call the API with; `profileKey` is non-null only for a signed
 * URL whose key checked out.
 */
export type ProfileScope = ({ ok: true } & ApiProfile) | { ok: false };

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

// Whether the install has more than one account, for `SIGNED_URL_MODE=auto`.
// Cached like the default profile; a failed lookup with nothing cached counts
// as "yes", so an API hiccup can't open the keyless URLs.
let cachedMultipleAccounts: { value: boolean; expiry: number } | null = null;
const ACCOUNTS_CACHE_TTL_MS = 60_000;

const hasMultipleAccounts = async (logger: FastifyBaseLogger): Promise<boolean> => {
  const now = Date.now();
  if (cachedMultipleAccounts && now < cachedMultipleAccounts.expiry) return cachedMultipleAccounts.value;

  try {
    const { multipleAccounts } = await apiGet("/auth/status", NO_PROFILE, parseAuthStatusResponse);
    // An API from before the field was added has no accounts to tell apart.
    const value = multipleAccounts ?? false;
    cachedMultipleAccounts = { value, expiry: now + ACCOUNTS_CACHE_TTL_MS };
    return value;
  } catch (error) {
    logger.warn(error, "Failed to check the account count; refusing unsigned addon URLs until it answers");
    return cachedMultipleAccounts?.value ?? true;
  }
};

/** Whether this request may use a keyless (`/p/<uuid>` or bare) URL. */
const unsignedUrlsAllowed = async (request: FastifyRequest): Promise<boolean> => {
  if (SIGNED_URL_MODE === "always") return false;
  if (SIGNED_URL_MODE === "never") return true;
  return !(await hasMultipleAccounts(request.log));
};

export const resolveProfileScope = async (request: FastifyRequest): Promise<ProfileScope> => {
  const raw = profilePathParam(request);
  const params = request.params as Record<string, unknown> | null;
  const key = params?.profileKey;
  if (key !== undefined) {
    // A wrong key answers exactly as an unknown profile does.
    if (raw === undefined || !UUID_V4_PATTERN.test(raw)) return { ok: false };
    // Shape first, so a malformed key costs no API call.
    if (typeof key !== "string" || !/^[0-9a-f]{64}$/.test(key)) return { ok: false };
    if (!(await isCurrentKey(raw, key, request.log))) return { ok: false };
    // The verified key travels on to the API, which accepts it (from this
    // service) in place of the profile's PIN token.
    return { ok: true, profileId: raw, profileKey: key };
  }
  if (!(await unsignedUrlsAllowed(request))) return { ok: false };
  if (raw !== undefined) {
    // A malformed id is never silently swapped for the default profile —
    // serving one profile's data under another's URL is exactly the kind of
    // quiet wrong answer this scoping exists to prevent.
    if (!UUID_V4_PATTERN.test(raw)) return { ok: false };
    // No key: an unsigned URL proves nothing, so the API's PIN gate still applies.
    return { ok: true, profileId: raw, profileKey: null };
  }
  return { ok: true, profileId: await fetchDefaultProfileId(request.log), profileKey: null };
};
