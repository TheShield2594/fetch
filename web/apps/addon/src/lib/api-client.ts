import {
  ApiContractError,
  deriveServiceToken,
  SERVICE_TOKEN_HEADER,
  STREMIO_PROFILE_KEY_HEADER,
} from "@fetch/shared";
import { FETCH_API_BASE, FETCH_API_TOKEN } from "./config.js";

/**
 * How this service talks to the Fetch API.
 *
 * Every route here is a thin translation of one or two API calls into Stremio's
 * wire shape, so this is the only place that knows the base URL, the
 * credentials, the timeout, and what to do when the API answers with something
 * other than what its contract says.
 */

// Proves these calls come from the addon rather than from a browser holding the
// same API token, so the API can rate-limit them separately — every Stremio
// client's requests reach the API from this one container's IP.
const SERVICE_TOKEN = FETCH_API_TOKEN ? deriveServiceToken(FETCH_API_TOKEN) : null;

/**
 * Who an API call acts as. `profileId: null` means no profile is bound and the
 * API applies its single-profile fallback.
 *
 * `profileKey` is set only when the request this service is answering came in
 * on a signed `/u/<profileId>/<key>/…` URL, and was verified there. Forwarded to
 * the API, it stands in for the profile-PIN token Stremio has no way to obtain:
 * holding the signed URL already proves the PIN was passed (see
 * `STREMIO_PROFILE_KEY_HEADER`). An unsigned `/p/<uuid>` URL never has one, so a
 * PIN-protected profile stays shut to it.
 */
export type ApiProfile = { profileId: string | null; profileKey: string | null };

/** For the few calls that act as no profile in particular. */
export const NO_PROFILE: ApiProfile = { profileId: null, profileKey: null };

export const apiHeaders = (profile?: ApiProfile | null): Record<string, string> => {
  const headers: Record<string, string> = {};
  if (SERVICE_TOKEN) headers[SERVICE_TOKEN_HEADER] = SERVICE_TOKEN;
  if (FETCH_API_TOKEN) headers.Authorization = `Bearer ${FETCH_API_TOKEN}`;
  if (profile?.profileId) {
    headers["x-profile-id"] = profile.profileId;
    if (profile.profileKey) headers[STREMIO_PROFILE_KEY_HEADER] = profile.profileKey;
  }
  return headers;
};

const FETCH_TIMEOUT_MS = 10_000;

export const fetchWithTimeout = async (url: string | URL, init?: RequestInit): Promise<Response> => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      throw new Error(`Request to ${String(url)} timed out after ${FETCH_TIMEOUT_MS}ms`, { cause: error });
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
};

/** An API URL, for a caller that has to make the request itself. */
export const apiUrl = (path: string): URL => new URL(path, FETCH_API_BASE);

// Every response is run through the contract parser for its endpoint rather
// than asserted into the expected type. An API that changes shape used to
// surface as an empty Stremio row with nothing in the logs; now it is a named
// error at the boundary, which every caller here already degrades gracefully on.
export const apiGet = async <T>(
  path: string,
  profile: ApiProfile | null | undefined,
  parse: (value: unknown) => T
): Promise<T> => {
  const response = await fetchWithTimeout(apiUrl(path), { headers: apiHeaders(profile) });
  if (!response.ok) throw new Error(`API ${path} returned ${response.status}`);
  const payload: unknown = await response.json();
  try {
    return parse(payload);
  } catch (error) {
    throw new ApiContractError(
      `API ${path} returned an unexpected shape: ${error instanceof Error ? error.message : String(error)}`,
      { cause: error }
    );
  }
};

// Unlike apiGet there is no contract to check: every caller either ignores the
// response or hands it straight back to the client without reading a field.
export const apiPost = async (path: string, body?: unknown, profile?: ApiProfile | null): Promise<unknown> => {
  const response = await fetchWithTimeout(apiUrl(path), {
    method: "POST",
    headers: {
      ...apiHeaders(profile),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok) throw new Error(`API POST ${path} returned ${response.status}`);
  return response.json();
};
