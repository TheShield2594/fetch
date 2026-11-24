import { createHmac, timingSafeEqual } from "node:crypto";

/** Header the addon sends to identify its calls to the API as service traffic. */
export const SERVICE_TOKEN_HEADER = "x-fetch-service-token";

const SERVICE_TOKEN_LABEL = "fetch-service-client";

/**
 * Derives the addon's service token from the shared API token.
 *
 * The addon has to prove it *is* the addon, not merely that it holds
 * `API_TOKEN` — other callers can hold that too (the operator's scripts, a
 * browser that stored it before accounts existed; a signed-in browser holds a
 * session token instead), so the raw token cannot single the addon out. Deriving costs no extra configuration (both sides already have
 * `API_TOKEN`) and keeps the blast radius small: the derived value grants
 * nothing on its own, and does not yield the token it came from.
 *
 * Same construction as the addon's mutation token (`deriveAddonMutationToken`).
 *
 * Since #637 it is also the add-on's whole credential at the API: sent without
 * `API_TOKEN`, it is accepted on the routes the add-on calls and nowhere else
 * (`apps/api/src/lib/addon-scope.ts`). It reaches the add-on inside
 * `FETCH_ADDON_KEY`.
 */
export const deriveServiceToken = (apiToken: string): string =>
  createHmac("sha256", apiToken).update(SERVICE_TOKEN_LABEL).digest("hex");

const ADDON_MUTATION_LABEL = "fetch-addon-mutation";

/**
 * The bearer token the add-on service's scrobble route takes from media
 * players. Unchanged from when the add-on derived it itself, so an integration
 * configured with it keeps working.
 */
export const deriveAddonMutationToken = (apiToken: string): string =>
  createHmac("sha256", apiToken).update(ADDON_MUTATION_LABEL).digest("hex");

/**
 * `FETCH_ADDON_KEY`: everything the add-on service needs, and nothing it
 * doesn't (#637).
 *
 * `API_TOKEN` is the operator's credential and the root every stored secret is
 * encrypted under, and the add-on is the one service that may face the
 * internet. So the add-on is handed only two values derived from it, joined
 * into one string so there is one thing to copy:
 *
 * - the service token (`deriveServiceToken`), which the API accepts from the
 *   add-on alone, and on only the routes it calls;
 * - the scrobble token (`deriveAddonMutationToken`), which it checks media
 *   players' scrobbles against.
 *
 * Both are HMACs of `API_TOKEN`, so neither yields it, and neither can mint a
 * profile's Stremio key: the add-on asks the API whether a signed URL's key is
 * current instead of computing it.
 *
 * Shape: `fak1_` then the two 64-character hex digests, back to back.
 */
export const ADDON_KEY_PREFIX = "fak1_";

const ADDON_KEY_PATTERN = /^fak1_([0-9a-f]{64})([0-9a-f]{64})$/;

export type AddonCredentials = { serviceToken: string; mutationToken: string };

export const deriveAddonKey = (apiToken: string): string =>
  `${ADDON_KEY_PREFIX}${deriveServiceToken(apiToken)}${deriveAddonMutationToken(apiToken)}`;

/** The two credentials inside a `FETCH_ADDON_KEY`, or null when it isn't one. */
export const parseAddonKey = (value: string | undefined | null): AddonCredentials | null => {
  const match = ADDON_KEY_PATTERN.exec(value?.trim() ?? "");
  if (!match) return null;
  return { serviceToken: match[1]!, mutationToken: match[2]! };
};

/**
 * Constant-time comparison of a presented service token against the expected
 * one. Both are fixed-width hex digests, so the length guard — needed because
 * `timingSafeEqual` throws on a length mismatch — cannot leak anything about
 * the secret.
 */
export const matchesServiceToken = (candidate: unknown, expected: string | null): boolean => {
  if (!expected || typeof candidate !== "string" || !candidate) return false;
  const presented = Buffer.from(candidate);
  const wanted = Buffer.from(expected);
  if (presented.length !== wanted.length) return false;
  return timingSafeEqual(presented, wanted);
};

const STREMIO_PROFILE_SECRET_LABEL = "fetch-stremio-addon";

/**
 * A profile's Stremio key: the unguessable part of every addon URL that serves
 * that profile. Stremio sends no credentials, so the URL itself is the
 * credential. Derived from `API_TOKEN` rather than stored, so the API and the
 * addon service agree on it with nothing extra configured, a leaked URL does
 * not yield the token, and rotating `API_TOKEN` rotates every key.
 *
 * One key per profile, used by both the API's own catalog addon
 * (`/addon/stremio/<key>/…`) and the addon service (`/u/<profileId>/<key>/…`).
 * Only the API derives it: the add-on service holds no `API_TOKEN` and asks the
 * API to check a key (`GET /profiles/:id/addon-url/verify`).
 */
/**
 * `generation` is the profile's `stremioKeyGeneration`: replacing a profile's
 * URL (Settings → Stremio addon) bumps it, which changes this profile's key and
 * no one else's. Generation 0 is the original derivation, unchanged, so URLs
 * installed before the counter existed keep working until someone replaces
 * them.
 */
export const deriveStremioProfileKey = (apiToken: string, profileId: string, generation = 0): string =>
  createHmac("sha256", apiToken)
    .update(
      generation > 0
        ? `${STREMIO_PROFILE_SECRET_LABEL}:${profileId}:${generation}`
        : `${STREMIO_PROFILE_SECRET_LABEL}:${profileId}`
    )
    .digest("hex");

/**
 * Header the addon service uses to pass the API the profile key from a signed
 * `/u/<profileId>/<key>/…` URL it is serving.
 *
 * The key is the credential a Stremio installation holds for that profile, and
 * only someone signed in as the profile (past its PIN) could have copied it out
 * of Settings. So the API accepts it, together with the service token, in place
 * of an `x-profile-token` for that one profile. The addon sends it only for
 * signed URLs; an unsigned `/p/<uuid>` URL proves nothing and carries no key.
 */
export const STREMIO_PROFILE_KEY_HEADER = "x-fetch-stremio-profile-key";

const STREMIO_PROFILE_KEY_PATTERN = /^[0-9a-f]{64}$/;

/**
 * Constant-time check of a presented profile key. The shape check comes first
 * because timingSafeEqual throws on buffers of different byte lengths, and 64
 * characters of anything but ASCII is more than 64 bytes.
 */
export const matchesStremioProfileKey = (
  candidate: unknown,
  apiToken: string | undefined,
  profileId: string,
  generation = 0
): boolean => {
  if (!apiToken || typeof candidate !== "string" || !STREMIO_PROFILE_KEY_PATTERN.test(candidate)) return false;
  return timingSafeEqual(Buffer.from(candidate), Buffer.from(deriveStremioProfileKey(apiToken, profileId, generation)));
};
