/**
 * The pure half of the collection builder: making new collections, folders and
 * sources in the TV's own shape, describing a source in a line, and finding an
 * addon's base address. Kept out of the page so it can be tested without a DOM.
 */

import type { Collection, CollectionFolder, CollectionSource, TmdbSourceType } from "../api";

const newId = (): string =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

export const newCollection = (title = "New collection"): Collection => ({
  id: newId(),
  title,
  pinToTop: false,
  viewMode: "TABBED_GRID",
  showAllTab: true,
  folders: [newFolder()],
});

export const newFolder = (title = "New folder"): CollectionFolder => ({
  id: newId(),
  title,
  tileShape: "POSTER",
  hideTitle: false,
  sources: [],
});

/** TMDB's genre ids, which the TV's discover filters carry as a comma-separated string. */
export const TMDB_GENRES: Record<"MOVIE" | "TV", { id: number; name: string }[]> = {
  MOVIE: [
    { id: 28, name: "Action" }, { id: 12, name: "Adventure" }, { id: 16, name: "Animation" },
    { id: 35, name: "Comedy" }, { id: 80, name: "Crime" }, { id: 99, name: "Documentary" },
    { id: 18, name: "Drama" }, { id: 10751, name: "Family" }, { id: 14, name: "Fantasy" },
    { id: 36, name: "History" }, { id: 27, name: "Horror" }, { id: 10402, name: "Music" },
    { id: 9648, name: "Mystery" }, { id: 10749, name: "Romance" }, { id: 878, name: "Science Fiction" },
    { id: 53, name: "Thriller" }, { id: 10752, name: "War" }, { id: 37, name: "Western" },
  ],
  TV: [
    { id: 10759, name: "Action & Adventure" }, { id: 16, name: "Animation" }, { id: 35, name: "Comedy" },
    { id: 80, name: "Crime" }, { id: 99, name: "Documentary" }, { id: 18, name: "Drama" },
    { id: 10751, name: "Family" }, { id: 10762, name: "Kids" }, { id: 9648, name: "Mystery" },
    { id: 10764, name: "Reality" }, { id: 10765, name: "Sci-Fi & Fantasy" }, { id: 37, name: "Western" },
  ],
};

export const TMDB_SOURCE_LABELS: Record<TmdbSourceType, string> = {
  DISCOVER: "Discover (filters)",
  LIST: "TMDB list",
  COLLECTION: "Film collection",
  COMPANY: "Studio / company",
  NETWORK: "TV network",
  PERSON: "Actor",
  DIRECTOR: "Director",
};

const genreNames = (ids: string | null | undefined, mediaType: "MOVIE" | "TV"): string[] => {
  if (!ids) return [];
  const names = new Map(TMDB_GENRES[mediaType].map((genre) => [String(genre.id), genre.name]));
  return ids
    .split(/[,|]/)
    .map((id) => names.get(id.trim()))
    .filter((name): name is string => Boolean(name));
};

/** One line saying what a source fills a folder with. */
export function describeSource(source: CollectionSource): string {
  const media = source.mediaType === "TV" ? "series" : "movies";
  switch (source.provider) {
    case "fetch":
      return `${source.title ?? "Website list"} · ${source.mediaType ? media : "movies and series"}`;
    case "local":
      return `${source.title ?? "TV list"} · kept on the TV`;
    case "trakt":
      return `Trakt: ${source.title ?? `list ${source.traktListId}`} · ${media}`;
    case "tmdb": {
      const type = source.tmdbSourceType ?? "DISCOVER";
      if (type !== "DISCOVER") return `TMDB ${TMDB_SOURCE_LABELS[type].toLowerCase()}: ${source.title ?? source.tmdbId} · ${media}`;
      const filters = source.filters ?? {};
      const bits = [
        ...genreNames(filters.withGenres, source.mediaType === "TV" ? "TV" : "MOVIE"),
        filters.year ? String(filters.year) : null,
        filters.voteAverageGte ? `rated ${filters.voteAverageGte}+` : null,
      ].filter(Boolean);
      return `TMDB ${media}${bits.length ? `: ${bits.join(", ")}` : ""}`;
    }
    case "addon":
    default:
      return `${source.title ?? source.catalogId} · ${source.addonId} (${source.type})`;
  }
}

// ─── Addon catalogs (the server reads the addon; see `api.readAddonManifest`) ───

export type AddonCatalog = { type: string; id: string; name: string };
export type AddonManifest = { id: string; name: string; transportUrl: string; catalogs: AddonCatalog[] };

/** `https://x/manifest.json` and `https://x/` and `stremio://x/manifest.json` all name the addon at `https://x`. */
export function addonBaseUrl(raw: string): string {
  const trimmed = raw.trim().replace(/^stremio:\/\//i, "https://");
  return trimmed.replace(/\/manifest\.json(?:\?.*)?$/i, "").replace(/\/+$/, "");
}

// The website only learns an addon's address when someone types it into the
// builder; a source keeps just the addon's id, as on the TV. Remembering the
// address per id lets the preview work for that addon's sources afterwards.
const ADDON_URLS_KEY = "fetch:collection-addon-urls";

export function rememberAddonUrl(addonId: string, base: string): void {
  try {
    const current = JSON.parse(localStorage.getItem(ADDON_URLS_KEY) ?? "{}") as Record<string, string>;
    current[addonId] = base;
    localStorage.setItem(ADDON_URLS_KEY, JSON.stringify(current));
  } catch {
    /* storage unavailable — the preview just asks for the address again */
  }
}

export function rememberedAddonUrl(addonId: string | undefined): string | null {
  if (!addonId) return null;
  try {
    const current = JSON.parse(localStorage.getItem(ADDON_URLS_KEY) ?? "{}") as Record<string, string>;
    return current[addonId] ?? null;
  } catch {
    return null;
  }
}
