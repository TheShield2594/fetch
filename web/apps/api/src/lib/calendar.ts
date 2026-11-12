import type { CalendarEntry } from "@fetch/shared";
import { ListKind, MetadataType } from "@prisma/client";
import { prisma } from "./prisma.js";
import { getNextEpisodes, getUpcomingEpisodes } from "./upcoming-episodes.js";

/**
 * How many in-progress series, and how many watchlisted ones, the calendar
 * asks TMDB about. Each is a request (cached for hours), so the two are capped
 * separately: a long watchlist must not crowd out the shows you are actually
 * watching.
 */
const IN_PROGRESS_LIMIT = 30;
const WATCHLIST_SERIES_LIMIT = 30;

/** `YYYY-MM-DD` for a UTC day, the shape `Metadata.releaseDate` is stored in. */
const isoDay = (date: Date): string => date.toISOString().slice(0, 10);
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Episodes before films on a shared day, premieres first among episodes. */
const KIND_ORDER: Record<CalendarEntry["kind"], number> = { premiere: 0, episode: 1, movie: 2 };

/**
 * Everything dated in the next `daysAhead` days that this profile would want
 * to know about:
 *
 *   - the next episode of each series in progress (as before)
 *   - the premiere of each series on the watchlist that hasn't been started —
 *     only an episode 1, because the middle of a season of a show you have not
 *     begun is not news
 *   - the release of each film on the watchlist, from the release date the
 *     metadata sync already stores
 *
 * The calendar used to be the first of those alone, which is why a watchlisted
 * film never appeared on it.
 */
export const getCalendar = async (
  profileId: string,
  daysAhead: number,
  spoilerProtection = false
): Promise<CalendarEntry[]> => {
  const [episodes, watchlistItems] = await Promise.all([
    getUpcomingEpisodes(profileId, daysAhead, IN_PROGRESS_LIMIT, spoilerProtection),
    prisma.listItem.findMany({
      where: { list: { profileId, kind: ListKind.watchlist } },
      orderBy: { addedAt: "desc" },
      select: { type: true, imdbId: true },
    }),
  ]);

  const covered = new Set(episodes.map((e) => e.seriesImdbId));
  const watchlistSeries = watchlistItems
    .filter((item) => item.type === "series" && !covered.has(item.imdbId))
    .slice(0, WATCHLIST_SERIES_LIMIT)
    .map((item) => item.imdbId);
  const watchlistMovies = watchlistItems.filter((item) => item.type === "movie").map((item) => item.imdbId);

  const today = new Date();
  today.setUTCHours(0, 0, 0, 0);
  const end = new Date(today);
  end.setUTCDate(end.getUTCDate() + daysAhead);

  const [premieres, films] = await Promise.all([
    getNextEpisodes(watchlistSeries, daysAhead, spoilerProtection).then((next) =>
      next.filter((entry) => entry.kind === "premiere")
    ),
    watchlistMovies.length === 0
      ? Promise.resolve([])
      : prisma.metadata.findMany({
          where: {
            imdbId: { in: watchlistMovies },
            type: MetadataType.movie,
            // Stored as text, so a range over it is a string comparison —
            // correct for well-formed YYYY-MM-DD, and the shape check below
            // drops anything that only looks close.
            releaseDate: { gte: isoDay(today), lte: isoDay(end) },
          },
          select: { imdbId: true, name: true, poster: true, background: true, description: true, releaseDate: true },
        }),
  ]);

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
            overview: film.description ?? null,
          },
        ]
      : []
  );

  return [...episodes, ...premieres, ...movieEntries].sort(
    (a, b) =>
      a.airDate.localeCompare(b.airDate) ||
      KIND_ORDER[a.kind] - KIND_ORDER[b.kind] ||
      a.seriesName.localeCompare(b.seriesName)
  );
};
