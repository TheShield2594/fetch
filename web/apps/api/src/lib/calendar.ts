import type { CalendarEntry } from "@fetch/shared";
import type { FastifyBaseLogger } from "fastify";
import { LRUCache } from "lru-cache";
import { ListKind, MetadataType, WatchEventType } from "@prisma/client";
import { prisma } from "./prisma.js";
import { getTmdb } from "./tmdb-client.js";
import { loadEpisodes } from "./detail.js";
import { getCachedShowDetails } from "./upcoming-episodes.js";
import { getTraktClient, isTraktConnected } from "./trakt-client.js";

/**
 * How many in-progress series, and how many watchlisted ones, the calendar
 * asks TMDB about. Each costs a show-details request and a season or two of
 * episodes (all cached for hours), so the two are capped separately: a long
 * watchlist must not crowd out the shows you are actually watching.
 */
const IN_PROGRESS_LIMIT = 30;
const WATCHLIST_SERIES_LIMIT = 30;
/** Seasons walked back from the current one to reach the start of a window in the past. */
const MAX_SEASONS_PER_SERIES = 3;
const BATCH_SIZE = 5;

/** TMDB statuses after which nothing more will air. */
const NO_FURTHER_EPISODES = new Set(["Ended", "Canceled"]);

/** `YYYY-MM-DD` for a UTC day, the shape air and release dates are stored in. */
const isoDay = (date: Date): string => date.toISOString().slice(0, 10);
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Episodes before films on a shared day, premieres first among episodes. */
const KIND_ORDER: Record<CalendarEntry["kind"], number> = { premiere: 0, episode: 1, movie: 2 };

/** The window asked about: `days` days from `start` (a UTC midnight). */
export type CalendarWindow = { start: Date; days: number };

const episodeKey = (imdbId: string, season: number, episode: number) => `${imdbId}:${season}:${episode}`;

/*
 * Air times from Trakt, per profile and window. Half an hour: a schedule
 * rarely moves within one, and the calendar is reloaded on every visit.
 */
const traktTimesCache = new LRUCache<string, Map<string, string>>({ max: 200, ttl: 30 * 60 * 1000 });

/**
 * The instant each of this profile's Trakt shows airs inside the window, keyed
 * by series, season and episode. Empty for a profile without Trakt, and on any
 * failure: a time of day is a refinement, and the calendar is right without
 * one.
 */
async function traktAirTimes(
  profileId: string,
  window: CalendarWindow,
  log?: FastifyBaseLogger
): Promise<Map<string, string>> {
  const key = `${profileId}:${isoDay(window.start)}:${window.days}`;
  const cached = traktTimesCache.get(key);
  if (cached) return cached;
  const times = new Map<string, string>();
  try {
    if (!(await isTraktConnected(profileId))) return times;
    const client = await getTraktClient(profileId);
    // A show airing late in the evening in the Americas is already the next UTC
    // day, so the request starts a day early to catch the window's first night.
    const from = new Date(window.start);
    from.setUTCDate(from.getUTCDate() - 1);
    const rows = await client.fetchMyShowsCalendar(isoDay(from), window.days + 2, log ?? silentLogger);
    for (const row of rows) {
      const imdb = row.show?.ids?.imdb;
      const season = row.episode?.season;
      const number = row.episode?.number;
      if (!imdb || season == null || number == null || !row.first_aired) continue;
      if (Number.isNaN(Date.parse(row.first_aired))) continue;
      times.set(episodeKey(imdb, season, number), row.first_aired);
    }
    traktTimesCache.set(key, times);
  } catch (err) {
    log?.warn({ err }, "Trakt calendar unavailable; the calendar shows dates without times");
  }
  return times;
}

const silentLogger = {
  warn() {},
  info() {},
  error() {},
  debug() {},
  trace() {},
  fatal() {},
  child() {
    return silentLogger;
  },
  level: "silent",
} as unknown as FastifyBaseLogger;

type SeriesMeta = { imdbId: string; tmdbId: number | null; name: string; poster: string | null; background: string | null; status: string | null };

/**
 * Every episode of one series that airs inside the window.
 *
 * Read from the season episode lists rather than from `next_episode_to_air`,
 * which names one episode: a month of a weekly show is four entries, and a
 * month in the past has no "next" at all. The seasons asked for are the one
 * airing now (or next), then earlier ones while the window still reaches back
 * before the first episode in hand.
 */
async function episodesInWindow(
  series: SeriesMeta,
  window: { from: string; to: string },
  log?: FastifyBaseLogger
): Promise<{ season: number; episode: number; name: string; airDate: string; still: string | null; overview: string | null }[]> {
  if (!series.tmdbId) return [];
  const tmdb = await getTmdb();
  const details = await getCachedShowDetails(tmdb, series.tmdbId);
  if (!details) return [];
  const current = Math.max(details.nextEpisodeToAir?.season_number ?? 0, details.lastEpisodeToAir?.season_number ?? 0);
  if (current < 1) return [];

  const found: Awaited<ReturnType<typeof episodesInWindow>> = [];
  for (let season = current; season >= 1 && season > current - MAX_SEASONS_PER_SERIES; season--) {
    const episodes = await loadEpisodes(series.imdbId, season, log);
    for (const ep of episodes) {
      if (!ep.airDate || !ISO_DAY.test(ep.airDate)) continue;
      if (ep.airDate < window.from || ep.airDate > window.to) continue;
      found.push({ season, episode: ep.episodeNumber, name: ep.name, airDate: ep.airDate, still: ep.still, overview: ep.overview });
    }
    // Stop once this season began on or before the window did: nothing in an
    // earlier one can fall inside it.
    const firstAired = episodes.map((e) => e.airDate).filter((d): d is string => !!d && ISO_DAY.test(d)).sort()[0];
    if (!firstAired || firstAired <= window.from) break;
  }
  return found;
}

/**
 * Everything dated inside the window that this profile would want to know
 * about:
 *
 *   - every episode of each series in progress
 *   - the premiere of each series on the watchlist that hasn't been started —
 *     only an episode 1, because the middle of a season of a show you have not
 *     begun is not news
 *   - the release of each film on the watchlist, from the release date the
 *     metadata sync already stores
 *
 * The window may lie in the past, which is what lets the month grid page back.
 * Each entry says whether it has been watched, and when Trakt is connected,
 * the instant it airs.
 */
export const getCalendar = async (
  profileId: string,
  window: CalendarWindow,
  spoilerProtection = false,
  log?: FastifyBaseLogger
): Promise<CalendarEntry[]> => {
  const end = new Date(window.start);
  end.setUTCDate(end.getUTCDate() + window.days);
  const range = { from: isoDay(window.start), to: isoDay(end) };
  const today = new Date();
  today.setUTCHours(0, 0, 0, 0);
  const allFuture = window.start.getTime() >= today.getTime();

  const [progressRows, watchlistItems] = await Promise.all([
    prisma.seriesProgress.findMany({
      where: { profileId },
      orderBy: { lastWatchedAt: "desc" },
      take: IN_PROGRESS_LIMIT,
      select: { seriesImdbId: true },
    }),
    prisma.listItem.findMany({
      where: { list: { profileId, kind: ListKind.watchlist } },
      orderBy: { addedAt: "desc" },
      select: { type: true, imdbId: true },
    }),
  ]);

  const followed = progressRows.map((p) => p.seriesImdbId);
  const followedSet = new Set(followed);
  const premiereOnly = watchlistItems
    .filter((item) => item.type === "series" && !followedSet.has(item.imdbId))
    .slice(0, WATCHLIST_SERIES_LIMIT)
    .map((item) => item.imdbId);
  const premiereOnlySet = new Set(premiereOnly);
  const movieIds = watchlistItems.filter((item) => item.type === "movie").map((item) => item.imdbId);
  const seriesIds = [...followed, ...premiereOnly];

  const [seriesMeta, films, traktTimes] = await Promise.all([
    seriesIds.length === 0
      ? Promise.resolve([] as SeriesMeta[])
      : prisma.metadata.findMany({
          where: { imdbId: { in: seriesIds }, type: MetadataType.series, tmdbId: { not: null } },
          select: { imdbId: true, tmdbId: true, name: true, poster: true, background: true, status: true },
        }),
    movieIds.length === 0
      ? Promise.resolve([])
      : prisma.metadata.findMany({
          where: {
            imdbId: { in: movieIds },
            type: MetadataType.movie,
            // Stored as text, so a range over it is a string comparison —
            // correct for well-formed YYYY-MM-DD, and the shape check below
            // drops anything that only looks close.
            releaseDate: { gte: range.from, lte: range.to },
          },
          select: { imdbId: true, name: true, poster: true, background: true, description: true, releaseDate: true },
        }),
    traktAirTimes(profileId, window, log),
  ]);

  const metaById = new Map(seriesMeta.map((m) => [m.imdbId, m]));
  // In the caller's order, so the most recently watched shows are asked first.
  const toAsk = seriesIds
    .map((id) => metaById.get(id))
    // A show that has ended has nothing in a future window, and asking costs a
    // request; one in a window that reaches into the past may have aired there.
    .filter((m): m is SeriesMeta => !!m && !(allFuture && m.status && NO_FURTHER_EPISODES.has(m.status)));

  type Raw = { series: SeriesMeta; ep: Awaited<ReturnType<typeof episodesInWindow>>[number] };
  const raw: Raw[] = [];
  let tmdbAvailable = true;
  try {
    await getTmdb();
  } catch {
    tmdbAvailable = false;
  }
  if (tmdbAvailable) {
    for (let i = 0; i < toAsk.length; i += BATCH_SIZE) {
      const batch = toAsk.slice(i, i + BATCH_SIZE);
      const results = await Promise.allSettled(batch.map((series) => episodesInWindow(series, range, log)));
      results.forEach((result, index) => {
        const series = batch[index];
        if (result.status !== "fulfilled" || !series) return;
        for (const ep of result.value) {
          if (premiereOnlySet.has(series.imdbId) && ep.episode !== 1) continue;
          raw.push({ series, ep });
        }
      });
    }
  }

  // What has been watched, in one query for the whole window.
  const watchedRows =
    raw.length === 0 && films.length === 0
      ? []
      : await prisma.watchEvent.findMany({
          where: {
            profileId,
            OR: [
              ...(raw.length > 0
                ? [{ type: WatchEventType.episode, seriesImdbId: { in: [...new Set(raw.map((r) => r.series.imdbId))] } }]
                : []),
              ...(films.length > 0 ? [{ type: WatchEventType.movie, imdbId: { in: films.map((f) => f.imdbId) } }] : []),
            ],
          },
          select: { type: true, imdbId: true, seriesImdbId: true, season: true, episode: true },
        });
  const watchedEpisodes = new Set(
    watchedRows
      .filter((w) => w.type === WatchEventType.episode && w.seriesImdbId && w.season != null && w.episode != null)
      .map((w) => episodeKey(w.seriesImdbId!, w.season!, w.episode!))
  );
  const watchedFilms = new Set(watchedRows.filter((w) => w.type === WatchEventType.movie).map((w) => w.imdbId));

  const episodeEntries: CalendarEntry[] = raw.map(({ series, ep }) => {
    const key = episodeKey(series.imdbId, ep.season, ep.episode);
    const watched = watchedEpisodes.has(key);
    // Spoiler protection guards what you haven't seen. An episode you have
    // watched can't be spoiled, so it keeps its name and still.
    const guard = spoilerProtection && !watched;
    return {
      // An episode 1 opens a season — the one date a follower most wants to know.
      kind: ep.episode === 1 ? "premiere" : "episode",
      seriesImdbId: series.imdbId,
      seriesName: series.name,
      poster: series.poster,
      still: guard ? null : ep.still,
      backdrop: series.background ?? null,
      season: ep.season,
      episode: ep.episode,
      episodeName: guard ? `Episode ${ep.episode}` : ep.name,
      airDate: ep.airDate,
      airsAt: traktTimes.get(key) ?? null,
      watched,
      overview: guard ? null : ep.overview,
    };
  });

  const movieEntries: CalendarEntry[] = films.flatMap((film) =>
    film.releaseDate && ISO_DAY.test(film.releaseDate)
      ? [
          {
            kind: "movie" as const,
            seriesImdbId: film.imdbId,
            seriesName: film.name,
            poster: film.poster,
            still: null,
            backdrop: film.background ?? null,
            season: null,
            episode: null,
            episodeName: null,
            airDate: film.releaseDate,
            airsAt: null,
            watched: watchedFilms.has(film.imdbId),
            overview: film.description ?? null,
          },
        ]
      : []
  );

  return [...episodeEntries, ...movieEntries].sort(
    (a, b) =>
      a.airDate.localeCompare(b.airDate) ||
      (a.airsAt ?? "").localeCompare(b.airsAt ?? "") ||
      KIND_ORDER[a.kind] - KIND_ORDER[b.kind] ||
      a.seriesName.localeCompare(b.seriesName) ||
      (a.season ?? 0) - (b.season ?? 0) ||
      (a.episode ?? 0) - (b.episode ?? 0)
  );
};
