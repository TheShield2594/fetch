import { LRUCache } from "lru-cache";
import type { FastifyBaseLogger } from "fastify";
import {
  readMdbListRatingSettings,
  visibleMdbListSources,
  type MdbListRatingSource,
  type TvSettingsDocument,
} from "@fetch/shared/tv-settings";
import { deleteConnection, getConnection, saveConnection } from "./connections.js";
import { accessTokenFor, mdbRequest, MdbListError } from "./mdblist.js";
import { getProfileDocument } from "./profile-document.js";

// MDBList ratings on detail pages, as the TV shows them (data/mdblist/
// MdbListRatingsClient.kt, MdbListRatingsLoader.kt): IMDb, Trakt, TMDB, Rotten
// Tomatoes and its audience score, Letterboxd, Metacritic and MyAnimeList, read
// with an API key the profile pastes, or with its MDBList sign-in when it has
// no key. Which of them show, and in what order, is the profile's
// `mdblist_settings` in its TV settings document, so the TV and the website
// share one set of choices.

export type MdbListKeyCredentials = { apiKey: string };
type MdbListKeyDetails = Record<string, never>;

/** A pasted key that MDBList turned down, as opposed to one it couldn't be asked about. */
export class MdbListKeyRejected extends Error {}

const KEY_PATTERN = /^\S{1,200}$/;

export const getMdbListKeyStatus = async (profileId: string) => {
  const [key, account] = await Promise.all([
    getConnection<MdbListKeyCredentials, MdbListKeyDetails>(profileId, "mdblist-key"),
    getConnection(profileId, "mdblist"),
  ]);
  return { hasKey: !!key?.credentials?.apiKey, signedIn: !!account };
};

/** Checks the key with MDBList's `/user`, as the TV does, and keeps it encrypted. */
export const saveMdbListKey = async (profileId: string, raw: string): Promise<void> => {
  const apiKey = raw.trim();
  if (!KEY_PATTERN.test(apiKey)) throw new MdbListKeyRejected("That doesn't look like an MDBList API key");
  try {
    await mdbRequest("GET", "/user", { query: { apikey: apiKey } });
  } catch (error) {
    // A 4xx is MDBList saying no to the key; anything else is MDBList being
    // unreachable, which says nothing about the key.
    if (error instanceof MdbListError && error.status !== null && error.status >= 400 && error.status < 500) {
      throw new MdbListKeyRejected("MDBList didn't accept that API key");
    }
    throw error;
  }
  await saveConnection<MdbListKeyCredentials, MdbListKeyDetails>({
    profileId,
    provider: "mdblist-key",
    credentials: { apiKey },
    details: {},
  });
};

export const removeMdbListKey = async (profileId: string): Promise<void> => {
  await deleteConnection(profileId, "mdblist-key");
};

type Credential = { query: Record<string, string> } | { token: string };

/** The profile's key, else its MDBList sign-in, else nothing: the TV's `MdbListRatingsClient.credential`. */
const credentialFor = async (profileId: string, logger: FastifyBaseLogger): Promise<Credential | null> => {
  const key = await getConnection<MdbListKeyCredentials, MdbListKeyDetails>(profileId, "mdblist-key");
  if (key?.credentials?.apiKey) return { query: { apikey: key.credentials.apiKey } };
  try {
    const token = await accessTokenFor(profileId);
    return token ? { token } : null;
  } catch (error) {
    // Ratings are decoration; a sign-in that won't refresh just means none.
    logger.warn({ err: error }, "MDBList sign-in unusable for ratings");
    return null;
  }
};

// ─── Reading and normalising ───

export type MdbListAllRatings = Partial<Record<MdbListRatingSource, number>> & {
  tomatoesCertified?: boolean;
  audienceCertified?: boolean;
};

type MediaResponse = {
  ratings?: { source?: unknown; value?: unknown; score?: unknown }[] | null;
  keywords?: unknown[] | null;
} | null;

const SOURCE_ALIASES: Record<string, MdbListRatingSource> = {
  imdb: "imdb",
  trakt: "trakt",
  tmdb: "tmdb",
  letterboxd: "letterboxd",
  tomatoes: "tomatoes",
  metacritic: "metacritic",
  popcorn: "audience",
  audience: "audience",
  tomatoesaudience: "audience",
  myanimelist: "mal",
  mal: "mal",
};

const maximumFor = (source: MdbListRatingSource): number =>
  source === "imdb" || source === "mal" ? 10 : source === "letterboxd" ? 5 : 100;

/**
 * One title's ratings from MDBList's media response, the TV's
 * `MDBListMediaResponseDto.toRatings`: scales checked, Letterboxd taken from its
 * 0–100 score when MDBList gives one, and the certified badges read from the
 * title's keywords. Exported for tests.
 */
export const projectMdbListRatings = (response: MediaResponse): MdbListAllRatings => {
  const keywords = new Set(
    (response?.keywords ?? []).flatMap((keyword) => {
      const name =
        typeof keyword === "string"
          ? keyword
          : typeof keyword === "object" && keyword !== null && typeof (keyword as { name?: unknown }).name === "string"
            ? (keyword as { name: string }).name
            : null;
      return name ? [name.slice(name.lastIndexOf(".") + 1)] : [];
    })
  );
  const ratings: MdbListAllRatings = {};
  for (const rating of response?.ratings ?? []) {
    const source = typeof rating.source === "string" ? SOURCE_ALIASES[rating.source] : undefined;
    if (!source || ratings[source] !== undefined) continue;
    if (typeof rating.value !== "number" || !Number.isFinite(rating.value) || rating.value < 0) continue;
    const value = source === "letterboxd" && typeof rating.score === "number" ? rating.score / 20 : rating.value;
    if (value < 0 || value > maximumFor(source)) continue;
    ratings[source] = value;
  }
  if (ratings.tomatoes !== undefined && keywords.has("certified-fresh")) ratings.tomatoesCertified = true;
  if (ratings.audience !== undefined && keywords.has("certified-hot")) ratings.audienceCertified = true;
  return ratings;
};

// Detail pages are hot and ratings move slowly. The same for every profile, so
// keyed by title alone; a title MDBList has nothing for is asked about again
// sooner, since that is the answer most likely to change.
const RATINGS_TTL_MS = 6 * 60 * 60 * 1000;
const RATINGS_MISS_TTL_MS = 30 * 60 * 1000;
const ratingsCache = new LRUCache<string, MdbListAllRatings>({ max: 5000, ttl: RATINGS_TTL_MS });
const ratingsLookups = new Map<string, Promise<MdbListAllRatings>>();

export const resetMdbListRatingsCacheForTests = () => {
  ratingsCache.clear();
  ratingsLookups.clear();
};

const fetchRatings = async (mediaType: "movie" | "show", imdbId: string, credential: Credential) => {
  try {
    const response =
      "token" in credential
        ? await mdbRequest<MediaResponse>("GET", `/imdb/${mediaType}/${imdbId}/`, {
            token: credential.token,
            query: { append_to_response: "keyword" },
          })
        : await mdbRequest<MediaResponse>("GET", `/imdb/${mediaType}/${imdbId}`, {
            query: { ...credential.query, append_to_response: "keyword" },
          });
    return projectMdbListRatings(response);
  } catch (error) {
    if (error instanceof MdbListError && error.status === 404) return {};
    throw error;
  }
};

const isEmpty = (ratings: MdbListAllRatings) => Object.keys(ratings).length === 0;

const ratingsFor = async (mediaType: "movie" | "show", imdbId: string, credential: Credential) => {
  const cacheKey = `${mediaType}:${imdbId}`;
  const cached = ratingsCache.get(cacheKey);
  if (cached) return cached;
  // Opening a detail page asks once per panel mount; two tabs, or a quick
  // close and reopen, share the request already out.
  let lookup = ratingsLookups.get(cacheKey);
  if (!lookup) {
    lookup = fetchRatings(mediaType, imdbId, credential)
      .then((ratings) => {
        ratingsCache.set(cacheKey, ratings, { ttl: isEmpty(ratings) ? RATINGS_MISS_TTL_MS : RATINGS_TTL_MS });
        return ratings;
      })
      .finally(() => ratingsLookups.delete(cacheKey));
    ratingsLookups.set(cacheKey, lookup);
  }
  return lookup;
};

export type MdbListRating = { source: MdbListRatingSource; value: number; certified: boolean };

/**
 * The ratings a profile's detail page shows for a title, in its order. Empty
 * when it hasn't turned MDBList ratings on, has no key or sign-in to read them
 * with, or MDBList has none; the page then shows its own scores instead.
 */
export const getMdbListRatings = async (
  profileId: string,
  type: "movie" | "series",
  imdbId: string,
  logger: FastifyBaseLogger
): Promise<MdbListRating[]> => {
  const stored = await getProfileDocument(profileId, "tv-settings");
  const settings = readMdbListRatingSettings((stored?.document ?? {}) as TvSettingsDocument);
  const visible = visibleMdbListSources(settings);
  if (!settings.enabled || visible.length === 0) return [];
  const credential = await credentialFor(profileId, logger);
  if (!credential) return [];
  const ratings = await ratingsFor(type === "movie" ? "movie" : "show", imdbId, credential);
  return visible.flatMap((source) => {
    const value = ratings[source];
    if (value === undefined) return [];
    const certified =
      (source === "tomatoes" && ratings.tomatoesCertified === true) ||
      (source === "audience" && ratings.audienceCertified === true);
    return [{ source, value, certified }];
  });
};
