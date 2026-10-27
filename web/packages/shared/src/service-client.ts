import { createHmac, timingSafeEqual } from "node:crypto";

/** Header the addon sends to identify its calls to the API as service traffic. */
export const SERVICE_TOKEN_HEADER = "x-fetch-service-token";

const SERVICE_TOKEN_LABEL = "fetch-service-client";

/**
 * Derives the addon's service token from the shared API token.
 *
 * The addon has to prove it *is* the addon, not merely that it holds
 * `API_TOKEN` — the browser holds that too, so the raw token cannot tell the
 * two apart. Deriving costs no extra configuration (both sides already have
 * `API_TOKEN`) and keeps the blast radius small: the derived value grants
 * nothing on its own, and does not yield the token it came from.
 *
 * Same construction as the addon's mutation token in `apps/addon/src/app.ts`.
 */
export const deriveServiceToken = (apiToken: string): string =>
  createHmac("sha256", apiToken).update(SERVICE_TOKEN_LABEL).digest("hex");

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
 */
export const deriveStremioProfileKey = (apiToken: string, profileId: string): string =>
  createHmac("sha256", apiToken).update(`${STREMIO_PROFILE_SECRET_LABEL}:${profileId}`).digest("hex");

const STREMIO_PROFILE_KEY_PATTERN = /^[0-9a-f]{64}$/;

/**
 * Constant-time check of a presented profile key. The shape check comes first
 * because timingSafeEqual throws on buffers of different byte lengths, and 64
 * characters of anything but ASCII is more than 64 bytes.
 */
export const matchesStremioProfileKey = (candidate: unknown, apiToken: string | undefined, profileId: string): boolean => {
  if (!apiToken || typeof candidate !== "string" || !STREMIO_PROFILE_KEY_PATTERN.test(candidate)) return false;
  return timingSafeEqual(Buffer.from(candidate), Buffer.from(deriveStremioProfileKey(apiToken, profileId)));
};
