import { guardedFetch, type OutboundScope, readBodyText } from "./ssrf.js";
import type { ManifestFetcher } from "./addon-manifest.js";
import type { StremioMetaPreview } from "./types.js";

/**
 * The first page of a Stremio addon's catalog, fetched by the server for the
 * website's collection builder.
 *
 * The browser used to ask the addon itself, which the production
 * Content-Security-Policy refuses: `connect-src` names only the origins this
 * deployment talks to, and a community addon can be on any host. The server
 * has no such limit, and fetches under the same outbound policy as installing
 * an addon (`addon-manifest.ts`): pinned, no redirects, and the admin's
 * profiles alone may reach the LAN. Like the manifest, the addon's address can
 * carry its credentials, so it never appears in an error or a log line.
 */

const MAX_CATALOG_BYTES = 2 * 1024 * 1024;
const CATALOG_TIMEOUT_MS = 8_000;

export const CATALOG_BLOCKED_REASON = "That address isn't one this server may fetch.";
export const CATALOG_UNREACHABLE_REASON = "The addon didn't answer. Check the address and that the addon is running.";
export const CATALOG_REJECTED_REASON = "The addon answered, but not with a catalog.";

export type AddonCatalogQuery = { type: string; catalogId: string; genre?: string | null };

export type CatalogResult =
  | { ok: true; metas: StremioMetaPreview[] }
  | { ok: false; reason: string; detail?: string };

/**
 * The addon's base address from what a source or the builder holds: a base, a
 * manifest URL, or the `stremio://` form an Install button links to. Null when
 * it isn't an http(s) address.
 */
export const addonBase = (raw: string): string | null => {
  const trimmed = raw.trim().replace(/^stremio:\/\//i, "https://");
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  url.hash = "";
  url.search = "";
  return url.toString().replace(/\/manifest\.json$/i, "").replace(/\/+$/, "");
};

export const addonCatalogUrl = (base: string, { type, catalogId, genre }: AddonCatalogQuery): string => {
  const extra = genre ? `/genre=${encodeURIComponent(genre)}` : "";
  return `${base}/catalog/${encodeURIComponent(type)}/${encodeURIComponent(catalogId)}${extra}.json`;
};

const readMetas = (value: unknown, limit: number): StremioMetaPreview[] | null => {
  if (typeof value !== "object" || value === null) return null;
  const metas = (value as { metas?: unknown }).metas;
  if (!Array.isArray(metas)) return null;
  const out: StremioMetaPreview[] = [];
  for (const entry of metas) {
    if (out.length >= limit) break;
    const meta = entry as { id?: unknown; type?: unknown; name?: unknown; poster?: unknown } | null;
    if (typeof meta?.id !== "string" || typeof meta.name !== "string") continue;
    const poster = typeof meta.poster === "string" && /^https?:\/\//i.test(meta.poster) ? meta.poster : undefined;
    out.push({
      id: meta.id,
      type: meta.type === "series" ? "series" : "movie",
      name: meta.name,
      ...(poster ? { poster } : {}),
    });
  }
  return out;
};

export const fetchAddonCatalog = async (
  base: string,
  query: AddonCatalogQuery,
  scope: OutboundScope,
  limit: number,
  { fetcher = guardedFetch }: { fetcher?: ManifestFetcher } = {}
): Promise<CatalogResult> => {
  let response: Response | null;
  try {
    response = await fetcher(addonCatalogUrl(base, query), scope, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(CATALOG_TIMEOUT_MS),
    });
  } catch (error) {
    return {
      ok: false,
      reason: CATALOG_UNREACHABLE_REASON,
      detail: error instanceof Error ? (error.cause instanceof Error ? error.cause.message : error.message) : "fetch failed",
    };
  }
  if (!response) return { ok: false, reason: CATALOG_BLOCKED_REASON };
  if (!response.ok) {
    await response.body?.cancel().catch(() => undefined);
    return { ok: false, reason: CATALOG_REJECTED_REASON, detail: `HTTP ${response.status}` };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(await readBodyText(response, MAX_CATALOG_BYTES));
  } catch {
    return { ok: false, reason: CATALOG_REJECTED_REASON, detail: "the body was too large, cut off, or not JSON" };
  }
  const metas = readMetas(parsed, limit);
  return metas ? { ok: true, metas } : { ok: false, reason: CATALOG_REJECTED_REASON, detail: "no metas array" };
};
