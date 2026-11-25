import { readFileSync } from "node:fs";

/**
 * What this server is, and which TV apps it works with (#639).
 *
 * The server and the TV app are released on their own version lines (the server
 * is 0.x, the TV 1.x), so neither version says anything about the other. What
 * they agree on is `API_VERSION`: a single integer the server raises whenever it
 * changes a route the TV calls in a way an older TV can't cope with. The TV
 * carries the lowest `API_VERSION` it needs; the server carries the lowest TV
 * build it still serves (`MIN_TV_APP`). Each side checks the other's, so a
 * mismatch is one plain message ("update the app" or "update your server")
 * instead of a sync that fails part-way through with a 404.
 */

/** Raised on a breaking change to a route the TV calls. See docs/compatibility.md. */
export const API_VERSION = 1;

/**
 * The oldest TV app this server still answers. A TV that sends a lower
 * `X-Fetch-App-Version` gets a 426 with `code: "tv_app_too_old"`.
 *
 * 1.9.0 is the first build that syncs profiles, Home rows and TV settings the
 * way this server expects them; older builds only half-work against it.
 */
export const MIN_TV_APP = "1.9.0";

/**
 * Optional route groups a client can test for rather than inferring them from
 * the version. Add an entry when a TV-facing capability lands; never remove one
 * without raising `API_VERSION`.
 */
export const FEATURES = [
  "profiles",
  "profile-pins",
  "collections",
  "addons",
  "home-layout",
  "tv-settings",
  "playback",
  "watch-state",
  "lists",
  "calendar",
  "stats",
  "sync-report",
] as const;

/** The header a TV sends with its build's version on every request. */
export const APP_VERSION_HEADER = "x-fetch-app-version";

const readServerVersion = (): string => {
  // `dist/lib/version.js` and `src/lib/version.ts` are both two levels below
  // the package root, so one relative path serves the build and `tsx`.
  try {
    const raw = readFileSync(new URL("../../package.json", import.meta.url), "utf8");
    const version = (JSON.parse(raw) as { version?: unknown }).version;
    return typeof version === "string" && version.length > 0 ? version : "unknown";
  } catch {
    return "unknown";
  }
};

export const SERVER_VERSION = readServerVersion();

export type VersionInfo = {
  server: string;
  apiVersion: number;
  minTvApp: string;
  features: string[];
};

export const versionInfo = (): VersionInfo => ({
  server: SERVER_VERSION,
  apiVersion: API_VERSION,
  minTvApp: MIN_TV_APP,
  features: [...FEATURES],
});

/**
 * The numeric parts of a TV build's version: "1.9.1" → [1, 9, 1]. Anything
 * after the numbers ("1.9.1-debug", "2.0.0 (beta)") is ignored. Null when the
 * string doesn't start with a number, so a header nobody can read is never
 * taken as "too old".
 */
export const parseAppVersion = (raw: string | undefined | null): number[] | null => {
  const match = raw?.trim().match(/^v?(\d+(?:\.\d+)*)/);
  if (!match?.[1]) return null;
  return match[1].split(".").map((part) => Number(part));
};

/** Negative when `a` is older than `b`, zero when equal, positive when newer. */
export const compareVersions = (a: number[], b: number[]): number => {
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i += 1) {
    const diff = (a[i] ?? 0) - (b[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
};

/** Whether a TV reporting `appVersion` is below `MIN_TV_APP`. Unreadable versions are let through. */
export const isTvAppTooOld = (appVersion: string | undefined | null, minimum: string = MIN_TV_APP): boolean => {
  const app = parseAppVersion(appVersion);
  const min = parseAppVersion(minimum);
  if (!app || !min) return false;
  return compareVersions(app, min) < 0;
};
