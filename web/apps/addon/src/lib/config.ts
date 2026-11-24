import {
  type AddonCredentials,
  deriveAddonKey,
  parseAddonKey,
  parseBoolStrict,
  parseProxyPathPrefixes,
  parseTrustProxy,
} from "@fetch/shared";

/**
 * Everything this service reads out of its environment, read once.
 *
 * One module rather than a `process.env` lookup wherever a value is wanted:
 * these are startup configuration, and a value that is read at the moment it is
 * used is a value a deployment can appear to change without a restart.
 */

export const FETCH_API_BASE = process.env.FETCH_API_BASE ?? "http://api:7000";

/**
 * What this service authenticates with, and where it came from (#637).
 *
 * `FETCH_ADDON_KEY` (from `fetch addon-key`) holds the two values derived from
 * `API_TOKEN` that this service needs — its service token for the API, and the
 * token it checks scrobbles against — and not `API_TOKEN` itself, which is the
 * operator's credential and the root of every stored secret's encryption key.
 * This is the service that may face the internet, so it is the one that must
 * not hold that.
 *
 * `FETCH_API_TOKEN` is how it was configured before. It still works — the same
 * two values are derived from it here, so nothing an existing install relies on
 * changes — but it puts the root key in this container, so it is warned about
 * at startup (`app.ts`). `FETCH_ADDON_KEY` wins when both are set.
 */
export type AddonCredentialSource =
  | { kind: "addon-key"; credentials: AddonCredentials }
  | { kind: "api-token"; credentials: AddonCredentials }
  | { kind: "invalid" }
  | { kind: "none" };

export const resolveAddonCredentials = (
  addonKey: string | undefined,
  apiToken: string | undefined
): AddonCredentialSource => {
  if (addonKey?.trim()) {
    const credentials = parseAddonKey(addonKey);
    return credentials ? { kind: "addon-key", credentials } : { kind: "invalid" };
  }
  if (apiToken) {
    // Through the same parser as a real key, so both paths produce one shape.
    return { kind: "api-token", credentials: parseAddonKey(deriveAddonKey(apiToken))! };
  }
  return { kind: "none" };
};

export const ADDON_CREDENTIAL_SOURCE = resolveAddonCredentials(
  process.env.FETCH_ADDON_KEY,
  process.env.FETCH_API_TOKEN
);

/** Whether `API_TOKEN` was handed to this container at all, to warn about it. */
export const HOLDS_API_TOKEN = Boolean(process.env.FETCH_API_TOKEN);

/** The two derived credentials, or null when none (or a malformed key) was configured. */
export const ADDON_CREDENTIALS: AddonCredentials | null =
  ADDON_CREDENTIAL_SOURCE.kind === "addon-key" || ADDON_CREDENTIAL_SOURCE.kind === "api-token"
    ? ADDON_CREDENTIAL_SOURCE.credentials
    : null;
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
