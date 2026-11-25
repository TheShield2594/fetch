/**
 * One public address for the whole stack.
 *
 * A self-hosted install used to set six URL variables by hand
 * (FETCH_ALLOWED_ORIGINS, FETCH_API_PUBLIC, FETCH_WEB_PUBLIC, ADDON_PUBLIC_BASE,
 * VITE_API_BASE, VITE_ADDON_BASE, plus TRAKT_REDIRECT_URI), and they all had to
 * agree. Forgetting one left a healthy-looking stack whose cookies, CORS, QR
 * codes and invite links pointed somewhere else (#644).
 *
 * `FETCH_PUBLIC_URL` is the address people open the website at. Everything else
 * is derived from it, in one of two layouts:
 *
 *  - **Ports** — the plain LAN install, where the URL is on the web service's
 *    own port 7002 (`http://192.168.1.25:7002`): the API is the same host on
 *    port 7000 and the add-on on port 7001, as docker-compose.yml publishes them.
 *  - **Paths** — anything else, e.g. `https://fetch.example.com` behind a
 *    reverse proxy: the API is at `/api` and the add-on at `/addon`, the two
 *    prefixes the services strip by default (PROXY_PATH_PREFIXES).
 *
 * A variable that is set explicitly always wins; FETCH_PUBLIC_URL only fills
 * the ones that are unset or empty. Compose hands an unset `${VAR}` to the
 * container as an empty string, so an empty value counts as unset here, and is
 * removed when there is nothing to derive it from — the code that reads these
 * variables then sees "not configured", as it would outside Docker.
 *
 * This file has no imports on purpose: the web image copies its compiled form
 * next to its entrypoint, where no node_modules exist.
 */

/** The variables {@link expandPublicUrlEnv} may fill in. */
export const PUBLIC_URL_VARIABLES = [
  "FETCH_ALLOWED_ORIGINS",
  "FETCH_API_PUBLIC",
  "FETCH_WEB_PUBLIC",
  "ADDON_PUBLIC_BASE",
  "VITE_API_BASE",
  "VITE_ADDON_BASE",
  "TRAKT_REDIRECT_URI",
] as const;

export type PublicUrlVariable = (typeof PUBLIC_URL_VARIABLES)[number];

/** The web service's container port; a FETCH_PUBLIC_URL on it means the ports layout. */
export const WEB_PORT = "7002";
const API_PORT = "7000";
const ADDON_PORT = "7001";

/**
 * The address the docs and the old compose defaults used as an example. A URL
 * still holding it almost always means a copied example nobody edited.
 */
export const EXAMPLE_HOST = "192.168.1.25";

export type PublicUrlLayout = "ports" | "paths";

export interface DerivedPublicUrls {
  layout: PublicUrlLayout;
  web: string;
  api: string;
  addon: string;
  allowedOrigins: string[];
  traktRedirectUri: string;
}

const stripTrailingSlash = (value: string): string => value.replace(/\/+$/, "");

/**
 * Parses FETCH_PUBLIC_URL. A bare host (`fetch.example.com`,
 * `192.168.1.25:7002`) is taken as http for a private-looking address with a
 * port and https otherwise, the way people type it into a browser.
 */
export function parsePublicUrl(raw: string): URL | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)
    ? trimmed
    : `${/:\d+(\/|$)/.test(trimmed) ? "http" : "https"}://${trimmed}`;
  try {
    const url = new URL(withScheme);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    if (url.search || url.hash || url.username || url.password) return null;
    return url;
  } catch {
    return null;
  }
}

/** Every public URL derived from FETCH_PUBLIC_URL, or null when it doesn't parse. */
export function derivePublicUrls(raw: string): DerivedPublicUrls | null {
  const url = parsePublicUrl(raw);
  if (!url) return null;
  const web = stripTrailingSlash(`${url.origin}${url.pathname}`);
  if (url.port === WEB_PORT) {
    // `hostname` keeps the brackets of an IPv6 literal, so this stays a valid URL.
    const base = `${url.protocol}//${url.hostname}`;
    const api = `${base}:${API_PORT}`;
    return {
      layout: "ports",
      web,
      api,
      addon: `${base}:${ADDON_PORT}`,
      // localhost on the same port keeps working for someone on the server
      // itself, as the old compose default allowed.
      allowedOrigins: unique([
        url.origin,
        `http://localhost:${WEB_PORT}`,
        `http://127.0.0.1:${WEB_PORT}`,
      ]),
      traktRedirectUri: `${api}/trakt/oauth/callback`,
    };
  }
  const api = `${web}/api`;
  return {
    layout: "paths",
    web,
    api,
    addon: `${web}/addon`,
    allowedOrigins: [url.origin],
    traktRedirectUri: `${api}/trakt/oauth/callback`,
  };
}

function unique(values: string[]): string[] {
  return Array.from(new Set(values));
}

export interface PublicUrlExpansion {
  /** What FETCH_PUBLIC_URL produced, or null when it is unset or invalid. */
  derived: DerivedPublicUrls | null;
  /** Variables this call filled in from FETCH_PUBLIC_URL. */
  filled: PublicUrlVariable[];
  /** Things the operator should hear about at startup. */
  warnings: string[];
}

type Env = Record<string, string | undefined>;

const isBlank = (value: string | undefined): boolean => value === undefined || value.trim() === "";

/**
 * The value FETCH_PUBLIC_URL gives `name`. An address that was set explicitly
 * carries through to the variables that repeat it — an overridden
 * FETCH_API_PUBLIC is also the web page's API base and the Trakt redirect's
 * host — so an override never leaves the two halves disagreeing.
 */
function valueFor(name: PublicUrlVariable, derived: DerivedPublicUrls, env: Env): string {
  const explicit = (key: string): string | undefined =>
    isBlank(env[key]) ? undefined : stripTrailingSlash((env[key] as string).trim());
  const api = explicit("FETCH_API_PUBLIC") ?? derived.api;
  // WEB_PUBLIC_BASE is the older name every consumer still falls back to.
  const web = explicit("FETCH_WEB_PUBLIC") ?? explicit("WEB_PUBLIC_BASE") ?? derived.web;
  const addon = explicit("ADDON_PUBLIC_BASE") ?? derived.addon;
  switch (name) {
    case "FETCH_ALLOWED_ORIGINS": {
      const webOrigin = parsePublicUrl(web)?.origin;
      return unique([...(webOrigin ? [webOrigin] : []), ...derived.allowedOrigins]).join(",");
    }
    case "FETCH_API_PUBLIC":
    case "VITE_API_BASE":
      return api;
    case "FETCH_WEB_PUBLIC":
      return web;
    case "ADDON_PUBLIC_BASE":
    case "VITE_ADDON_BASE":
      return addon;
    case "TRAKT_REDIRECT_URI":
      return `${api}/trakt/oauth/callback`;
  }
}

/**
 * The web page's copy of an overridden service address when there is no
 * FETCH_PUBLIC_URL to derive from: VITE_API_BASE follows FETCH_API_PUBLIC and
 * VITE_ADDON_BASE follows ADDON_PUBLIC_BASE, as they do when it is set.
 */
function overrideWithoutPublicUrl(name: PublicUrlVariable, env: Env): string | undefined {
  const source = name === "VITE_API_BASE" ? "FETCH_API_PUBLIC" : name === "VITE_ADDON_BASE" ? "ADDON_PUBLIC_BASE" : null;
  if (!source || isBlank(env[source])) return undefined;
  return stripTrailingSlash((env[source] as string).trim());
}

/** True when a URL (or comma-separated list of them) still names the example address. */
export function mentionsExampleHost(value: string | undefined): boolean {
  if (!value) return false;
  return value.split(",").some((entry) => {
    const url = parsePublicUrl(entry);
    return url?.hostname === EXAMPLE_HOST;
  });
}

/**
 * Fills the public URL variables in `env` from FETCH_PUBLIC_URL, in place.
 * `names` limits which ones a service fills (each service only reads some).
 */
export function expandPublicUrlEnv(
  env: Env,
  names: readonly PublicUrlVariable[] = PUBLIC_URL_VARIABLES
): PublicUrlExpansion {
  const warnings: string[] = [];
  const filled: PublicUrlVariable[] = [];
  const raw = env.FETCH_PUBLIC_URL;
  let derived: DerivedPublicUrls | null = null;
  if (!isBlank(raw)) {
    derived = derivePublicUrls(raw as string);
    if (!derived) {
      warnings.push(
        `FETCH_PUBLIC_URL=${JSON.stringify(raw)} is not a URL; set it to the address you open the website at, ` +
          "e.g. http://192.168.1.25:7002 or https://fetch.example.com. Nothing was derived from it."
      );
    }
  }
  for (const name of names) {
    if (!isBlank(env[name])) continue;
    if (derived) {
      env[name] = valueFor(name, derived, env);
      filled.push(name);
    } else {
      const override = overrideWithoutPublicUrl(name, env);
      if (override !== undefined) env[name] = override;
      else delete env[name];
    }
  }
  const stillExample = names.filter((name) => mentionsExampleHost(env[name]));
  if (mentionsExampleHost(raw)) stillExample.unshift("FETCH_PUBLIC_URL" as PublicUrlVariable);
  if (stillExample.length > 0) {
    warnings.push(
      `${stillExample.join(", ")} still point${stillExample.length === 1 ? "s" : ""} at ${EXAMPLE_HOST}, ` +
        "the example address from the docs. Unless that really is this server's LAN address, cookies, CORS, " +
        "QR codes and invite links will point at a machine that isn't this one. Set FETCH_PUBLIC_URL in .env " +
        "to the address you open the website at and remove the per-URL variables."
    );
  }
  return { derived, filled, warnings };
}

/** One line for the startup log saying what FETCH_PUBLIC_URL resolved to. */
export function describeExpansion(expansion: PublicUrlExpansion, env: Env): string | null {
  if (!expansion.derived || expansion.filled.length === 0) return null;
  const parts = expansion.filled.map((name) => `${name}=${env[name]}`);
  return `FETCH_PUBLIC_URL (${expansion.derived.layout} layout) → ${parts.join(" ")}`;
}

/**
 * The browser-side fallback when the web container was given no API address:
 * the same two layouts, read from the page's own location.
 */
export function defaultBasesForLocation(location: { protocol: string; hostname: string; port: string; origin: string }): {
  api: string;
  addon: string;
} {
  if (location.port === WEB_PORT) {
    return {
      api: `${location.protocol}//${location.hostname}:${API_PORT}`,
      addon: `${location.protocol}//${location.hostname}:${ADDON_PORT}`,
    };
  }
  return { api: `${location.origin}/api`, addon: `${location.origin}/addon` };
}
