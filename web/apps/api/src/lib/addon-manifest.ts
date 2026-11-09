import { guardedFetch, type GuardedRequest, type OutboundScope, readBodyText } from "./ssrf.js";

/**
 * Fetching and checking a Stremio addon manifest someone pasted on the website.
 *
 * Two things shape everything here:
 *
 *   - **The URL is a credential.** Configured addons put their settings — a
 *     debrid API key, an account token — in the path or the query, so the
 *     manifest URL is as secret as whatever was typed into the addon's
 *     configure page. Nothing in this module logs or returns it in an error;
 *     `manifestUrlForLog` is the only form that may reach a log line.
 *   - **The host is often on the LAN.** AIOStreams, Jackettio and friends are
 *     self-hosted next to this server, so the policy is the one every other
 *     user-configured target gets (`ssrf.ts`): the admin's profiles may reach
 *     loopback and private ranges, every other account's only public hosts,
 *     and nobody the cloud metadata endpoints. The connection is pinned to
 *     the checked addresses (`guardedFetch`), so DNS rebinding can't move it.
 *   - **The answer is a verdict, not a probe result.** A failure says only
 *     whether the address was refused, unreachable, or reached without a
 *     manifest — never the status code or what the body looked like — for the
 *     reason `outbound-test.ts` gives: those details name the service behind
 *     a port, and a list of addons is a list of ports to try. The detail goes
 *     to the server log through `detail`.
 */

/** Generous: a big catalog addon's manifest is tens of KB. */
const MAX_MANIFEST_BYTES = 512 * 1024;
const MANIFEST_TIMEOUT_MS = 8_000;
const MAX_CATALOGS = 200;
const MAX_FIELD_LENGTH = 300;
const MAX_DESCRIPTION_LENGTH = 2_000;

/** The parts of a manifest the website and the Home layout read. */
export type CachedCatalog = { type: string; id: string; name: string };

export type CachedManifest = {
  id: string;
  name: string;
  version: string;
  description: string | null;
  logo: string | null;
  types: string[];
  resources: string[];
  catalogs: CachedCatalog[];
  configurable: boolean;
  configurationRequired: boolean;
};

export type ManifestResult =
  | { ok: true; manifest: CachedManifest }
  /** `reason` is shown to the caller; `detail`, when there is one, is for the server log only. */
  | { ok: false; reason: string; detail?: string };

/**
 * The form a manifest URL is stored and compared in, or null when it can't be
 * one. `stremio://` is what an addon's own Install button links to, and means
 * the same address over https.
 */
export const normalizeManifestUrl = (raw: string): string | null => {
  const trimmed = raw.trim();
  const candidate = /^stremio:\/\//i.test(trimmed) ? `https://${trimmed.slice("stremio://".length)}` : trimmed;
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (!url.pathname.endsWith("/manifest.json")) return null;
  url.hash = "";
  return url.toString();
};

/** Scheme and host only: enough to tell addons apart in a log, never the configured path. */
export const manifestUrlForLog = (manifestUrl: string): string => {
  try {
    const url = new URL(manifestUrl);
    return `${url.protocol}//${url.host}/…/manifest.json`;
  } catch {
    return "(invalid URL)";
  }
};

/** Where the addon's own settings page lives, for addons that have one. */
export const configureUrlFor = (manifestUrl: string): string =>
  manifestUrl.replace(/\/manifest\.json(\?.*)?$/, "/configure");

const text = (value: unknown, max = MAX_FIELD_LENGTH): string | null =>
  typeof value === "string" && value.trim() ? value.trim().slice(0, max) : null;

const httpUrl = (value: unknown): string | null => {
  const raw = text(value, 2_048);
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
};

const stringList = (value: unknown): string[] =>
  Array.isArray(value)
    ? [...new Set(value.map((entry) => text(entry)).filter((entry): entry is string => entry !== null))]
    : [];

/**
 * Reads a manifest object into the trimmed shape that is cached, or says why it
 * isn't a Stremio manifest. Only what the protocol requires is required: `id`,
 * `name`, `version` and `resources`.
 */
export const parseManifest = (value: unknown): ManifestResult => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return { ok: false, reason: "The address didn't return a Stremio manifest (expected a JSON object)." };
  }
  const raw = value as Record<string, unknown>;
  const id = text(raw.id);
  const name = text(raw.name);
  const version = text(raw.version, 64);
  if (!id || !name || !version) {
    return { ok: false, reason: "The manifest is missing its id, name or version, so it isn't a Stremio addon." };
  }
  if (!Array.isArray(raw.resources)) {
    return { ok: false, reason: "The manifest doesn't list any resources, so it isn't a Stremio addon." };
  }

  // A resource is either a bare name or `{ name, types, idPrefixes }`.
  const resources = [
    ...new Set(
      raw.resources
        .map((entry) =>
          typeof entry === "string" ? text(entry) : text((entry as { name?: unknown } | null)?.name)
        )
        .filter((entry): entry is string => entry !== null)
    ),
  ];

  const catalogs: CachedCatalog[] = [];
  if (Array.isArray(raw.catalogs)) {
    for (const entry of raw.catalogs.slice(0, MAX_CATALOGS)) {
      const catalog = entry as { type?: unknown; id?: unknown; name?: unknown } | null;
      const type = text(catalog?.type);
      const catalogId = text(catalog?.id);
      if (!type || !catalogId) continue;
      catalogs.push({ type, id: catalogId, name: text(catalog?.name) ?? catalogId });
    }
  }

  const hints = (typeof raw.behaviorHints === "object" && raw.behaviorHints !== null
    ? raw.behaviorHints
    : {}) as { configurable?: unknown; configurationRequired?: unknown };

  return {
    ok: true,
    manifest: {
      id,
      name,
      version,
      description: text(raw.description, MAX_DESCRIPTION_LENGTH),
      logo: httpUrl(raw.logo),
      types: stringList(raw.types),
      resources,
      catalogs,
      configurable: hints.configurable === true,
      configurationRequired: hints.configurationRequired === true,
    },
  };
};

export const MANIFEST_BLOCKED_REASON = "That address isn't one this server may fetch.";
export const MANIFEST_UNREACHABLE_REASON =
  "The addon didn't answer. Check the address and that the addon is running.";
export const MANIFEST_REJECTED_REASON =
  "The address answered, but not with a Stremio addon manifest. Check that it is the addon's manifest address.";

/** How the manifest is requested; tests stand in for `guardedFetch`. */
export type ManifestFetcher = (url: string, scope: OutboundScope, init: GuardedRequest) => Promise<Response | null>;

export type FetchManifestOptions = { fetcher?: ManifestFetcher };

/**
 * Fetches `manifestUrl` (already normalized) under the profile's outbound
 * `scope` and parses it. Every failure comes back as one of three reasons,
 * phrased without the URL.
 */
export const fetchManifest = async (
  manifestUrl: string,
  scope: OutboundScope,
  { fetcher = guardedFetch }: FetchManifestOptions = {}
): Promise<ManifestResult> => {
  let response: Response | null;
  try {
    // guardedFetch refuses a redirect rather than following it: the address
    // it points at would skip the checks.
    response = await fetcher(manifestUrl, scope, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(MANIFEST_TIMEOUT_MS),
    });
  } catch (error) {
    return {
      ok: false,
      reason: MANIFEST_UNREACHABLE_REASON,
      detail: error instanceof Error ? (error.cause instanceof Error ? error.cause.message : error.message) : "fetch failed",
    };
  }
  if (!response) return { ok: false, reason: MANIFEST_BLOCKED_REASON };

  const rejected = (detail: string): ManifestResult => ({ ok: false, reason: MANIFEST_REJECTED_REASON, detail });
  if (!response.ok) {
    await response.body?.cancel().catch(() => undefined);
    return rejected(`HTTP ${response.status}`);
  }

  let body: string;
  try {
    body = await readBodyText(response, MAX_MANIFEST_BYTES);
  } catch {
    return rejected("the body was too large or was cut off");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return rejected("the body isn't JSON");
  }
  const result = parseManifest(parsed);
  return result.ok ? result : rejected(result.reason);
};

/** A stored `manifest` column read back, or null when it isn't one of ours. */
export const readCachedManifest = (value: unknown): CachedManifest | null => {
  if (typeof value !== "object" || value === null) return null;
  const raw = value as Partial<CachedManifest>;
  // Stored with the flags at the top level rather than under `behaviorHints`,
  // so it is re-read through the parser with them put back where it looks.
  const result = parseManifest({
    ...raw,
    behaviorHints: { configurable: raw.configurable, configurationRequired: raw.configurationRequired },
  });
  return result.ok ? result.manifest : null;
};
