/**
 * Validation for a profile's collections document.
 *
 * The document is the TV's own serialized form (`CollectionsDataStore`), stored
 * as given: the checks here make sure every collection, folder and source has
 * what the TV needs to read it back, and nothing more. Unknown fields are kept,
 * so a collection made on the TV with a field this version doesn't know about
 * still comes back unchanged.
 *
 * Sources are tagged by `provider`:
 *   - `addon`  — an addon catalog: `addonId`, `type`, `catalogId`, optional `genre`, and
 *                optional `addonUrl`, the addon's address, for a TV that doesn't have it installed
 *   - `tmdb`   — `tmdbSourceType`, and `tmdbId` or `filters` depending on it
 *   - `trakt`  — `traktListId`
 *   - `fetch`  — one of this website's own lists: `listId`
 *   - `local`  — a list kept on one TV with no account (`listId`); only that TV can fill it,
 *                so the website keeps it as given and doesn't preview it
 */

export const MAX_COLLECTIONS = 50;
export const MAX_FOLDERS_PER_COLLECTION = 50;
export const MAX_SOURCES_PER_FOLDER = 30;
const MAX_TEXT = 300;

export const SOURCE_PROVIDERS = ["addon", "tmdb", "trakt", "fetch", "local"] as const;
export const TMDB_SOURCE_TYPES = ["LIST", "COLLECTION", "COMPANY", "NETWORK", "DISCOVER", "PERSON", "DIRECTOR"] as const;
export const VIEW_MODES = ["TABBED_GRID", "ROWS", "FOLLOW_LAYOUT"] as const;

export type CollectionSourceJson = Record<string, unknown> & { provider?: string };
export type CollectionFolderJson = Record<string, unknown> & {
  id: string;
  title: string;
  sources?: CollectionSourceJson[];
};
export type CollectionJson = Record<string, unknown> & { id: string; title: string; folders?: CollectionFolderJson[] };

type Result<T> = { ok: true; value: T } | { ok: false; error: string };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const nonBlank = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0 && value.length <= MAX_TEXT;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const validateSource = (source: unknown, where: string): string | null => {
  if (!isRecord(source)) return `${where} must be an object`;
  // The TV writes `provider` on every source it saves and reads a missing one as an addon.
  const provider = typeof source.provider === "string" ? source.provider.toLowerCase() : "addon";
  switch (provider) {
    case "addon":
      if (!nonBlank(source.addonId) || !nonBlank(source.type) || !nonBlank(source.catalogId)) {
        return `${where}: an addon source needs addonId, type and catalogId`;
      }
      // A TV fetches from this address, so it can only be a web one.
      if (
        source.addonUrl !== undefined &&
        source.addonUrl !== null &&
        !(typeof source.addonUrl === "string" && source.addonUrl.length <= 2048 && /^https?:\/\/\S+$/i.test(source.addonUrl))
      ) {
        return `${where}: addonUrl must be an http(s) address`;
      }
      return null;
    case "tmdb": {
      const sourceType = typeof source.tmdbSourceType === "string" ? source.tmdbSourceType.toUpperCase() : "";
      if (!(TMDB_SOURCE_TYPES as readonly string[]).includes(sourceType)) {
        return `${where}: tmdbSourceType must be one of ${TMDB_SOURCE_TYPES.join(", ")}`;
      }
      if (sourceType !== "DISCOVER" && !(Number.isInteger(source.tmdbId) && (source.tmdbId as number) > 0)) {
        return `${where}: a TMDB ${sourceType.toLowerCase()} source needs a tmdbId`;
      }
      if (source.filters !== undefined && source.filters !== null && !isRecord(source.filters)) {
        return `${where}: filters must be an object`;
      }
      return null;
    }
    case "trakt":
      if (!(Number.isInteger(source.traktListId) && (source.traktListId as number) > 0)) {
        return `${where}: a Trakt source needs a traktListId`;
      }
      return null;
    case "fetch":
      if (typeof source.listId !== "string" || !UUID_PATTERN.test(source.listId)) {
        return `${where}: a website list source needs a listId`;
      }
      return null;
    case "local":
      if (!nonBlank(source.listId)) {
        return `${where}: a TV list source needs a listId`;
      }
      return null;
    default:
      return `${where}: provider must be one of ${SOURCE_PROVIDERS.join(", ")}`;
  }
};

export const validateCollections = (raw: unknown): Result<CollectionJson[]> => {
  if (!Array.isArray(raw)) return { ok: false, error: "collections must be an array" };
  if (raw.length > MAX_COLLECTIONS) return { ok: false, error: `At most ${MAX_COLLECTIONS} collections` };

  const collectionIds = new Set<string>();
  for (const [index, collection] of raw.entries()) {
    const where = `collections[${index}]`;
    if (!isRecord(collection)) return { ok: false, error: `${where} must be an object` };
    if (!nonBlank(collection.id) || !nonBlank(collection.title)) {
      return { ok: false, error: `${where} needs an id and a title` };
    }
    if (collectionIds.has(collection.id)) return { ok: false, error: `${where}: duplicate id ${collection.id}` };
    collectionIds.add(collection.id);

    if (collection.viewMode !== undefined && !(VIEW_MODES as readonly unknown[]).includes(collection.viewMode)) {
      return { ok: false, error: `${where}: viewMode must be one of ${VIEW_MODES.join(", ")}` };
    }

    const folders = collection.folders ?? [];
    if (!Array.isArray(folders)) return { ok: false, error: `${where}.folders must be an array` };
    if (folders.length > MAX_FOLDERS_PER_COLLECTION) {
      return { ok: false, error: `${where}: at most ${MAX_FOLDERS_PER_COLLECTION} folders` };
    }

    const folderIds = new Set<string>();
    for (const [folderIndex, folder] of folders.entries()) {
      const folderWhere = `${where}.folders[${folderIndex}]`;
      if (!isRecord(folder)) return { ok: false, error: `${folderWhere} must be an object` };
      if (!nonBlank(folder.id) || !nonBlank(folder.title)) {
        return { ok: false, error: `${folderWhere} needs an id and a title` };
      }
      if (folderIds.has(folder.id)) return { ok: false, error: `${folderWhere}: duplicate id ${folder.id}` };
      folderIds.add(folder.id);

      // `catalogSources` is the TV's older form, addon catalogs only; it still
      // reads it when `sources` is absent.
      for (const key of ["sources", "catalogSources"] as const) {
        const sources = folder[key];
        if (sources === undefined || sources === null) continue;
        if (!Array.isArray(sources)) return { ok: false, error: `${folderWhere}.${key} must be an array` };
        if (sources.length > MAX_SOURCES_PER_FOLDER) {
          return { ok: false, error: `${folderWhere}: at most ${MAX_SOURCES_PER_FOLDER} sources` };
        }
        for (const [sourceIndex, source] of sources.entries()) {
          const error = validateSource(
            key === "catalogSources" && isRecord(source) ? { ...source, provider: "addon" } : source,
            `${folderWhere}.${key}[${sourceIndex}]`
          );
          if (error) return { ok: false, error };
        }
      }
    }
  }

  return { ok: true, value: raw as CollectionJson[] };
};
