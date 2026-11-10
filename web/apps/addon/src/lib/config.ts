import { parseProxyPathPrefixes, parseTrustProxy, parseBoolStrict } from "@fetch/shared";

/**
 * Everything this service reads out of its environment, read once.
 *
 * One module rather than a `process.env` lookup wherever a value is wanted:
 * these are startup configuration, and a value that is read at the moment it is
 * used is a value a deployment can appear to change without a restart.
 */

export const FETCH_API_BASE = process.env.FETCH_API_BASE ?? "http://api:7000";
export const FETCH_API_TOKEN = process.env.FETCH_API_TOKEN;
export const ADDON_PUBLIC_BASE = process.env.ADDON_PUBLIC_BASE;
export const WEB_PUBLIC_BASE = (process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.replace(/\/+$/, "");
export const PROXY_PATH_PREFIXES = parseProxyPathPrefixes(process.env.PROXY_PATH_PREFIXES, ["/addon"] as const);

// Omitted rather than passed as `undefined` when `TRUST_PROXY` is unset:
// Fastify's `trustProxy?:` means "the key may be missing", and handing it an
// explicit undefined under `exactOptionalPropertyTypes` drops `fastify()` to
// its HTTP/2 overload — which typed every route registration against the wrong
// server and the wrong request.
export const trustProxy = parseTrustProxy(process.env.TRUST_PROXY);

/**
 * When to answer only the signed `/u/<profileId>/<key>/` URLs and turn away the
 * bare and `/p/<profileId>/` ones. Those carry no secret: anyone who can reach
 * this service and knows (or guesses) a profile id could read that profile's
 * lists and record watches for it, and the bare form serves the oldest profile.
 *
 * - `always` (`true`): signed only. Set it before this service faces the internet.
 * - `never` (`false`): the old forms keep working whatever the install looks like.
 * - `auto` (unset, the default): the old forms keep working while one account
 *   owns every profile, and stop the moment a second account exists. Their
 *   only users are installs from before the signed URL, and those are always
 *   single-account; once friends have accounts, the bare URL would hand them
 *   (or anyone else) the admin's lists.
 */
export type SignedUrlMode = "always" | "never" | "auto";

export const parseSignedUrlMode = (value: string | undefined): SignedUrlMode => {
  const normalized = value?.trim().toLowerCase() ?? "";
  if (normalized === "" || normalized === "auto") return "auto";
  const flag = parseBoolStrict(normalized);
  // A typo lands on `auto`, which refuses when it matters, rather than on `never`.
  return flag === null ? "auto" : flag ? "always" : "never";
};

export const SIGNED_URL_MODE = parseSignedUrlMode(process.env.ADDON_REQUIRE_SIGNED_URLS);


export const LOG_LEVEL = process.env.LOG_LEVEL ?? "info";

/**
 * Where Stremio's "Configure" button sends someone: the Stremio Addon section of
 * the web UI's Settings, opened, rather than the Preferences tab it lands on by
 * default. Null without a public web address.
 */
export const addonSettingsUrl = (): string | null =>
  WEB_PUBLIC_BASE ? `${WEB_PUBLIC_BASE}/settings?tab=integrations&section=addon` : null;

/** The address this service tells Stremio to come back to, for the URLs it mints. */
export const addonBaseUrl = (): string => ADDON_PUBLIC_BASE ?? `http://localhost:${process.env.PORT ?? 7001}`;
