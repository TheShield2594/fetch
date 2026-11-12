/**
 * The ids the website keys titles by.
 *
 * Columns and fields are still called `imdbId` because an IMDb id is what
 * nearly every title has, but a title without one travels under a prefixed id
 * instead, the same spelling the Fetch TV app and Stremio add-ons use for it:
 *
 * - `tt0111161` — an IMDb id, kept as it always was;
 * - `tmdb:603` — TMDB, which the website can still fetch metadata for;
 * - `kitsu:1376`, `mal:5114`, `anilist:5114`, `anidb:4563` — anime catalogs;
 * - `tvdb:…`, `trakt:…`, `simkl:…` — what else the TV can know a title by.
 *
 * One title has one key: a client that knows a title's IMDb id sends that, so
 * the rows it already has keep matching. Only the series id is stored for an
 * episode, never the Stremio video id (`kitsu:1376:5`), which names an episode.
 */

export const IMDB_ID_PATTERN = /^tt\d+$/;

export const CONTENT_ID_SOURCES = ["tmdb", "tvdb", "trakt", "simkl", "mal", "anilist", "anidb", "kitsu"] as const;

export type ContentIdSource = "imdb" | (typeof CONTENT_ID_SOURCES)[number];

const PREFIXED_ID_PATTERN = /^([a-z]+):(\d{1,12})$/;
const KNOWN_SOURCES = new Set<string>(CONTENT_ID_SOURCES);

/** What the API says when an id is neither an IMDb id nor a prefixed one. */
export const CONTENT_ID_ERROR = "imdbId must be an IMDb id (tt…) or a prefixed id such as tmdb:603 or kitsu:1376";

/**
 * The canonical spelling of [raw], or null when it isn't an id the website
 * keys titles by. `TMDB:603` becomes `tmdb:603`, `imdb:tt0111161` becomes
 * `tt0111161`, and surrounding space is dropped.
 */
export function normalizeContentId(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  let value = raw.trim();
  if (value.toLowerCase().startsWith("imdb:")) value = value.slice("imdb:".length);
  if (/^tt\d+$/i.test(value)) return `tt${value.slice(2)}`;
  const match = PREFIXED_ID_PATTERN.exec(value.toLowerCase());
  if (!match || !KNOWN_SOURCES.has(match[1]!)) return null;
  // Leading zeros would give one title two keys.
  return `${match[1]}:${String(Number(match[2]))}`;
}

export const isImdbId = (id: string): boolean => IMDB_ID_PATTERN.test(id);

/** Where [id] comes from and its value there, or null for an unrecognised id. */
export function parseContentId(id: string): { source: ContentIdSource; value: string } | null {
  const normalized = normalizeContentId(id);
  if (!normalized) return null;
  if (isImdbId(normalized)) return { source: "imdb", value: normalized };
  const [source, value] = normalized.split(":") as [ContentIdSource, string];
  return { source, value };
}

/** The TMDB id in a `tmdb:` content id, or null for any other id. */
export function tmdbIdOf(id: string): number | null {
  const parsed = parseContentId(id);
  return parsed?.source === "tmdb" ? Number(parsed.value) : null;
}

/**
 * The `ids` object a Trakt-style API (Trakt, Simkl, MDBList) takes for [id],
 * limited to the sources that service understands; null when it understands
 * none of them, in which case the push is skipped rather than sent with an id
 * the service would read as something else.
 */
export function externalIdsFor(
  id: string,
  accepted: readonly ContentIdSource[]
): Record<string, string | number> | null {
  const parsed = parseContentId(id);
  if (!parsed || !accepted.includes(parsed.source)) return null;
  if (parsed.source === "imdb") return { imdb: parsed.value };
  return { [parsed.source]: Number(parsed.value) };
}

export const TRAKT_ID_SOURCES: readonly ContentIdSource[] = ["imdb", "tmdb", "tvdb", "trakt"];
export const SIMKL_ID_SOURCES: readonly ContentIdSource[] = [
  "imdb",
  "tmdb",
  "tvdb",
  "simkl",
  "mal",
  "anilist",
  "anidb",
  "kitsu",
];
export const MDBLIST_ID_SOURCES: readonly ContentIdSource[] = ["imdb", "tmdb", "tvdb", "trakt"];
