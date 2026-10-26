import { fetchWithPolicy } from "./http.js";
import { type HostResolver, resolveAiProviderUrl, validateAiProviderUrl } from "./ssrf.js";

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
 *   - **The host is usually on the LAN.** AIOStreams, Jackettio and friends are
 *     self-hosted next to this server, so the outbound policy is the AI
 *     provider's: loopback and private ranges are allowed, the cloud metadata
 *     endpoints are not.
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

export type ManifestResult = { ok: true; manifest: CachedManifest } | { ok: false; reason: string };

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

/** Reads at most `limit` bytes of the body, or null when there is more than that. */
const readCapped = async (response: Response, limit: number): Promise<string | null> => {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > limit) return null;
  if (!response.body) return "";

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
};

export type FetchManifestOptions = { resolveHost?: HostResolver };

/**
 * Fetches `manifestUrl` (already normalized) and parses it. Every failure comes
 * back as a reason a person can act on, phrased without the URL.
 */
export const fetchManifest = async (
  manifestUrl: string,
  options: FetchManifestOptions = {}
): Promise<ManifestResult> => {
  if (!validateAiProviderUrl(manifestUrl)) {
    return { ok: false, reason: "That address isn't one this server is allowed to fetch." };
  }
  const target = await resolveAiProviderUrl(manifestUrl, options.resolveHost);
  if (!target) {
    return { ok: false, reason: "The addon's host couldn't be found, or isn't one this server is allowed to fetch." };
  }

  let response: Response;
  try {
    response = await fetchWithPolicy(
      target,
      // A redirect is refused rather than followed: the address it points at
      // would skip the check above.
      { headers: { Accept: "application/json" }, redirect: "manual" },
      { timeoutMs: MANIFEST_TIMEOUT_MS, retries: 1 }
    );
  } catch {
    return { ok: false, reason: "The addon didn't answer. Check the address and that the addon is running." };
  }

  if (response.status >= 300 && response.status < 400) {
    return { ok: false, reason: "The address redirects somewhere else. Paste the manifest address it redirects to." };
  }
  if (!response.ok) {
    return { ok: false, reason: `The addon answered with HTTP ${response.status}.` };
  }

  const body = await readCapped(response, MAX_MANIFEST_BYTES).catch(() => null);
  if (body === null) {
    return { ok: false, reason: "The addon's answer was too large or was cut off." };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return { ok: false, reason: "The address didn't return JSON, so it isn't a Stremio manifest." };
  }
  return parseManifest(parsed);
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
