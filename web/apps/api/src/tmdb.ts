import { MetadataType } from "@prisma/client";
import {
  externalIdsCache,
  searchCache,
  searchLookups,
  EXTERNAL_IDS_MISS_CACHE_TTL_MS,
  SEARCH_MISS_CACHE_TTL_MS,
} from "./lib/cache.js";
import { mapWithConcurrency } from "./lib/concurrency.js";
import { fetchWithPolicy } from "./lib/http.js";

type TmdbMediaType = "movie" | "tv";

type TmdbSearchResult = {
  id: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  overview?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  release_date?: string;
  first_air_date?: string;
  media_type?: string;
  genre_ids?: number[];
  vote_average?: number;
  vote_count?: number;
};

type TmdbDetailsResult = TmdbSearchResult & {
  genres?: { id: number; name: string }[];
  number_of_seasons?: number;
  number_of_episodes?: number;
  runtime?: number | null;
  episode_run_time?: number[];
  status?: string;
  networks?: { id: number; name: string; origin_country: string }[];
  production_companies?: { id: number; name: string }[];
  seasons?: {
    id: number;
    name: string;
    season_number: number;
    episode_count: number;
    air_date?: string | null;
    poster_path?: string | null;
  }[];
  // append_to_response: release_dates (movie)
  release_dates?: {
    results: {
      iso_3166_1: string;
      release_dates: { certification: string; type: number }[];
    }[];
  };
  // append_to_response: content_ratings (tv)
  content_ratings?: {
    results: { iso_3166_1: string; rating: string }[];
  };
};

type TmdbExternalIds = {
  imdb_id?: string | null;
};

type TmdbSearchResponse = {
  page?: number;
  total_pages?: number;
  results?: TmdbSearchResult[];
};

type TmdbPersonSearchResponse = {
  results?: {
    id: number;
    name?: string;
    known_for?: TmdbSearchResult[];
  }[];
};

type TmdbDetailsResponse = TmdbDetailsResult;

type TmdbFindResponse = {
  movie_results?: TmdbSearchResult[];
  tv_results?: TmdbSearchResult[];
};

type TmdbSeasonResponse = {
  episodes?: {
    episode_number: number;
    name: string;
    air_date?: string | null;
    still_path?: string | null;
    runtime?: number | null;
    overview?: string | null;
  }[];
};

type TmdbWatchProviderEntry = {
  provider_id: number;
  provider_name: string;
  logo_path?: string | null;
};

type TmdbWatchProvidersResponse = {
  results?: Record<
    string,
    {
      link?: string;
      flatrate?: TmdbWatchProviderEntry[];
      free?: TmdbWatchProviderEntry[];
      ads?: TmdbWatchProviderEntry[];
    }
  >;
};

type TmdbCastEntry = {
  id: number;
  name: string;
  character: string;
  profile_path?: string | null;
  order: number;
};

type TmdbCrewEntry = {
  id: number;
  name: string;
  job: string;
  department?: string;
};

type TmdbCreditsResponse = {
  cast?: TmdbCastEntry[];
  crew?: TmdbCrewEntry[];
};

type TmdbTvWithCreditsResponse = {
  created_by?: { id: number; name: string }[];
  credits?: TmdbCreditsResponse;
};

export type CastMember = {
  name: string;
  character: string;
  photo: string | null;
  order: number;
};

export type Credits = {
  cast: CastMember[];
  director: string | null;
};

export type SeasonInfo = {
  seasonNumber: number;
  name: string;
  episodeCount: number;
  airYear: number | null;
  poster: string | null;
};

export type EpisodeInfo = {
  episodeNumber: number;
  name: string;
  airDate: string | null;
  still: string | null;
  runtime: number | null;
  /** The episode's synopsis, or null when TMDB has none. A spoiler for anyone who hasn't seen it. */
  overview: string | null;
};

export type WatchProvider = {
  id: number;
  name: string;
  logo: string | null;
};

export type WatchProviders = {
  link: string | null;
  flatrate: WatchProvider[];
  free: WatchProvider[];
  ads: WatchProvider[];
};

export type ShowDetails = {
  /** `still` is the full image URL, resolved here like every other image this client returns. */
  nextEpisodeToAir: { season_number: number; episode_number: number; name: string; air_date: string; overview?: string; still?: string | null } | null;
  lastEpisodeToAir: { season_number: number; episode_number: number; name: string; air_date: string } | null;
  status: string | null;
};

/** The TV's `TmdbCollectionFilters`, as the collection JSON carries them. */
export type TmdbCollectionFilters = {
  withGenres?: string | null;
  withoutGenres?: string | null;
  releaseDateGte?: string | null;
  releaseDateLte?: string | null;
  voteAverageGte?: number | null;
  voteAverageLte?: number | null;
  voteCountGte?: number | null;
  withOriginalLanguage?: string | null;
  withOriginCountry?: string | null;
  withKeywords?: string | null;
  withoutKeywords?: string | null;
  withCompanies?: string | null;
  withoutCompanies?: string | null;
  withNetworks?: string | null;
  year?: number | null;
  watchRegion?: string | null;
  withWatchProviders?: string | null;
  withoutWatchProviders?: string | null;
};

export type TmdbCollectionSourceQuery = {
  sourceType: "LIST" | "COLLECTION" | "COMPANY" | "NETWORK" | "DISCOVER" | "PERSON" | "DIRECTOR";
  tmdbId?: number | null;
  mediaType?: "MOVIE" | "TV" | null;
  sortBy?: string | null;
  filters?: TmdbCollectionFilters | null;
};

/** TMDB's `/discover` parameters for a collection's filters; movies and TV name the dates differently. */
export const discoverParams = (mediaType: "movie" | "tv", filters: TmdbCollectionFilters): Record<string, string> => {
  const dateKey = mediaType === "movie" ? "primary_release_date" : "first_air_date";
  const pairs: [string, string | number | null | undefined][] = [
    ["with_genres", filters.withGenres],
    ["without_genres", filters.withoutGenres],
    [`${dateKey}.gte`, filters.releaseDateGte],
    [`${dateKey}.lte`, filters.releaseDateLte],
    ["vote_average.gte", filters.voteAverageGte],
    ["vote_average.lte", filters.voteAverageLte],
    ["vote_count.gte", filters.voteCountGte],
    ["with_original_language", filters.withOriginalLanguage],
    ["with_origin_country", filters.withOriginCountry],
    ["with_keywords", filters.withKeywords],
    ["without_keywords", filters.withoutKeywords],
    ["with_companies", filters.withCompanies],
    ["without_companies", filters.withoutCompanies],
    ["with_networks", filters.withNetworks],
    [mediaType === "movie" ? "primary_release_year" : "first_air_date_year", filters.year],
    ["watch_region", filters.watchRegion],
    ["with_watch_providers", filters.withWatchProviders],
    ["without_watch_providers", filters.withoutWatchProviders],
  ];
  const params: Record<string, string> = {};
  for (const [key, value] of pairs) {
    if (value === null || value === undefined || value === "") continue;
    params[key] = String(value);
  }
  return params;
};

/**
 * The genres the web client's Search filter offers, by name, with the TMDB id
 * each one has for films and for shows. TMDB keeps two separate genre lists and
 * they do not line up: shows have no Horror, Romance or Thriller, and fold
 * Action and Adventure into one. A genre with no id for a media type matches
 * nothing of that type, which is the honest answer — a TV "Horror" filter that
 * quietly returned every show would be worse.
 */
export const BROWSE_GENRES: Record<string, { movie?: number; tv?: number }> = {
  Action: { movie: 28, tv: 10759 },
  Adventure: { movie: 12, tv: 10759 },
  Animation: { movie: 16, tv: 16 },
  Comedy: { movie: 35, tv: 35 },
  Crime: { movie: 80, tv: 80 },
  Documentary: { movie: 99, tv: 99 },
  Drama: { movie: 18, tv: 18 },
  Family: { movie: 10751, tv: 10751 },
  Fantasy: { movie: 14, tv: 10765 },
  History: { movie: 36 },
  Horror: { movie: 27 },
  Music: { movie: 10402 },
  Mystery: { movie: 9648, tv: 9648 },
  Romance: { movie: 10749 },
  "Sci-Fi": { movie: 878, tv: 10765 },
  Thriller: { movie: 53 },
  War: { movie: 10752, tv: 10768 },
  Western: { movie: 37, tv: 37 },
};

export type BrowseSort = "relevance" | "rating" | "year_desc" | "year_asc" | "title";

export type BrowseQuery = {
  /** A text query. Without one, the browse is a TMDB `/discover` over the filters. */
  query?: string | null;
  genre?: string | null;
  yearMin?: number | null;
  yearMax?: number | null;
  ratingMin?: number | null;
  sort?: BrowseSort | null;
  /** 1-based. For a text query it is the TMDB search page to start scanning from. */
  page?: number;
};

export type BrowsePage = { results: MetadataPayload[]; page: number; nextPage: number | null };

/** Page size the client asks for; also how many candidates a filtered scan collects. */
const BROWSE_PAGE_RESULTS = 20;

/**
 * How many TMDB search pages one filtered call will scan for matches before it
 * answers with what it has. A filter like "Horror, 1980–1989" on a common word
 * can leave one match in a hundred; scanning forever would hold the request
 * open for that, and the client can always ask for the next stretch.
 */
const BROWSE_SCAN_PAGES = 5;

/** TMDB refuses `page` past 500 on every list endpoint. */
const TMDB_MAX_PAGE = 500;

/** Below this many votes a 9.4 average is three people's opinion, not a rating. */
const RATING_MIN_VOTES = 100;

/** Search's sort options as TMDB `/discover` `sort_by` values. Movies and TV name their fields differently. */
export const discoverSort = (mediaType: "movie" | "tv", sort: BrowseSort): string => {
  const date = mediaType === "movie" ? "primary_release_date" : "first_air_date";
  switch (sort) {
    case "rating":
      return "vote_average.desc";
    case "year_desc":
      return `${date}.desc`;
    case "year_asc":
      return `${date}.asc`;
    case "title":
      return mediaType === "movie" ? "title.asc" : "name.asc";
    case "relevance":
    default:
      // Without a query there is nothing to be relevant to; popularity is
      // what TMDB and every storefront fall back to.
      return "popularity.desc";
  }
};

export type MetadataPayload = {
  imdbId: string;
  type: MetadataType;
  tmdbId: number | null;
  name: string;
  year: number | null;
  poster: string | null;
  background: string | null;
  description: string | null;
  genres: string[];
  rating: number | null;
  voteCount: number | null;
  totalSeasons: number | null;
  totalEpisodes: number | null;
  runtime: number | null;
  certification: string | null;
  status: string | null;
  network: string | null;
  releaseDate: string | null;
};

// Standard TMDB genre IDs (movie + TV combined)
const TMDB_GENRE_MAP: Record<number, string> = {
  28: "Action", 12: "Adventure", 16: "Animation", 35: "Comedy", 80: "Crime",
  99: "Documentary", 18: "Drama", 10751: "Family", 14: "Fantasy", 36: "History",
  27: "Horror", 10402: "Music", 9648: "Mystery", 10749: "Romance", 878: "Sci-Fi",
  10770: "TV Movie", 53: "Thriller", 10752: "War", 37: "Western",
  10759: "Action & Adventure", 10762: "Kids", 10763: "News", 10764: "Reality",
  10765: "Sci-Fi & Fantasy", 10766: "Soap", 10767: "Talk", 10768: "War & Politics",
};

// Common streaming provider IDs on TMDB
export const STREAMING_PROVIDERS: Record<string, { id: number; name: string }> = {
  netflix: { id: 8, name: "Netflix" },
  amazon: { id: 9, name: "Amazon Prime Video" },
  disney: { id: 337, name: "Disney+" },
  apple: { id: 350, name: "Apple TV+" },
  hulu: { id: 15, name: "Hulu" },
  max: { id: 1899, name: "Max" },
  paramount: { id: 531, name: "Paramount+" },
  peacock: { id: 386, name: "Peacock" },
  crunchyroll: { id: 283, name: "Crunchyroll" },
};

const REQUEST_TIMEOUT_MS = 10_000;

/**
 * How many TMDB requests this process keeps in flight at once. Every catalog is
 * a list request plus one external-id lookup per result, and a Stremio home
 * screen asks for up to 20 catalogs at a time, so without a cap a cold cache
 * turns one refresh into several hundred simultaneous requests — which is how a
 * 429 is earned. Well under TMDB's own ~50/s allowance, and the queue behind it
 * costs latency rather than results.
 */
const TMDB_CONCURRENCY = 8;

/**
 * Within a single catalog, how many external-id lookups run together. Kept
 * below the host cap so one catalog can't monopolise the budget while twenty
 * others wait.
 */
const IMDB_LOOKUP_CONCURRENCY = 5;

/** TMDB list endpoints page at 20; anything past that is never rendered. */
const MAX_CATALOG_RESULTS = 20;

/**
 * How many usable results a search tries to come back with. The same number the
 * `/search` route caps its response at, so a search that reaches it has lost
 * nothing to the trimming below.
 */
const SEARCH_TARGET_RESULTS = 20;

/**
 * How far a search will page for them. Search is the one place where the
 * catalog endpoints' "page one is the answer" assumption does not hold: every
 * result whose IMDb id TMDB does not know is dropped, so a page of twenty can
 * leave six, and a title sitting at rank 21 was never a candidate at all. That
 * was the shape of the complaint this exists to fix — a title findable on
 * themoviedb.org that Fetch answered with nothing.
 *
 * Three is a ceiling, not a cost: pages are fetched one at a time and only
 * while the target is still short, so a search whose first page resolves
 * cleanly still costs exactly one list request.
 */
const SEARCH_MAX_PAGES = 3;

/**
 * When a query turns out to be a person rather than a title, how many of the
 * people it matched contribute their known-for titles. Three covers the
 * "which Chris" problem without turning one search into a filmography.
 */
const SEARCH_PEOPLE_CONSIDERED = 3;

const IMDB_ID_PATTERN = /^tt\d{6,10}$/i;
const TMDB_URL_PATTERN = /(?:^|\/\/)(?:www\.)?themoviedb\.org\/(movie|tv)\/(\d+)/i;

/**
 * Case, accents and punctuation removed, so "wall-e" finds "WALL·E" and
 * "pokemon" finds "Pokémon" — the difference between the two is exactly the
 * kind of thing a person types and does not expect to matter.
 */
const normaliseTitle = (value: string): string =>
  value
    .normalize("NFKD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();

/**
 * How closely one title answers the query, as a coarse tier: exact hit >
 * prefix > word-start > substring > no hit.
 *
 * Deliberately the same tiers as `mergeByRelevance` in the web client, which
 * interleaves the movie and series halves of an "All" search. Two different
 * notions of "good match" on the two sides of one result list is how a search
 * ends up ordered by neither.
 */
const titleMatchScore = (title: string | undefined, normalisedQuery: string): number => {
  if (!title || !normalisedQuery) return 0;
  const name = normaliseTitle(title);
  if (!name) return 0;
  if (name === normalisedQuery) return 4;
  if (name.startsWith(`${normalisedQuery} `)) return 3;
  if (name.includes(` ${normalisedQuery} `) || name.endsWith(` ${normalisedQuery}`)) return 2;
  if (name.includes(normalisedQuery)) return 1;
  return 0;
};

/** What the Home folders builder can search TMDB for by name. */
export type TmdbEntityKind = "person" | "company" | "collection";
export const TMDB_ENTITY_KINDS: readonly TmdbEntityKind[] = ["person", "company", "collection"];
export type TmdbEntity = { id: number; name: string; image: string | null; detail: string | null };

export class TmdbClient {
  private static readonly baseUrl = "https://api.themoviedb.org/3";
  private static readonly imageBaseUrl = "https://image.tmdb.org/t/p/w500";
  private static readonly profileBaseUrl = "https://image.tmdb.org/t/p/w185";
  private static readonly logoBaseUrl = "https://image.tmdb.org/t/p/w92";

  private readonly language: string;

  private constructor(private readonly apiKey: string, language?: string) {
    this.language = language ?? "en-US";
  }

  getLanguage(): string {
    return this.language;
  }

  static fromKey(apiKey: string, language?: string) {
    const trimmed = apiKey.trim();
    if (!trimmed) {
      throw new Error("TMDB API key is not configured");
    }

    return new TmdbClient(trimmed, language);
  }

  /**
   * The shape every list endpoint here shares: TMDB answers with a page of
   * results carrying its own ids, and Fetch keys everything by IMDb id, so
   * each result needs an `/external_ids` lookup before it is usable.
   *
   * That lookup is the fan-out — one list request becomes up to 21 — so it runs
   * under a concurrency limit rather than `Promise.all`. Results whose id TMDB
   * doesn't know an IMDb id for are dropped: an entry Fetch cannot key is
   * one it cannot store, rate or mark watched.
   *
   * A lookup that *fails* is dropped the same way rather than failing the
   * catalog. `Promise.all` semantics meant one refused `/external_ids` call —
   * after its retries, so one that was genuinely not coming back — turned
   * nineteen usable results into an empty row. A real outage still surfaces,
   * because the list request itself fails before any of this runs.
   */
  private async withImdbIds(
    results: TmdbSearchResult[],
    classify: (result: TmdbSearchResult) => { mediaType: TmdbMediaType; type: MetadataType } | null
  ): Promise<MetadataPayload[]> {
    const withImdb = await mapWithConcurrency(
      results.slice(0, MAX_CATALOG_RESULTS),
      IMDB_LOOKUP_CONCURRENCY,
      async (result) => {
        if (!result.id) return null;

        const kind = classify(result);
        if (!kind) return null;

        const imdbId = await this.getImdbId(kind.mediaType, result.id).catch(() => null);
        if (!imdbId) return null;

        return this.toMetadataPayload(kind.type, result, imdbId);
      }
    );

    return withImdb.filter((item): item is MetadataPayload => item !== null);
  }

  /** The classifier for the endpoints whose media type is fixed by the caller. */
  private asType(type: MetadataType): () => { mediaType: TmdbMediaType; type: MetadataType } {
    const mediaType = this.toMediaType(type);
    return () => ({ mediaType, type });
  }

  async search(type: MetadataType, query: string): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    return this.cachedSearch(`${mediaType}:${normaliseTitle(query)}`, async () => {
      const direct = await this.lookupByReference(query, [type]);
      if (direct) return direct;

      return this.runSearch(`/search/${mediaType}`, query, [mediaType], this.asType(type));
    });
  }

  async searchMulti(query: string): Promise<MetadataPayload[]> {
    return this.cachedSearch(`multi:${normaliseTitle(query)}`, async () => {
      const direct = await this.lookupByReference(query, [MetadataType.movie, MetadataType.series]);
      if (direct) return direct;

      return this.runSearch("/search/multi", query, ["movie", "tv"], (result) => {
        if (result.media_type !== "movie" && result.media_type !== "tv") return null;
        const mediaType = result.media_type;
        return { mediaType, type: mediaType === "movie" ? MetadataType.movie : MetadataType.series };
      });
    });
  }

  /**
   * A search, paged until it has enough to show.
   *
   * The catalog endpoints take one page and are done, because every result on
   * it is one TMDB chose to put there. A search is not like that: its results
   * are ranked by popularity rather than by how well they answer the query, and
   * this client then drops every one whose IMDb id TMDB does not know — so the
   * twenty candidates on page one routinely become a handful of results, and
   * the title actually being searched for can be sitting at rank 21 having
   * never been looked at.
   *
   * So each round takes the best of what has arrived so far, resolves as many
   * as are still missing from the target, and asks for another page only if it
   * is still short and TMDB says there is one. The ranking runs before the
   * resolution on purpose: `/external_ids` is the expensive half, and spending
   * it on the twenty most relevant candidates rather than the twenty most
   * popular ones is the whole point.
   */
  private async runSearch(
    path: string,
    query: string,
    mediaTypes: TmdbMediaType[],
    classify: (result: TmdbSearchResult) => { mediaType: TmdbMediaType; type: MetadataType } | null
  ): Promise<MetadataPayload[]> {
    const normalisedQuery = normaliseTitle(query);
    const seen = new Set<number>();
    const attempted = new Set<number>();
    const candidates: TmdbSearchResult[] = [];
    const found: MetadataPayload[] = [];

    const collect = (results: TmdbSearchResult[] | undefined) => {
      for (const result of results ?? []) {
        if (!result.id || seen.has(result.id)) continue;
        seen.add(result.id);
        candidates.push(result);
      }
    };

    /**
     * Resolves the best candidates not yet tried, until the target is reached
     * or there are none left.
     *
     * One pass is not enough, because a pass is exactly the thing that comes up
     * short: a batch sized to the shortfall loses some of itself to results
     * TMDB knows no IMDb id for. Trying the next-best candidates is only
     * possible while there is a next page to trigger it — so on the last page,
     * at the page cap, and after the person fallback, the search stopped with
     * usable candidates it had never looked at.
     *
     * Terminates because a non-empty batch always marks at least one more
     * candidate attempted, and the pool is finite.
     */
    const resolveBest = async () => {
      while (found.length < SEARCH_TARGET_RESULTS) {
        const batch = this.rankCandidates(candidates, normalisedQuery)
          .filter((result) => !attempted.has(result.id))
          .slice(0, SEARCH_TARGET_RESULTS - found.length);
        if (batch.length === 0) return;

        for (const result of batch) attempted.add(result.id);
        found.push(...(await this.withImdbIds(batch, classify)));
      }
    };

    for (let page = 1; page <= SEARCH_MAX_PAGES; page++) {
      const response = await this.request<TmdbSearchResponse>(path, { query, page: String(page) });
      collect(response.results);
      await resolveBest();

      if (found.length >= SEARCH_TARGET_RESULTS || page >= (response.total_pages ?? 1)) break;
    }

    // A query that matched no title at all is usually a person — the search box
    // is the only one on the page, and "villeneuve" is a reasonable thing to
    // type into it. TMDB answers that from `/search/person`; Fetch has
    // nowhere to put a person, so what it borrows is their known-for titles.
    if (
      found.length < SEARCH_TARGET_RESULTS &&
      !found.some((result) => titleMatchScore(result.name, normalisedQuery) >= 2)
    ) {
      collect(await this.knownForTitles(query, mediaTypes));
      await resolveBest();
    }

    // Ranked once more at the end: a page-two exact match was resolved after a
    // page-one near-miss, and the order results are found in is not the order
    // they should be read in.
    return found
      .map((result, index) => ({ result, index, score: titleMatchScore(result.name, normalisedQuery) }))
      .sort((a, b) => b.score - a.score || a.index - b.index)
      .map((entry) => entry.result);
  }

  /**
   * Best match first, with TMDB's own order — popularity, broadly — as the
   * tiebreak inside a tier, so an exact hit outranks a more popular near-miss
   * without the ranking having to invent an opinion about anything else.
   */
  private rankCandidates(results: TmdbSearchResult[], normalisedQuery: string): TmdbSearchResult[] {
    const score = (result: TmdbSearchResult) =>
      Math.max(
        titleMatchScore(result.title, normalisedQuery),
        titleMatchScore(result.name, normalisedQuery),
        titleMatchScore(result.original_title, normalisedQuery),
        titleMatchScore(result.original_name, normalisedQuery)
      );

    return results
      .map((result, index) => ({ result, index, score: score(result) }))
      .sort((a, b) => b.score - a.score || a.index - b.index)
      .map((entry) => entry.result);
  }

  /** The known-for titles of the people a query matched, best-effort. */
  private async knownForTitles(query: string, mediaTypes: TmdbMediaType[]): Promise<TmdbSearchResult[]> {
    try {
      const response = await this.request<TmdbPersonSearchResponse>("/search/person", { query });
      const titles: TmdbSearchResult[] = [];
      for (const person of (response.results ?? []).slice(0, SEARCH_PEOPLE_CONSIDERED)) {
        for (const credit of person.known_for ?? []) {
          if (!credit.id || !credit.media_type) continue;
          if (!mediaTypes.includes(credit.media_type as TmdbMediaType)) continue;
          titles.push(credit);
        }
      }
      return titles;
    } catch {
      // A search that found titles must not fail because the person lookup did.
      return [];
    }
  }

  /**
   * A pasted IMDb id (`tt0110912`) or themoviedb.org link, resolved to the one
   * title it names. Both are things people paste into a search box when the
   * name alone is not finding it, and both previously matched nothing —
   * `/search/movie?query=tt0110912` is a search for that literal string.
   *
   * Returns null when the query is an ordinary one, so the caller searches.
   */
  private async lookupByReference(query: string, types: MetadataType[]): Promise<MetadataPayload[] | null> {
    const trimmed = query.trim();

    if (IMDB_ID_PATTERN.test(trimmed)) {
      const imdbId = trimmed.toLowerCase();
      const response = await this.request<TmdbFindResponse>(`/find/${encodeURIComponent(imdbId)}`, {
        external_source: "imdb_id",
      });

      const found: MetadataPayload[] = [];
      for (const type of types) {
        const result =
          type === MetadataType.movie ? response.movie_results?.[0] : response.tv_results?.[0];
        if (result?.id) found.push(this.toMetadataPayload(type, result, imdbId));
      }
      return found;
    }

    const [, linkMediaType, linkTmdbId] = TMDB_URL_PATTERN.exec(trimmed) ?? [];
    // Both groups are required by the pattern, so having the first is having both.
    if (linkMediaType !== undefined && linkTmdbId !== undefined) {
      const mediaType = linkMediaType.toLowerCase() as TmdbMediaType;
      const type = mediaType === "movie" ? MetadataType.movie : MetadataType.series;
      if (!types.includes(type)) return [];

      const tmdbId = Number(linkTmdbId);
      const imdbId = await this.getImdbId(mediaType, tmdbId).catch(() => null);
      if (!imdbId) return [];

      const appendFields = mediaType === "movie" ? "release_dates" : "content_ratings";
      const details = await this.request<TmdbDetailsResponse>(`/${mediaType}/${tmdbId}`, {
        append_to_response: appendFields,
      });
      return [this.toMetadataPayloadFromDetails(type, details, imdbId)];
    }

    return null;
  }

  /**
   * Search results, cached and de-duplicated across callers.
   *
   * A search is the most expensive thing this client does — a list request plus
   * an `/external_ids` lookup per result, per page — and it is also the one
   * that runs while someone is typing. The web client debounces and aborts, but
   * an aborted request is only abandoned by the browser: the work it started
   * here runs to completion regardless. So identical searches share one flight,
   * and the answer is held briefly afterwards, which is what keeps a typed
   * query from spending a burst of TMDB's rate limit on itself and earning the
   * 429 that would empty the next one.
   *
   * A search that found nothing is held for far less time than one that found
   * something: "no results" is the answer most worth being wrong about, and the
   * fix for it is often a key or a setting that has just been corrected.
   */
  private async cachedSearch(key: string, run: () => Promise<MetadataPayload[]>): Promise<MetadataPayload[]> {
    const cacheKey = `${this.language}:${key}`;

    const cached = searchCache.get(cacheKey);
    if (cached) return cached;

    const inFlight = searchLookups.get(cacheKey);
    if (inFlight) return inFlight;

    const lookup = run()
      .then((results) => {
        searchCache.set(cacheKey, results, results.length === 0 ? { ttl: SEARCH_MISS_CACHE_TTL_MS } : undefined);
        return results;
      })
      .finally(() => {
        searchLookups.delete(cacheKey);
      });

    searchLookups.set(cacheKey, lookup);
    return lookup;
  }

  async trending(type: MetadataType, timeWindow: "day" | "week" = "week"): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    const response = await this.request<TmdbSearchResponse>(`/trending/${mediaType}/${timeWindow}`);
    return this.withImdbIds(response.results ?? [], this.asType(type));
  }

  async popular(type: MetadataType): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    const response = await this.request<TmdbSearchResponse>(`/${mediaType}/popular`);
    return this.withImdbIds(response.results ?? [], this.asType(type));
  }

  async recommendations(type: MetadataType, tmdbId: number): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    const response = await this.request<TmdbSearchResponse>(`/${mediaType}/${tmdbId}/recommendations`);
    return this.withImdbIds(response.results ?? [], this.asType(type));
  }

  async discoverByProvider(type: MetadataType, providerId: number, region: string = "US"): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    const response = await this.request<TmdbSearchResponse>(`/discover/${mediaType}`, {
      with_watch_providers: String(providerId),
      watch_region: region,
      sort_by: "popularity.desc",
    });
    return this.withImdbIds(response.results ?? [], this.asType(type));
  }

  async discoverAnime(type: MetadataType): Promise<MetadataPayload[]> {
    const mediaType = this.toMediaType(type);
    const params: Record<string, string> = {
      with_genres: "16", // Animation
      sort_by: "popularity.desc",
    };
    // For TV, filter by Japanese origin
    if (mediaType === "tv") {
      params.with_origin_country = "JP";
    }
    // For movies, use original language
    if (mediaType === "movie") {
      params.with_original_language = "ja";
    }

    const response = await this.request<TmdbSearchResponse>(`/discover/${mediaType}`, params);
    return this.withImdbIds(response.results ?? [], this.asType(type));
  }

  /**
   * The first page of a TV collection's TMDB source, for the website's
   * collection builder to preview. Mirrors how the TV reads the same source
   * (`TmdbCollectionSource`), so the preview shows the titles the row will.
   */
  /**
   * People, studios and film collections by name, for the Home folders
   * builder's pickers. TMDB ids were typed by hand there, which meant leaving
   * the page to look one up. One page of TMDB's own ranking is plenty: the
   * picker shows the top handful and a refined query does better than paging.
   * TMDB has no network search, so networks are a fixed list on the client.
   */
  async searchEntities(kind: TmdbEntityKind, query: string): Promise<TmdbEntity[]> {
    const response = await this.request<{
      results?: { id?: number; name?: string; title?: string; profile_path?: string | null; logo_path?: string | null; poster_path?: string | null; known_for_department?: string; origin_country?: string }[];
    }>(`/search/${kind}`, { query, include_adult: "false", page: "1" });
    return (response.results ?? []).flatMap((result) => {
      const name = (result.name ?? result.title)?.trim();
      if (!result.id || !name) return [];
      const image = result.profile_path ?? result.logo_path ?? result.poster_path ?? null;
      const detail = kind === "person" ? result.known_for_department : kind === "company" ? result.origin_country : undefined;
      return [{
        id: result.id,
        name,
        image: image ? `${kind === "person" ? TmdbClient.profileBaseUrl : kind === "company" ? TmdbClient.logoBaseUrl : TmdbClient.imageBaseUrl}${image}` : null,
        detail: detail || null,
      }];
    }).slice(0, 12);
  }

  async collectionSourcePage(source: TmdbCollectionSourceQuery): Promise<MetadataPayload[]> {
    const mediaType: TmdbMediaType = source.mediaType === "TV" ? "tv" : "movie";
    const type = mediaType === "movie" ? MetadataType.movie : MetadataType.series;
    const fixed = () => ({ mediaType, type });
    const mixed = (result: TmdbSearchResult): { mediaType: TmdbMediaType; type: MetadataType } | null => {
      const kind = result.media_type ?? mediaType;
      if (kind !== "movie" && kind !== "tv") return null;
      return { mediaType: kind, type: kind === "movie" ? MetadataType.movie : MetadataType.series };
    };
    const sortBy = source.sortBy && source.sortBy !== "original" ? source.sortBy : undefined;
    const id = source.tmdbId ? String(source.tmdbId) : undefined;

    switch (source.sourceType) {
      case "LIST": {
        if (!id) return [];
        const response = await this.request<{ items?: TmdbSearchResult[] }>(`/list/${id}`);
        return this.withImdbIds(response.items ?? [], mixed);
      }
      case "COLLECTION": {
        if (!id) return [];
        const response = await this.request<{ parts?: TmdbSearchResult[] }>(`/collection/${id}`);
        return this.withImdbIds(response.parts ?? [], () => ({ mediaType: "movie", type: MetadataType.movie }));
      }
      case "PERSON":
      case "DIRECTOR": {
        if (!id) return [];
        if (mediaType === "movie") {
          const key = source.sourceType === "DIRECTOR" ? "with_crew" : "with_cast";
          const response = await this.request<TmdbSearchResponse>("/discover/movie", {
            [key]: id,
            sort_by: sortBy ?? "popularity.desc",
          });
          return this.withImdbIds(response.results ?? [], fixed);
        }
        const credits = await this.request<{ cast?: TmdbSearchResult[]; crew?: (TmdbSearchResult & { job?: string })[] }>(
          `/person/${id}/tv_credits`
        );
        const results =
          source.sourceType === "DIRECTOR" ? (credits.crew ?? []).filter((c) => c.job === "Director") : credits.cast ?? [];
        return this.withImdbIds(results, fixed);
      }
      case "COMPANY":
      case "NETWORK":
      case "DISCOVER": {
        const params = discoverParams(mediaType, source.filters ?? {});
        if (source.sourceType === "COMPANY" && id) params.with_companies = id;
        if (source.sourceType === "NETWORK" && id) params.with_networks = id;
        params.sort_by = sortBy ?? "popularity.desc";
        const response = await this.request<TmdbSearchResponse>(`/discover/${mediaType}`, params);
        return this.withImdbIds(response.results ?? [], fixed);
      }
    }
  }

  /**
   * A page of Search results that honours its filters at the source.
   *
   * Search used to fetch one page and filter it in the browser, so "Horror,
   * 1980–1989" narrowed twenty results rather than searching for horror from
   * the eighties. Now:
   *
   *   - With no text query the filters *are* the query: one TMDB `/discover`
   *     page, sorted by TMDB, paged by TMDB.
   *   - With a query and no filters, page one is the ranked, cached `search()`
   *     everything else uses, and later pages are TMDB's own.
   *   - With a query and filters, TMDB search pages are scanned until a page's
   *     worth of matches is found. The filter runs on the raw TMDB rows —
   *     genre ids, release date, vote average are all on them — so only
   *     matches pay for the IMDb-id lookup.
   *
   * `nextPage` is where the next call should start, or null when TMDB has no
   * more. It is a TMDB page number, not a count of results: a filtered scan can
   * consume several pages in one call.
   */
  async browse(type: MetadataType, input: BrowseQuery): Promise<BrowsePage> {
    const mediaType = this.toMediaType(type);
    const page = Math.min(Math.max(Math.floor(input.page ?? 1), 1), TMDB_MAX_PAGE);
    const query = input.query?.trim() ?? "";
    const genreId = input.genre ? BROWSE_GENRES[input.genre]?.[mediaType] : undefined;

    // A genre this media type doesn't have matches none of it.
    if (input.genre && genreId === undefined) return { results: [], page, nextPage: null };

    const filtered =
      genreId !== undefined || input.yearMin != null || input.yearMax != null || input.ratingMin != null;

    if (!query) {
      const params: Record<string, string> = {
        ...discoverParams(mediaType, {
          withGenres: genreId !== undefined ? String(genreId) : null,
          releaseDateGte: input.yearMin != null ? `${input.yearMin}-01-01` : null,
          releaseDateLte: input.yearMax != null ? `${input.yearMax}-12-31` : null,
          voteAverageGte: input.ratingMin ?? null,
        }),
        sort_by: discoverSort(mediaType, input.sort ?? "relevance"),
        page: String(page),
      };
      // A rating filter or sort over titles three people have voted on is noise.
      if (input.ratingMin != null || input.sort === "rating") params["vote_count.gte"] = String(RATING_MIN_VOTES);
      // Newest first should mean released, not announced for 2031.
      if (input.sort === "year_desc" && input.yearMax == null) {
        const dateKey = mediaType === "movie" ? "primary_release_date" : "first_air_date";
        params[`${dateKey}.lte`] = new Date().toISOString().slice(0, 10);
      }
      const response = await this.request<TmdbSearchResponse>(`/discover/${mediaType}`, params);
      const results = await this.withImdbIds(response.results ?? [], this.asType(type));
      const total = Math.min(response.total_pages ?? 1, TMDB_MAX_PAGE);
      return { results, page, nextPage: page < total ? page + 1 : null };
    }

    if (!filtered && page === 1) {
      const results = await this.search(type, query);
      return { results, page, nextPage: results.length > 0 ? 2 : null };
    }

    const matches = (result: TmdbSearchResult): boolean => {
      if (genreId !== undefined && !(result.genre_ids ?? []).includes(genreId)) return false;
      const year = Number((result.release_date ?? result.first_air_date ?? "").slice(0, 4)) || null;
      if (input.yearMin != null && (year === null || year < input.yearMin)) return false;
      if (input.yearMax != null && (year === null || year > input.yearMax)) return false;
      if (input.ratingMin != null && (result.vote_average ?? 0) < input.ratingMin) return false;
      return true;
    };

    const candidates: TmdbSearchResult[] = [];
    let current = page;
    let totalPages = page;
    for (let scanned = 0; scanned < BROWSE_SCAN_PAGES && current <= totalPages; scanned++, current++) {
      const response = await this.request<TmdbSearchResponse>(`/search/${mediaType}`, { query, page: String(current) });
      totalPages = Math.min(response.total_pages ?? current, TMDB_MAX_PAGE);
      for (const result of response.results ?? []) if (matches(result)) candidates.push(result);
      if (candidates.length >= BROWSE_PAGE_RESULTS) {
        current++;
        break;
      }
    }

    // Every match on a scanned page is kept — the next call starts on the page
    // after — so a stretch can come back with up to a page more than asked for.
    // `withImdbIds` takes twenty at a time.
    const ranked = this.rankCandidates(candidates, normaliseTitle(query));
    const results: MetadataPayload[] = [];
    for (let i = 0; i < ranked.length; i += MAX_CATALOG_RESULTS) {
      results.push(...(await this.withImdbIds(ranked.slice(i, i + MAX_CATALOG_RESULTS), this.asType(type))));
    }
    return { results, page, nextPage: current <= totalPages ? current : null };
  }

  async getShowDetails(tmdbId: number): Promise<ShowDetails | null> {
    try {
      const data = await this.request<{
        next_episode_to_air?: { season_number: number; episode_number: number; name: string; air_date: string; overview?: string; still_path?: string | null } | null;
        last_episode_to_air?: { season_number: number; episode_number: number; name: string; air_date: string } | null;
        status?: string;
      }>(`/tv/${tmdbId}`);
      return {
        nextEpisodeToAir: data.next_episode_to_air
          ? (({ still_path, ...episode }) => ({
              ...episode,
              still: still_path ? `${TmdbClient.imageBaseUrl}${still_path}` : null,
            }))(data.next_episode_to_air)
          : null,
        lastEpisodeToAir: data.last_episode_to_air ?? null,
        status: data.status ?? null,
      };
    } catch {
      return null;
    }
  }

  async findByImdbId(type: MetadataType, imdbId: string): Promise<MetadataPayload | null> {
    const findResponse = await this.request<TmdbFindResponse>(`/find/${encodeURIComponent(imdbId)}`, {
      external_source: "imdb_id"
    });

    const result = type === MetadataType.movie ? findResponse.movie_results?.[0] : findResponse.tv_results?.[0];
    if (!result?.id) {
      return null;
    }

    const mediaType = this.toMediaType(type);
    const appendFields = mediaType === "movie" ? "release_dates" : "content_ratings";
    const details = await this.request<TmdbDetailsResponse>(`/${mediaType}/${result.id}`, {
      append_to_response: appendFields,
    });

    return this.toMetadataPayloadFromDetails(type, details, imdbId);
  }

  async getCast(type: MetadataType, tmdbId: number): Promise<Credits> {
    const empty: Credits = { cast: [], director: null };
    try {
      if (type === MetadataType.movie) {
        const data = await this.request<TmdbCreditsResponse>(`/movie/${tmdbId}/credits`);
        return {
          cast: this.mapCast(data.cast),
          director: data.crew?.find((c) => c.job === "Director")?.name ?? null,
        };
      }

      const data = await this.request<TmdbTvWithCreditsResponse>(`/tv/${tmdbId}`, {
        append_to_response: "credits",
      });
      return {
        cast: this.mapCast(data.credits?.cast),
        director: data.created_by?.[0]?.name
          ?? data.credits?.crew?.find((c) => c.job === "Executive Producer")?.name
          ?? null,
      };
    } catch {
      return empty;
    }
  }

  private mapCast(cast: TmdbCastEntry[] | undefined): CastMember[] {
    return (cast ?? [])
      .sort((a, b) => a.order - b.order)
      .slice(0, 20)
      .map((c) => ({
        name: c.name,
        character: c.character,
        photo: c.profile_path ? `${TmdbClient.profileBaseUrl}${c.profile_path}` : null,
        order: c.order,
      }));
  }

  async getSeasons(tmdbId: number): Promise<SeasonInfo[]> {
    try {
      const data = await this.request<{ seasons?: TmdbDetailsResult["seasons"] }>(`/tv/${tmdbId}`);
      return (data.seasons ?? [])
        .filter((s) => s.season_number > 0) // skip "Specials" season 0
        .map((s) => ({
          seasonNumber: s.season_number,
          name: s.name,
          episodeCount: s.episode_count,
          airYear: s.air_date ? new Date(s.air_date).getUTCFullYear() : null,
          poster: s.poster_path ? `${TmdbClient.imageBaseUrl}${s.poster_path}` : null,
        }));
    } catch {
      return [];
    }
  }

  async getSeasonEpisodes(tmdbId: number, seasonNumber: number): Promise<EpisodeInfo[]> {
    try {
      const data = await this.request<TmdbSeasonResponse>(`/tv/${tmdbId}/season/${seasonNumber}`);
      return (data.episodes ?? []).map((e) => ({
        episodeNumber: e.episode_number,
        name: e.name,
        airDate: e.air_date ?? null,
        still: e.still_path ? `${TmdbClient.imageBaseUrl}${e.still_path}` : null,
        runtime: typeof e.runtime === "number" ? e.runtime : null,
        overview: e.overview?.trim() || null,
      }));
    } catch {
      return [];
    }
  }

  async getWatchProviders(type: MetadataType, tmdbId: number, region: string = "US"): Promise<WatchProviders> {
    const mediaType = this.toMediaType(type);
    const empty: WatchProviders = { link: null, flatrate: [], free: [], ads: [] };
    try {
      const data = await this.request<TmdbWatchProvidersResponse>(`/${mediaType}/${tmdbId}/watch/providers`);
      const forRegion = data.results?.[region];
      if (!forRegion) return empty;

      const toProvider = (p: TmdbWatchProviderEntry): WatchProvider => ({
        id: p.provider_id,
        name: p.provider_name,
        logo: p.logo_path ? `${TmdbClient.logoBaseUrl}${p.logo_path}` : null,
      });

      return {
        link: forRegion.link ?? null,
        flatrate: (forRegion.flatrate ?? []).map(toProvider),
        free: (forRegion.free ?? []).map(toProvider),
        ads: (forRegion.ads ?? []).map(toProvider),
      };
    } catch {
      return empty;
    }
  }

  private async getImdbId(mediaType: TmdbMediaType, tmdbId: number): Promise<string | null> {
    const cacheKey = `${mediaType}:${tmdbId}`;
    const cached = externalIdsCache.get(cacheKey);
    if (cached !== undefined) {
      return cached.imdbId;
    }

    const externalIds = await this.request<TmdbExternalIds>(`/${mediaType}/${tmdbId}/external_ids`);
    const imdbId = externalIds.imdb_id?.trim() || null;
    externalIdsCache.set(
      cacheKey,
      { imdbId },
      imdbId ? undefined : { ttl: EXTERNAL_IDS_MISS_CACHE_TTL_MS }
    );
    return imdbId;
  }

  private parseCertification(type: MetadataType, details: TmdbDetailsResult): string | null {
    if (type === MetadataType.movie) {
      const usEntry = details.release_dates?.results?.find((r) => r.iso_3166_1 === "US");
      if (usEntry) {
        // Type 3 = Theatrical, 4 = Digital, 5 = Physical — prefer theatrical
        const theatrical = usEntry.release_dates.find((d) => d.type === 3 && d.certification);
        if (theatrical?.certification) return theatrical.certification;
        const any = usEntry.release_dates.find((d) => d.certification);
        if (any?.certification) return any.certification;
      }
      return null;
    }
    // TV
    const usEntry = details.content_ratings?.results?.find((r) => r.iso_3166_1 === "US");
    return usEntry?.rating ?? null;
  }

  private toMetadataPayload(type: MetadataType, result: TmdbSearchResult, imdbId: string): MetadataPayload {
    const name = (type === MetadataType.movie ? result.title : result.name)?.trim() || imdbId;
    const dateValue = type === MetadataType.movie ? result.release_date : result.first_air_date;
    const genres = (result.genre_ids ?? [])
      .map((id) => TMDB_GENRE_MAP[id])
      .filter((g): g is string => !!g);

    return {
      imdbId,
      type,
      tmdbId: result.id ?? null,
      name,
      year: this.extractYear(dateValue),
      poster: this.buildImageUrl(result.poster_path),
      background: this.buildImageUrl(result.backdrop_path),
      description: result.overview?.trim() || null,
      genres,
      rating: typeof result.vote_average === "number" ? Math.round(result.vote_average * 10) / 10 : null,
      voteCount: typeof result.vote_count === "number" ? result.vote_count : null,
      totalSeasons: null,
      totalEpisodes: null,
      runtime: null,
      certification: null,
      status: null,
      network: null,
      releaseDate: dateValue ?? null,
    };
  }

  private toMetadataPayloadFromDetails(type: MetadataType, result: TmdbDetailsResult, imdbId: string): MetadataPayload {
    const name = (type === MetadataType.movie ? result.title : result.name)?.trim() || imdbId;
    const dateValue = type === MetadataType.movie ? result.release_date : result.first_air_date;
    const genres = (result.genres ?? []).map((g) => g.name).filter(Boolean);

    const runtime = type === MetadataType.movie
      ? (typeof result.runtime === "number" && result.runtime > 0 ? result.runtime : null)
      : (result.episode_run_time?.[0] ?? null);

    const network = type === MetadataType.series
      ? (result.networks?.[0]?.name ?? null)
      : null;

    return {
      imdbId,
      type,
      tmdbId: result.id ?? null,
      name,
      year: this.extractYear(dateValue),
      poster: this.buildImageUrl(result.poster_path),
      background: this.buildImageUrl(result.backdrop_path),
      description: result.overview?.trim() || null,
      genres,
      rating: typeof result.vote_average === "number" ? Math.round(result.vote_average * 10) / 10 : null,
      voteCount: typeof result.vote_count === "number" ? result.vote_count : null,
      totalSeasons: type === MetadataType.series && typeof result.number_of_seasons === "number" ? result.number_of_seasons : null,
      totalEpisodes: type === MetadataType.series && typeof result.number_of_episodes === "number" ? result.number_of_episodes : null,
      runtime,
      certification: this.parseCertification(type, result),
      status: result.status ?? null,
      network,
      releaseDate: dateValue ?? null,
    };
  }

  private extractYear(rawDate: string | undefined) {
    if (!rawDate) {
      return null;
    }

    const parsed = new Date(rawDate);
    return Number.isNaN(parsed.getTime()) ? null : parsed.getUTCFullYear();
  }

  private buildImageUrl(path: string | null | undefined) {
    if (!path) {
      return null;
    }

    return `${TmdbClient.imageBaseUrl}${path}`;
  }

  private toMediaType(type: MetadataType): TmdbMediaType {
    if (type === MetadataType.movie) {
      return "movie";
    }

    return "tv";
  }

  private async request<T>(path: string, searchParams?: Record<string, string>): Promise<T> {
    const url = new URL(`${TmdbClient.baseUrl}${path}`);
    url.searchParams.set("api_key", this.apiKey);
    url.searchParams.set("language", this.language);

    if (searchParams) {
      for (const [key, value] of Object.entries(searchParams)) {
        url.searchParams.set(key, value);
      }
    }

    const response = await fetchWithPolicy(
      url,
      { headers: { Accept: "application/json" } },
      {
        timeoutMs: REQUEST_TIMEOUT_MS,
        concurrency: TMDB_CONCURRENCY,
      }
    );

    if (!response.ok) {
      throw new Error(`TMDB request failed (${response.status})`);
    }

    return (await response.json()) as T;
  }
}
