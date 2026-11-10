import type { FastifyBaseLogger } from "fastify";
import { parseEpisodesResponse, parseSeasonsResponse } from "@fetch/shared";
import { apiGet, type ApiProfile } from "./api-client.js";
import type { StremioVideo } from "./stremio-types.js";

/**
 * A series' episodes as Stremio `videos`, ids `tt…:season:episode`.
 *
 * The manifest answers `meta` for every series id, and in the addon protocol a
 * series meta without `videos` has no seasons or episodes: a client that asks
 * Fetch first (Omni, Nuvio, Vidi, or Stremio with Fetch above Cinemeta) would
 * show a detail page with nothing to play.
 *
 * Season 0 (specials) is left out: the API's episode route takes seasons from 1,
 * and Cinemeta lists specials apart from the numbered seasons too. A season
 * whose episodes can't be read still gets numbered entries from its episode
 * count, so every episode stays playable, only without its title.
 */
export const loadSeriesVideos = async (
  imdbId: string,
  scope: ApiProfile,
  logger: FastifyBaseLogger
): Promise<StremioVideo[]> => {
  let seasons: ReturnType<typeof parseSeasonsResponse>["seasons"];
  try {
    ({ seasons } = await apiGet(`/meta/series/${encodeURIComponent(imdbId)}/seasons`, scope, parseSeasonsResponse));
  } catch (error) {
    logger.warn(error, "Failed to fetch seasons for a series meta");
    return [];
  }

  const numbered = seasons
    .filter((season) => Number.isInteger(season.seasonNumber) && season.seasonNumber >= 1)
    .sort((a, b) => a.seasonNumber - b.seasonNumber);

  const perSeason = await Promise.all(
    numbered.map(async (season): Promise<StremioVideo[]> => {
      const { seasonNumber } = season;
      try {
        const { episodes } = await apiGet(
          `/meta/series/${encodeURIComponent(imdbId)}/season/${seasonNumber}/episodes`,
          scope,
          parseEpisodesResponse
        );
        const numberedEpisodes = episodes
          .filter((episode) => Number.isInteger(episode.episodeNumber) && episode.episodeNumber >= 1)
          .sort((a, b) => a.episodeNumber - b.episodeNumber);
        if (numberedEpisodes.length > 0) {
          return numberedEpisodes.map((episode) => toVideo(imdbId, seasonNumber, episode.episodeNumber, episode));
        }
      } catch (error) {
        logger.debug(error, `Failed to fetch episodes for season ${seasonNumber}, numbering them instead`);
      }
      return Array.from({ length: Math.max(0, Math.floor(season.episodeCount)) }, (_, index) =>
        toVideo(imdbId, seasonNumber, index + 1)
      );
    })
  );

  return perSeason.flat();
};

const toVideo = (
  imdbId: string,
  season: number,
  episode: number,
  details?: { name: string; airDate: string | null; still: string | null }
): StremioVideo => {
  const video: StremioVideo = {
    id: `${imdbId}:${season}:${episode}`,
    title: details?.name.trim() || `Episode ${episode}`,
    season,
    episode,
  };
  const released = details?.airDate ? releasedAt(details.airDate) : undefined;
  if (released) video.released = released;
  if (details?.still) video.thumbnail = details.still;
  return video;
};

/** TMDB's `YYYY-MM-DD` air date as the ISO timestamp Stremio expects; undefined when it isn't a date. */
const releasedAt = (airDate: string): string | undefined => {
  const time = Date.parse(/^\d{4}-\d{2}-\d{2}$/.test(airDate) ? `${airDate}T00:00:00.000Z` : airDate);
  return Number.isNaN(time) ? undefined : new Date(time).toISOString();
};
