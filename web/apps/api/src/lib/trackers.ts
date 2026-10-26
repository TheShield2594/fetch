import type { FastifyBaseLogger } from "fastify";
import { pushSimklScrobble, pushSimklWatched, pushSimklWatchlistAdd } from "./simkl.js";
import { pushMdbListScrobble, pushMdbListWatched, pushMdbListWatchlistAdd } from "./mdblist.js";
import { pushPlayedToMediaServers } from "./media-servers.js";

// What Fetch sends out to the tracking services a profile has connected,
// beside Trakt (lib/trakt-client.ts, which also keeps a history id per play):
// Simkl and MDBList, as the TV sends to them, and the profile's Plex, Jellyfin
// and Emby servers, where a play is marked played. Every push is best-effort and
// never throws, so the callers fire these without awaiting.

export type TrackerWatch =
  | { type: "movie"; imdbId: string; watchedAt?: Date }
  | { type: "episode"; seriesImdbId: string; season: number | null | undefined; episode: number | null | undefined; watchedAt?: Date };

export const pushWatchedToTrackers = async (profileId: string, watch: TrackerWatch, logger: FastifyBaseLogger) => {
  await Promise.all([
    pushSimklWatched(profileId, watch, logger),
    pushMdbListWatched(profileId, watch, logger),
    pushPlayedToMediaServers(profileId, watch, logger),
  ]);
};

export const pushWatchlistAddToTrackers = async (
  profileId: string,
  item: { type: "movie" | "series"; imdbId: string },
  logger: FastifyBaseLogger
) => {
  await Promise.all([
    pushSimklWatchlistAdd(profileId, item, logger),
    pushMdbListWatchlistAdd(profileId, item, logger),
  ]);
};

export type TrackerScrobble = {
  profileId: string;
  type: "movie" | "episode";
  imdbId: string;
  seriesImdbId?: string | null | undefined;
  season?: number | null | undefined;
  episode?: number | null | undefined;
  progress: number;
};

export const pushScrobbleToTrackers = async (
  action: "start" | "pause" | "stop",
  params: TrackerScrobble,
  logger: FastifyBaseLogger
) => {
  await Promise.all([pushSimklScrobble(action, params, logger), pushMdbListScrobble(action, params, logger)]);
};
