import { ItemType, ListItemType, MetadataType, Prisma, WatchEventType } from "@prisma/client";
import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";
import { watchEventDedupWhere } from "./watch-event-dedup.js";
import { upsertSeriesProgressIfNewer } from "./series-progress.js";
import { getDefaultWatchlist } from "./watchlist.js";
import { backfillMissingMetadata } from "./metadata.js";
import type { SeriesProgressCandidate } from "./types.js";

// What a tracker or media server sync brings into a profile: plays it knows
// about and titles on its watchlist. Shared by Simkl, MDBList, Plex, Jellyfin
// and Emby, so every one of them dedupes the same way.
//
// Plays are written directly rather than through `recordWatchEvent`, for the
// reason the Trakt import is: recording would push each one straight back out
// to every connected tracker, including the one it came from.

/**
 * `undated` marks a play the source knows happened but not when (a media
 * server item marked played by hand). It is dated to the sync, so it is only
 * added when the profile has no play of that title (or episode) at all —
 * otherwise every later sync, on a later day, would add it again.
 */
export type ImportedWatch =
  | { type: "movie"; imdbId: string; watchedAt: Date; title?: string | null | undefined; undated?: boolean }
  | {
      type: "episode";
      seriesImdbId: string;
      season: number;
      episode: number;
      watchedAt: Date;
      title?: string | null | undefined;
      undated?: boolean;
    };

export type ImportedWatchlistItem = { type: "movie" | "series"; imdbId: string; title?: string | null | undefined };

export type ImportSummary = { movies: number; episodes: number; watchlist: number; skipped: number };

const IMDB_ID = /^tt\d+$/;

const upsertItem = async (type: ItemType, imdbId: string, title: string | null | undefined) => {
  const name = title?.trim();
  await prisma.item.upsert({
    where: { type_imdbId: { type, imdbId } },
    create: { type, imdbId, ...(name ? { title: name } : {}) },
    update: name ? { title: name } : {},
  });
};

/**
 * Writes the plays and watchlist entries a sync found, skipping any the
 * profile already has: a play matches one on the same title (and episode) the
 * same UTC day, the rule every other import uses. Safe to run again over the
 * same items, which is what an incremental sync with an overlapping window does.
 */
export const importTrackerItems = async (
  profileId: string,
  watches: ImportedWatch[],
  watchlist: ImportedWatchlistItem[],
  log?: FastifyBaseLogger
): Promise<ImportSummary> => {
  const summary: ImportSummary = { movies: 0, episodes: 0, watchlist: 0, skipped: 0 };
  const upserted = new Set<string>();
  const progress = new Map<string, SeriesProgressCandidate>();
  const touched: { imdbId: string; type: MetadataType }[] = [];

  const ensureItem = async (type: ItemType, imdbId: string, title: string | null | undefined) => {
    const key = `${type}:${imdbId}`;
    if (upserted.has(key)) return;
    upserted.add(key);
    await upsertItem(type, imdbId, title);
    touched.push({ imdbId, type: type === ItemType.movie ? MetadataType.movie : MetadataType.series });
  };

  for (const watch of watches) {
    const imdbId = watch.type === "movie" ? watch.imdbId : watch.seriesImdbId;
    if (!IMDB_ID.test(imdbId) || Number.isNaN(watch.watchedAt.getTime())) {
      summary.skipped += 1;
      continue;
    }
    await ensureItem(watch.type === "movie" ? ItemType.movie : ItemType.series, imdbId, watch.title);

    const where = watch.undated
      ? watch.type === "movie"
        ? { profileId, type: WatchEventType.movie, imdbId }
        : { profileId, type: WatchEventType.episode, seriesImdbId: imdbId, season: watch.season, episode: watch.episode }
      : watch.type === "movie"
        ? watchEventDedupWhere({ profileId, type: WatchEventType.movie, imdbId, watchedAt: watch.watchedAt })
        : watchEventDedupWhere({
            profileId,
            type: WatchEventType.episode,
            imdbId,
            season: watch.season,
            episode: watch.episode,
            watchedAt: watch.watchedAt,
          });
    if (await prisma.watchEvent.findFirst({ where, select: { id: true } })) continue;

    try {
      await prisma.watchEvent.create({
        data:
          watch.type === "movie"
            ? { type: WatchEventType.movie, imdbId, watchedAt: watch.watchedAt, plays: 1, profileId }
            : {
                type: WatchEventType.episode,
                imdbId,
                seriesImdbId: imdbId,
                season: watch.season,
                episode: watch.episode,
                watchedAt: watch.watchedAt,
                plays: 1,
                profileId,
              },
      });
    } catch (error) {
      // Another writer landed the same play first; it exists, which is all this wanted.
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") continue;
      throw error;
    }

    if (watch.type === "movie") {
      summary.movies += 1;
    } else {
      summary.episodes += 1;
      const current = progress.get(imdbId);
      const later =
        !current ||
        watch.watchedAt > current.lastWatchedAt ||
        (watch.watchedAt.getTime() === current.lastWatchedAt.getTime() &&
          (watch.season > current.lastSeason ||
            (watch.season === current.lastSeason && watch.episode > current.lastEpisode)));
      if (later) {
        progress.set(imdbId, { lastSeason: watch.season, lastEpisode: watch.episode, lastWatchedAt: watch.watchedAt });
      }
    }
  }

  for (const [seriesImdbId, candidate] of progress) {
    await upsertSeriesProgressIfNewer(profileId, seriesImdbId, candidate);
  }

  if (watchlist.length > 0) {
    const list = await getDefaultWatchlist(profileId);
    for (const item of watchlist) {
      if (!IMDB_ID.test(item.imdbId)) {
        summary.skipped += 1;
        continue;
      }
      await ensureItem(item.type === "movie" ? ItemType.movie : ItemType.series, item.imdbId, item.title);
      try {
        await prisma.listItem.create({
          data: { listId: list.id, type: item.type as ListItemType, imdbId: item.imdbId },
        });
        summary.watchlist += 1;
      } catch (error) {
        if (!(error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002")) throw error;
      }
    }
  }

  // Titles that only arrived by id get their posters and names in the background.
  backfillMissingMetadata(touched, log);
  return summary;
};
