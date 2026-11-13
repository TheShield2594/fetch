import { api } from "../api";

/**
 * Release dates for the watch dialog's "Release date", looked up when the
 * caller doesn't already have one: an episode's air date from its season's
 * episode list, a film's from its detail bundle.
 *
 * Each list is asked for once and kept for the session — stepping through the
 * dialog's episode picker asks again for every episode, and air dates are the
 * same for every profile. A failed read is forgotten so the next ask retries.
 */
const seasonEpisodes = new Map<string, Promise<{ episodeNumber: number; airDate?: string | null }[]>>();
const movieDates = new Map<string, Promise<string | null>>();

function remember<T>(cache: Map<string, Promise<T>>, key: string, load: () => Promise<T>): Promise<T> {
  let pending = cache.get(key);
  if (!pending) {
    pending = load();
    cache.set(key, pending);
    pending.catch(() => cache.delete(key));
  }
  return pending;
}

/** The air date of an episode (YYYY-MM-DD), or null when it has none. */
export async function loadEpisodeAirDate(seriesImdbId: string, season: number, episode: number): Promise<string | null> {
  const episodes = await remember(seasonEpisodes, `${seriesImdbId}:${season}`, () =>
    api.getSeasonEpisodes(seriesImdbId, season).then((r) => r.episodes)
  );
  return episodes.find((e) => e.episodeNumber === episode)?.airDate ?? null;
}

/** A film's release date (YYYY-MM-DD), or null when it has none. */
export function loadMovieReleaseDate(imdbId: string): Promise<string | null> {
  return remember(movieDates, imdbId, () =>
    api.getDetailBundle("movie", imdbId).then((b) => b.meta.releaseDate ?? null)
  );
}

/** Forgets everything — for tests. */
export function clearReleaseDateCache() {
  seasonEpisodes.clear();
  movieDates.clear();
}
