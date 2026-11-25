import { ListItemType, ListKind } from "@prisma/client";
import type { FastifyBaseLogger } from "fastify";
import { parseBool } from "@fetch/shared";
import { prisma } from "./prisma.js";
import { pushTraktWatchlistChange } from "./trakt-client.js";

/**
 * A watchlist is the things still to watch, so a title leaves it once it has
 * been watched: a film when it is played, a show when its last episode is.
 * On by default; `WATCHLIST_REMOVE_WATCHED=false` keeps watched titles listed.
 *
 * Read per call rather than once at import, so a test can flip it.
 */
const removeWatchedEnabled = () => parseBool(process.env.WATCHLIST_REMOVE_WATCHED, true);

export type WatchedTitle = { type: "movie" | "series"; imdbId: string };

/**
 * Whether every episode TMDB counts for the show has been watched — the same
 * rule that drops a show from Continue Watching. Specials are outside TMDB's
 * count, so they are left out of this one. A show whose episode count isn't
 * known is never called finished.
 */
const isSeriesFinished = async (profileId: string, imdbId: string): Promise<boolean> => {
  const meta = await prisma.metadata.findUnique({
    where: { imdbId_type: { imdbId, type: "series" } },
    select: { totalEpisodes: true },
  });
  if (!meta?.totalEpisodes) return false;

  const watched = await prisma.watchEvent.findMany({
    where: { profileId, type: "episode", seriesImdbId: imdbId, season: { gte: 1 }, episode: { not: null } },
    select: { season: true, episode: true },
    distinct: ["season", "episode"],
  });
  return watched.length >= meta.totalEpisodes;
};

/**
 * Takes the titles that were just watched off the profile's watchlist, where
 * they are on it and — for a show — finished. The removal is pushed to Trakt
 * like one made by hand, so the two watchlists stay in step.
 *
 * Called after a watch has been recorded and never part of recording it:
 * best-effort, never throws. Only the titles named are looked at, so a watched
 * film added back to the watchlist for a rewatch stays until it is played again.
 */
export const removeWatchedFromWatchlist = async (
  profileId: string,
  titles: WatchedTitle[],
  log: FastifyBaseLogger
): Promise<number> => {
  if (titles.length === 0 || !removeWatchedEnabled()) return 0;

  let removed = 0;
  try {
    const watchlist = await prisma.list.findFirst({
      where: { kind: ListKind.watchlist, profileId },
      orderBy: { createdAt: "asc" },
      select: { id: true },
    });
    if (!watchlist) return 0;

    // The membership check comes first: most plays are of something that isn't
    // on the watchlist, and those should cost one query and nothing else.
    const listed = await prisma.listItem.findMany({
      where: { listId: watchlist.id, imdbId: { in: [...new Set(titles.map((title) => title.imdbId))] } },
      select: { type: true, imdbId: true },
    });
    const wanted = new Set(titles.map((title) => `${title.type}:${title.imdbId}`));

    for (const item of listed) {
      if (!wanted.has(`${item.type}:${item.imdbId}`)) continue;
      if (item.type === ListItemType.series && !(await isSeriesFinished(profileId, item.imdbId))) continue;

      const deleted = await prisma.listItem.deleteMany({
        where: { listId: watchlist.id, type: item.type, imdbId: item.imdbId },
      });
      if (deleted.count === 0) continue;

      removed += 1;
      log.info({ imdbId: item.imdbId, type: item.type }, "Watched: removed from watchlist");
      await pushTraktWatchlistChange("remove", { type: item.type, imdbId: item.imdbId }, log, profileId);
    }
  } catch (error) {
    log.warn({ error }, "Removing watched titles from the watchlist failed");
  }
  return removed;
};
