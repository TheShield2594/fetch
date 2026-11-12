import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { Award, BarChart3, Film, Flame, Minus, TrendingDown, TrendingUp } from "lucide-react";
import { api, type DetailedWatchStats, type SearchResult, type WatchStats, type YearInReviewStats } from "../api";
import { useCachedState } from "../hooks/useCachedState";
import { formatRating, ratingLabel } from "../utils/rating";
import { seriesBar, seriesMax } from "../utils/monthlyBars";
import { PAGE_TITLE, SECTION_TITLE, KICKER } from "../components/typography";
import { SelectField } from "../components/SelectField";
import { SectionError } from "../components/SectionError";
import { PosterGrid } from "../components/PosterGrid";
import { PosterCard } from "../components/PosterCard";
import { MyRating } from "../components/MyRating";
import { useOnTitleClose, useTitlePanel } from "../components/media-detail/titlePanel";
import { formatMinutes } from "./history-groups";

const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

type Milestone = { label: string; threshold: number; icon: typeof Film };

const MOVIE_MILESTONES: Milestone[] = [
  { label: "10 Movies", threshold: 10, icon: Film },
  { label: "50 Movies", threshold: 50, icon: Film },
  { label: "100 Movies", threshold: 100, icon: Film },
  { label: "250 Movies", threshold: 250, icon: Film },
  { label: "500 Movies", threshold: 500, icon: Film },
];

const EPISODE_MILESTONES: Milestone[] = [
  { label: "100 Episodes", threshold: 100, icon: BarChart3 },
  { label: "500 Episodes", threshold: 500, icon: BarChart3 },
  { label: "1,000 Episodes", threshold: 1000, icon: BarChart3 },
  { label: "2,500 Episodes", threshold: 2500, icon: BarChart3 },
];

const STREAK_MILESTONES: Milestone[] = [
  { label: "3-Day Streak", threshold: 3, icon: Flame },
  { label: "7-Day Streak", threshold: 7, icon: Flame },
  { label: "30-Day Streak", threshold: 30, icon: Flame },
  { label: "100-Day Streak", threshold: 100, icon: Flame },
];

function nextMilestone(milestones: Milestone[], value: number) {
  return milestones.find((m) => value < m.threshold) ?? null;
}

const PANEL = "glass-panel rounded-2xl p-5";
const PANEL_STYLE = { border: "1px solid var(--border)", background: "var(--bg-1)" } as const;

const plural = (n: number, one: string, many: string) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** "Film" / "Series" — the caption under a poster, not the API's `movie` / `series`. */
const kindLabel = (type: string): string => (type === "movie" ? "Film" : "Series");

/** What the title panel needs to open on a poster from this page. */
const toSearchResult = (item: { imdbId: string; type: string; name: string | null; poster: string | null }): SearchResult => ({
  imdbId: item.imdbId,
  type: item.type === "movie" ? "movie" : "series",
  name: item.name ?? item.imdbId,
  year: null,
  poster: item.poster,
  description: null,
  genres: [],
  rating: null,
  inWatchlist: false,
  inCollection: false,
  lists: [],
});

/** The library, narrowed to a genre — where every genre on this page leads. */
const genreHref = (genre: string): string => `/?genre=${encodeURIComponent(genre)}`;
/** History, opened at a month — where every month on this page leads. */
const monthHref = (month: string): string => `/history?month=${month}`;

/**
 * The stats page: a personal record of what was watched.
 *
 * It leads with the year — in the reader's own artwork, not in tiles — and
 * everything on it goes somewhere: a poster opens the title, a genre opens the
 * library filtered to it, a month opens History at that month. It used to be a
 * dashboard with no way out: four stat tiles, a chart and two grids of posters
 * that were pictures rather than links.
 */
export function StatsPage() {
  const [stats, setStats, statsMeta] = useCachedState<WatchStats | null>("stats:summary", null);
  const [detailed, setDetailed, detailedMeta] = useCachedState<DetailedWatchStats | null>("stats:detailed", null);
  const [loading, setLoading] = useState(!statsMeta.hadCachedValue || !detailedMeta.hadCachedValue);
  const [error, setError] = useState<string | null>(null);
  const { openTitle } = useTitlePanel();

  // The year you're in, not the one that just ended. A "year in review" that
  // opens on last year reads as stale for the eleven months after January —
  // the current year is listed right above it in the picker, empty-looking by
  // comparison, and nothing says the panel isn't showing it.
  const [year, setYear] = useState(() => new Date().getFullYear());
  // Keyed by the year it answers for, so a slow response for the year just
  // left is never drawn under the year just picked.
  const [yearReview, setYearReview] = useState<YearInReviewStats | null>(null);
  const [yearError, setYearError] = useState<{ year: number; message: string } | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [s, d] = await Promise.all([
        api.getWatchStats(signal),
        api.getDetailedStats(signal),
      ]);
      if (signal?.aborted) return;
      setStats(s);
      setDetailed(d);
    } catch (err) {
      if (signal?.aborted) return;
      setError(err instanceof Error ? err.message : "Failed to load stats");
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
    // Both come from useCachedState, which memoises its setter on the cache key
    // — constant here — so this is a truthful dependency list rather than a new
    // callback on every render.
  }, [setStats, setDetailed]);

  // Cancelled on unmount so a response can't land under whoever is signed in by
  // the time it arrives — leaving a page is as much a reason to drop the answer
  // as switching profile is.
  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  // The panel can log a watch or change a rating, and every figure here is
  // built from those. Closing it asks for all three again.
  const [yearReload, setYearReload] = useState(0);
  useOnTitleClose(() => {
    void load();
    setYearReload((n) => n + 1);
  });

  useEffect(() => {
    let cancelled = false;
    api.getYearInReview(year)
      .then((data) => { if (!cancelled) { setYearReview(data); setYearError(null); } })
      .catch((err) => {
        if (!cancelled) setYearError({ year, message: err instanceof Error ? err.message : "Failed to load year in review" });
      });
    return () => { cancelled = true; };
  }, [year, yearReload]);

  const review = yearReview?.year === year ? yearReview : null;
  const reviewError = yearError?.year === year ? yearError.message : null;

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-3">
      {/* "Stats", as the navigation calls it. */}
      <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Stats</h1>
    </div>
  );

  if (error) {
    return (
      <div className="mx-auto max-w-4xl space-y-6">
        {header}
        <SectionError
          variant="page"
          message={error}
          onRetry={() => {
            setError(null);
            setLoading(true);
            void load();
          }}
        />
      </div>
    );
  }

  if (loading) {
    return (
      <div className="mx-auto max-w-4xl space-y-6">
        {header}
        <div className="skeleton h-10 w-2/3 rounded-xl" />
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-5">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className={`skeleton aspect-poster rounded-2xl ${i > 2 ? "hidden sm:block" : ""}`} />
          ))}
        </div>
        <div className="skeleton h-64 rounded-2xl" />
      </div>
    );
  }

  /*
   * A profile with no watch history has nothing to compute statistics from, and
   * the page rendered that as four zero tiles, a flat chart, an empty genre list
   * and "No badges earned yet — keep watching!" — a screen that reads as a
   * failure and offers no way out of it. Every other page says what its empty
   * state means and points somewhere; this was the one that said nothing.
   *
   * `stats` is non-null by here (the error and loading branches have both
   * returned), and the two counts are of watch-event rows, so no rows means
   * both are zero — the same condition as `totalPlays === 0`, stated in the
   * terms the figures below use.
   */
  if (stats && stats.totalMovies === 0 && stats.totalEpisodes === 0) {
    return (
      <div className="mx-auto max-w-4xl space-y-6">
        {header}
        <div className={`${PANEL} p-8 text-center`} style={PANEL_STYLE}>
          <BarChart3 className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
          <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
            Nothing watched yet, so there is nothing to count.
          </p>
          {/* The expected state for a new profile, not a fault — and the reason
              the streaks and badges below are missing rather than broken. */}
          <p className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
            Every figure here — streaks, genres, badges, your year in review — is built from your watch history.
          </p>
          <Link to="/search" className="mt-2 inline-block py-1 text-sm font-medium text-claw-text underline-offset-2 transition-colors hover:underline">
            Find something to watch &rarr;
          </Link>
        </div>
      </div>
    );
  }

  const momComparison = (() => {
    // Two months is the minimum a month-on-month comparison has anything to
    // say about, and having both of these is that same condition.
    const current = detailed?.monthly.at(-1);
    const previous = detailed?.monthly.at(-2);
    if (!current || !previous) return null;
    const currentTotal = current.movies + current.episodes;
    const previousTotal = previous.movies + previous.episodes;
    const diff = currentTotal - previousTotal;
    const percent = previousTotal > 0 ? Math.round((diff / previousTotal) * 100) : null;
    return { diff, percent };
  })();

  const maxGenreCount = detailed?.genreDistribution[0]?.count ?? 1;
  const mostWatched = review?.mostWatched ?? [];
  const yearIsEmpty = review !== null && review.totalMovies === 0 && review.totalEpisodes === 0;
  const busiestMonth =
    review && review.busiestMonth !== null
      ? { name: MONTH_NAMES[review.busiestMonth], key: `${year}-${String(review.busiestMonth + 1).padStart(2, "0")}` }
      : null;

  return (
    <div className="mx-auto max-w-4xl space-y-10">
      {/* ── The year, first ─────────────────────────────────────────────
          The page is a record of a year of watching, so it opens on the year
          and on the picker that changes it. That picker used to sit beside the
          last block on the page, below everything it didn't affect. */}
      <section aria-labelledby="stats-year" className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Stats</h1>
          <SelectField
            aria-label="Year in review: year"
            value={year}
            onChange={(e) => setYear(Number(e.target.value))}
            className="rounded-lg px-3 py-1.5 text-sm"
          >
            {Array.from({ length: 6 }, (_, i) => new Date().getFullYear() - i).map((y) => (
              <option key={y} value={y}>{y}</option>
            ))}
          </SelectField>
        </div>

        <div>
          <h2 id="stats-year" className={KICKER} style={{ color: "var(--text-mute)" }}>{year} in review</h2>
          {reviewError ? (
            <p role="alert" className="mt-2 text-sm text-danger">{reviewError}</p>
          ) : !review ? (
            <div className="skeleton mt-3 h-10 w-2/3 rounded-xl" />
          ) : yearIsEmpty ? (
            <p className="mt-2 text-xl font-semibold" style={{ color: "var(--text-dim)" }}>
              Nothing logged in {year}.
            </p>
          ) : (
            <>
              {/* Said as a sentence, in the page's largest type, rather than
                  boxed into four tiles: these are one fact about one year. */}
              <p className="mt-2 text-balance text-[1.75rem] font-bold leading-tight tracking-[-0.02em] sm:text-[2.25rem]" style={{ color: "var(--text)" }}>
                {[
                  review.totalMovies > 0 ? plural(review.totalMovies, "film", "films") : null,
                  review.totalEpisodes > 0 ? plural(review.totalEpisodes, "episode", "episodes") : null,
                ].filter(Boolean).join(" and ")}
                .
              </p>
              <p className="mt-1.5 text-[0.9375rem]" style={{ color: "var(--text-dim)" }}>
                {review.totalRuntimeMinutes > 0 && <>About {formatMinutes(review.totalRuntimeMinutes)} of watching. </>}
                {busiestMonth?.name && (
                  <>
                    Busiest in{" "}
                    <Link to={monthHref(busiestMonth.key)} className="font-medium text-claw-text underline-offset-2 hover:underline">
                      {busiestMonth.name}
                    </Link>
                    , with {review.busiestMonthCount.toLocaleString()} watched.
                  </>
                )}
              </p>
            </>
          )}
        </div>

        {/* The year in its own artwork: what was actually watched, most first. */}
        {mostWatched.length > 0 && (
          <div>
            <h3 className={`mb-3 ${KICKER}`} style={{ color: "var(--text-dim)" }}>Most watched</h3>
            <PosterGrid density="compact">
              {mostWatched.slice(0, 10).map((item, index) => (
                <PosterCard
                  key={`${item.type}:${item.imdbId}`}
                  poster={item.poster}
                  name={item.name}
                  eager={index < 5}
                  onOpen={() => openTitle(toSearchResult(item))}
                >
                  <p className="mt-1.5 truncate text-sm font-medium" style={{ color: "var(--text)" }}>{item.name}</p>
                  <p className="meta-row" style={{ color: "var(--text-mute)" }}>
                    {item.type === "series"
                      ? plural(item.plays, "episode", "episodes")
                      : item.plays > 1 ? `Film · ${item.plays} plays` : "Film"}
                  </p>
                </PosterCard>
              ))}
            </PosterGrid>
          </div>
        )}

        {review && review.topRated.length > 0 && (
          <div>
            <h3 className={`mb-3 ${KICKER}`} style={{ color: "var(--text-dim)" }}>Your top rated</h3>
            <PosterGrid density="compact">
              {review.topRated.map((item) => (
                <PosterCard
                  key={`${item.type}:${item.imdbId}`}
                  poster={item.poster}
                  name={item.name ?? item.imdbId}
                  onOpen={() => openTitle(toSearchResult(item))}
                >
                  <p className="mt-1.5 truncate text-sm font-medium" style={{ color: "var(--text)" }}>{item.name ?? item.imdbId}</p>
                  {/* Your own rating wears stars, here and on History. A
                      community score is a number — see the grid further down. */}
                  <p className="meta-row flex items-center gap-2" style={{ color: "var(--text-mute)" }}>
                    <MyRating rating={item.rating} />
                    {kindLabel(item.type)}
                  </p>
                </PosterCard>
              ))}
            </PosterGrid>
          </div>
        )}

        {review && review.topGenres.length > 0 && (
          <div>
            <h3 className={`mb-2 ${KICKER}`} style={{ color: "var(--text-dim)" }}>Genres this year</h3>
            <ul className="flex flex-wrap gap-2">
              {review.topGenres.map((g) => (
                <li key={g.genre}>
                  <Link
                    to={genreHref(g.genre)}
                    aria-label={`${g.genre}, ${g.count} watched this year. Show ${g.genre} in your library`}
                    className="inline-block rounded-full px-3 py-1.5 text-xs transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
                    style={{ border: "1px solid var(--border)", background: "var(--surface-strong)", color: "var(--text)" }}
                  >
                    {g.genre} <span style={{ color: "var(--text-mute)" }}>{g.count}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {/* ── Monthly activity ── */}
      {detailed && detailed.monthly.length > 0 && (
        <section className={PANEL} style={PANEL_STYLE} aria-labelledby="stats-monthly">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
            <h2 id="stats-monthly" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Last 12 months</h2>
            {momComparison && (
              <span
                className={`flex items-center gap-1 text-xs font-medium ${
                  momComparison.diff > 0
                    ? "text-success"
                    : momComparison.diff < 0
                      ? "text-danger"
                      : ""
                }`}
                style={momComparison.diff === 0 ? { color: "var(--text-dim)" } : undefined}
              >
                {momComparison.diff > 0 ? (
                  <TrendingUp className="h-3.5 w-3.5" />
                ) : momComparison.diff < 0 ? (
                  <TrendingDown className="h-3.5 w-3.5" />
                ) : (
                  <Minus className="h-3.5 w-3.5" />
                )}
                {momComparison.diff > 0 ? "+" : ""}
                {momComparison.diff}
                {momComparison.percent !== null ? ` (${momComparison.diff > 0 ? "+" : ""}${momComparison.percent}%)` : ""} vs last month
              </span>
            )}
          </div>
          <MonthlyChart monthly={detailed.monthly} />
        </section>
      )}

      {/* ── Genres, all time ── */}
      {detailed && detailed.genreDistribution.length > 0 && (
        <section className={PANEL} style={PANEL_STYLE} aria-labelledby="stats-genres">
          <h2 id="stats-genres" className={`mb-3 ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>Genres</h2>
          <ul>
            {detailed.genreDistribution.map((g) => (
              <li key={g.genre}>
                {/* The whole row is the link: the name, the bar and the count
                    are one thing to click, and it opens the library on it. */}
                <Link
                  to={genreHref(g.genre)}
                  aria-label={`${g.genre}, ${g.count} watched. Show ${g.genre} in your library`}
                  className="group -mx-2 flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                >
                  <span className="w-28 shrink-0 truncate text-sm group-hover:text-claw-text" style={{ color: "var(--text)" }}>{g.genre}</span>
                  <span className="h-2 flex-1 overflow-hidden rounded-full" style={{ background: "var(--surface-strong)" }}>
                    <span
                      className="block h-full rounded-full bg-claw-500/80 transition-[width] duration-slow"
                      style={{ width: `${(g.count / maxGenreCount) * 100}%` }}
                    />
                  </span>
                  <span className="meta w-8 text-right" style={{ color: "var(--text-mute)" }}>{g.count}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── Highest rated among what was watched ── */}
      {detailed && detailed.topRated.length > 0 && (
        <section aria-labelledby="stats-top-rated">
          <h2 id="stats-top-rated" className={`mb-1 ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>
            Best reviewed
          </h2>
          <p className="mb-3 text-sm" style={{ color: "var(--text-dim)" }}>
            Of everything you have watched, by TMDB score.
          </p>
          <PosterGrid density="compact">
            {detailed.topRated.map((item) => (
              <PosterCard
                key={`${item.type}:${item.imdbId}`}
                poster={item.poster}
                name={item.name}
                onOpen={() => openTitle(toSearchResult(item))}
              >
                <p className="mt-1.5 truncate text-sm font-medium" style={{ color: "var(--text)" }}>{item.name}</p>
                {/* A community score: a number, with whose it is beside it. */}
                <p className="meta-row" style={{ color: "var(--text-mute)" }}>
                  {kindLabel(item.type)}
                  {item.rating != null && (
                    <>
                      {" · "}
                      <span role="img" aria-label={`TMDB: ${ratingLabel(item.rating)}`}>TMDB {formatRating(item.rating)}</span>
                    </>
                  )}
                </p>
              </PosterCard>
            ))}
          </PosterGrid>
        </section>
      )}

      {/* ── All time ── */}
      {stats && (
        <section aria-labelledby="stats-all-time" className="space-y-4">
          <h2 id="stats-all-time" className={SECTION_TITLE} style={{ color: "var(--text)" }}>All time</h2>
          {/* One ruled row of figures rather than four bordered tiles. */}
          <dl className={`${PANEL} grid grid-cols-2 gap-y-4 sm:grid-cols-4`} style={PANEL_STYLE}>
            {[
              { label: "Films", value: stats.totalMovies.toLocaleString() },
              { label: "Episodes", value: stats.totalEpisodes.toLocaleString() },
              { label: "Current streak", value: plural(detailed?.currentStreak ?? 0, "day", "days") },
              { label: "Longest streak", value: plural(detailed?.longestStreak ?? 0, "day", "days") },
            ].map((figure, i) => (
              <div
                key={figure.label}
                className={`flex flex-col-reverse gap-1 px-1 sm:px-4 ${i > 0 ? "sm:border-l" : ""} ${i % 2 === 1 ? "border-l pl-4 sm:pl-4" : ""}`}
                style={{ borderColor: "var(--border)" }}
              >
                <dt className="meta-row" style={{ color: "var(--text-mute)" }}>{figure.label}</dt>
                <dd className="text-2xl font-bold leading-none tabular-nums" style={{ color: "var(--text)" }}>{figure.value}</dd>
              </div>
            ))}
          </dl>
          <MilestoneBadges
            totalMovies={stats.totalMovies}
            totalEpisodes={stats.totalEpisodes}
            longestStreak={detailed?.longestStreak ?? 0}
          />
        </section>
      )}
    </div>
  );
}

/**
 * The last twelve months, as two series on two scales.
 *
 * Episodes above, films below, each band scaled to its own tallest month — see
 * `utils/monthlyBars` for why they stopped sharing an axis. Every month is a
 * link to History at that month, and its accessible name carries both counts,
 * so the chart is its own text alternative: twelve links, each saying what its
 * column shows.
 *
 * Twelve columns have a minimum readable width, so on a narrow screen the
 * overflow scrolls inside the chart rather than being clipped at the page edge
 * — a chart is one of the things SC 1.4.10 allows to scroll sideways.
 */
function MonthlyChart({ monthly }: { monthly: DetailedWatchStats["monthly"] }) {
  const episodeMax = seriesMax(monthly.map((m) => m.episodes));
  const filmMax = seriesMax(monthly.map((m) => m.movies));

  const band = (value: number, max: number, color: string, heightClass: string) => {
    const bar = seriesBar(value, max);
    return (
      <span className={`relative flex w-full flex-col justify-end ${heightClass}`}>
        {/* Rides its own bar: `bottom` is the bar's height, so the number
            sits on it whatever that height turns out to be. */}
        {bar.value > 0 && (
          <span
            className="meta pointer-events-none absolute inset-x-0 text-center"
            style={{ bottom: `calc(${bar.height}% + 2px)`, color: "var(--text-mute)" }}
          >
            {bar.value}
          </span>
        )}
        {bar.value > 0 ? (
          <span className={`block w-full rounded-t transition-[height,opacity] duration-slow ${color}`} style={{ height: `${bar.height}%` }} />
        ) : (
          <span
            className="block w-full rounded border border-dashed"
            style={{ height: `${bar.height}%`, minHeight: "3px", background: "var(--surface-strong)", borderColor: "var(--border-strong)" }}
          />
        )}
      </span>
    );
  };

  return (
    <div>
      <div className="-mx-1 overflow-x-auto px-1 pb-1">
        <ol className="flex min-w-[21rem] gap-1 sm:gap-1.5" aria-label="Films and episodes watched in each of the last 12 months">
          {monthly.map((m) => {
            const date = new Date(m.month + "-15");
            const full = date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
            return (
              <li key={m.month} className="min-w-0 flex-1">
                <Link
                  to={monthHref(m.month)}
                  aria-label={`${full}: ${plural(m.episodes, "episode", "episodes")}, ${plural(m.movies, "film", "films")}. Open in History`}
                  title={`${full} — open in History`}
                  className="group flex flex-col items-center gap-1 rounded-lg px-0.5 pb-1 pt-4 transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                >
                  {band(m.episodes, episodeMax, "bg-plum-500/70 group-hover:bg-plum-500", "h-[104px]")}
                  {/* The films' band, under a hairline: a second axis, not a
                      continuation of the first. */}
                  <span className="mt-4 block w-full border-t pt-4" style={{ borderColor: "var(--border)" }}>
                    {band(m.movies, filmMax, "bg-claw-500/70 group-hover:bg-claw-500", "h-[56px]")}
                  </span>
                  <span className="meta-row" style={{ color: "var(--text-mute)" }}>
                    {date.toLocaleDateString(undefined, { month: "short" })}
                  </span>
                </Link>
              </li>
            );
          })}
        </ol>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs" style={{ color: "var(--text-dim)" }}>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-sm bg-plum-500/70" /> Episodes
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-sm bg-claw-500/70" /> Films
        </span>
        <span style={{ color: "var(--text-mute)" }}>Each on its own scale. Pick a month to see it in History.</span>
      </div>
    </div>
  );
}

function MilestoneBadges({
  totalMovies,
  totalEpisodes,
  longestStreak,
}: {
  totalMovies: number;
  totalEpisodes: number;
  longestStreak: number;
}) {
  const groups = [
    { value: totalMovies, milestones: MOVIE_MILESTONES },
    { value: totalEpisodes, milestones: EPISODE_MILESTONES },
    { value: longestStreak, milestones: STREAK_MILESTONES },
  ];

  const earned = groups.flatMap(({ value, milestones }) =>
    milestones.filter((m) => value >= m.threshold)
  );
  const upcoming = groups
    .map(({ value, milestones }) => {
      const next = nextMilestone(milestones, value);
      return next ? { ...next, value } : null;
    })
    .filter((m): m is Milestone & { value: number } => m !== null);

  if (earned.length === 0 && upcoming.length === 0) return null;

  return (
    <section className="glass-panel rounded-2xl p-5" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
      <h2 className={`mb-4 flex items-center gap-2 ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>
        <Award className="h-5 w-5 text-warning" /> Milestones
      </h2>
      {earned.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {earned.map((m) => (
            <span
              key={m.label}
              className="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium text-warning"
              style={{ border: "1px solid rgba(245,158,11,0.3)", background: "rgba(245,158,11,0.1)" }}
            >
              <m.icon className="h-3.5 w-3.5" /> {m.label}
            </span>
          ))}
        </div>
      ) : (
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>No badges earned yet — keep watching!</p>
      )}
      {upcoming.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {upcoming.map((m) => (
            <span
              key={m.label}
              className="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium"
              style={{ border: "1px solid var(--border)", background: "var(--surface-strong)", color: "var(--text-dim)" }}
            >
              <m.icon className="h-3.5 w-3.5" /> {m.label} ({m.value}/{m.threshold})
            </span>
          ))}
        </div>
      )}
    </section>
  );
}
