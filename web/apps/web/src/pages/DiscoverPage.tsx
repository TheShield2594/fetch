import { useEffect, useState, useCallback, useRef } from "react";
import { Film, Tv, TrendingUp, Clock } from "lucide-react";
import {
  api,
  ApiError,
  type CalendarEntry,
  type CheckIn,
  type DetailedWatchStats,
  runtimeConfig,
  type ScrobbleSession,
  type SearchResult,
  type SeriesProgress,
  type TrendingMeta,
  type WatchEvent,
  type WatchStats,
} from "../api";
import { Link } from "react-router";
import { CarouselTrack } from "../components/CarouselTrack";
import { useHorizontalScroll } from "../components/carousel-utils";
import { useOnTitleClose, useTitlePanel } from "../components/media-detail/titlePanel";
import { historyItemImdbId, watchEventTitle } from "../utils/watchEvents";
import { timeUntil } from "../utils/timeAgo";
import { loadErrorMessage } from "../utils/loadError";
import { useCachedState } from "../hooks/useCachedState";
import { ScrollArrows } from "../components/ScrollArrows";
import { SectionHeader } from "../components/SectionHeader";
import { SectionError } from "../components/SectionError";
import { LoadErrorBanner } from "../components/LoadErrorBanner";
import { useDiscoverSection } from "../hooks/useDiscoverSection";
import { CarouselSkeleton } from "../components/discover/CarouselSkeleton";
import { ContinueWatchingCard } from "../components/discover/ContinueWatchingCard";
import { ContinueWatchingHero } from "../components/discover/ContinueWatchingHero";
import { DiscoverHeader, PageHeading } from "../components/discover/DiscoverHeader";
import { DiscoverSubRow } from "../components/discover/DiscoverSubRow";
import { DiscoveryCard } from "../components/discover/DiscoveryCard";
import { NowPlayingList } from "../components/discover/NowPlayingList";
import { NowWatchingHero } from "../components/discover/NowWatchingHero";
import { RecentlyWatchedCard } from "../components/discover/RecentlyWatchedCard";
import { recentTitles } from "./history-groups";
import { UpcomingList } from "../components/discover/UpcomingList";

/** What `GET /recommendations/ai` answers with. */
type AiRecommendationsResponse = { metas: TrendingMeta[]; reasons?: Record<string, string> | undefined };

/* ─── Main component ─── */

export function DiscoverPage() {
  // Each section keeps its data in the shared cache, so leaving Discover
  // and coming back paints the same content in the first frame rather than
  // spinning while the requests it already made are made again. The loads below
  // are unchanged and still run on mount — they now refresh what is on screen
  // instead of replacing it.
  const [progress, setProgress, progressMeta] = useCachedState<SeriesProgress[]>("dash:progress", []);
  const [history, setHistory, historyMeta] = useCachedState<WatchEvent[]>("dash:history", []);
  const [stats, setStats] = useCachedState<WatchStats | null>("dash:stats", null);

  const [loading, setLoading] = useState(!progressMeta.hadCachedValue && !historyMeta.hadCachedValue);
  const [error, setError] = useState<string | null>(null);

  const [trendingNeedsTmdb, setTrendingNeedsTmdb] = useState(false);
  const [aiActive, setAiActive] = useState(false);
  const [aiLastGeneratedAt, setAiLastGeneratedAt] = useState<string | null>(null);
  const [movieReasons, setMovieReasons] = useState<Record<string, string>>({});
  const [seriesReasons, setSeriesReasons] = useState<Record<string, string>>({});

  // Each rail loads, fails and retries on its own, so each gets its own
  // section — which is what keeps a Retry on one from invalidating a request
  // still in flight beside it. See `useDiscoverSection` for the guard.
  const detailed = useDiscoverSection<DetailedWatchStats | null>(
    "dash:detailed-stats",
    null,
    useCallback(() => api.getDetailedStats(), []),
  );

  const trending = useDiscoverSection<TrendingMeta[], { metas: TrendingMeta[] }>(
    "dash:trending",
    [],
    useCallback(() => api.getTrending("movie", "week"), []),
    {
      apply: (res, set) => {
        set(res.metas ?? []);
        // A key added in Settings since the last failure: without this the
        // "needs a TMDB API key" note outlived the key it asked for.
        setTrendingNeedsTmdb(false);
      },
      // An unconfigured TMDB key is the one failure worth naming: the rail then
      // says what to go and set rather than offering a Retry that cannot work.
      onError: useCallback((err: unknown) => {
        setTrendingNeedsTmdb(
          (err instanceof ApiError && err.code === "tmdb_not_configured") ||
            (err instanceof Error && /tmdb/i.test(err.message))
        );
      }, []),
    },
  );

  const movieRecs = useDiscoverSection<TrendingMeta[], AiRecommendationsResponse>(
    "dash:recs:movie",
    [],
    useCallback(() => api.getAiRecommendations("movie", 20), []),
    {
      apply: useCallback((res: AiRecommendationsResponse, set: (v: TrendingMeta[]) => void) => {
        set(res.metas ?? []);
        setMovieReasons(res.reasons ?? {});
      }, []),
    },
  );

  const seriesRecsSection = useDiscoverSection<TrendingMeta[], AiRecommendationsResponse>(
    "dash:recs:series",
    [],
    useCallback(() => api.getAiRecommendations("series", 20), []),
    {
      apply: useCallback((res: AiRecommendationsResponse, set: (v: TrendingMeta[]) => void) => {
        set(res.metas ?? []);
        setSeriesReasons(res.reasons ?? {});
      }, []),
    },
  );

  const calendar = useDiscoverSection<CalendarEntry[], { calendar: CalendarEntry[] }>(
    "dash:calendar",
    [],
    useCallback(() => api.getCalendar(14), []),
    { apply: (res, set) => set(res.calendar ?? []) },
  );

  const detailedStats = detailed.value;
  const trendingMovies = trending.value;
  const recommendations = movieRecs.value;
  const seriesRecs = seriesRecsSection.value;
  const calendarEntries = calendar.value;

  const [markingNext, setMarkingNext] = useState<Set<string>>(new Set());
  const [markedDone, setMarkedDone] = useState<Set<string>>(new Set());
  const [activeCheckin, setActiveCheckin] = useState<CheckIn | null>(null);
  const [nowPlaying, setNowPlaying] = useState<ScrobbleSession[]>([]);

  const continueScroll = useHorizontalScroll();
  const recentScroll = useHorizontalScroll();
  const recsScroll = useHorizontalScroll();
  const seriesRecsScroll = useHorizontalScroll();

  const { openTitle } = useTitlePanel();

  const toSearchResult = useCallback((imdbId: string, type: "movie" | "series", name: string, opts?: {
    poster?: string | undefined;
    year?: number | null | undefined;
    description?: string | null | undefined;
    genres?: string[] | undefined;
    rating?: number | null | undefined;
    background?: string | null | undefined;
  }): SearchResult => ({
    imdbId, type, name,
    year: opts?.year ?? null,
    poster: opts?.poster ?? null,
    description: opts?.description ?? null,
    genres: opts?.genres ?? [],
    rating: opts?.rating ?? null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
    background: opts?.background ?? null,
  }), []);

  // Takes the signal the mount effect cancels on unmount. Five requests that
  // outlive the page are five answers arriving for a profile that may no longer
  // be the one on screen — a switch mid-flight used to land the previous
  // profile's Continue Watching in the new one's cache.
  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [progressRes, historyRes, statsRes, checkinRes, nowPlayingRes] = await Promise.all([
        api.getSeriesProgress(signal),
        api.getWatchHistory(20, 0, { signal }),
        api.getWatchStats(signal),
        api.getCheckin(signal).catch(() => ({ checkin: null })),
        api.getNowPlaying(signal).catch(() => ({ sessions: [] })),
      ]);
      if (signal?.aborted) return;
      setProgress(progressRes ?? []);
      setHistory(historyRes ?? []);
      setStats(statsRes);
      setActiveCheckin(checkinRes.checkin);
      setNowPlaying(nowPlayingRes.sessions ?? []);
      setError(null);
    } catch (err) {
      if (signal?.aborted) return;
      setError(loadErrorMessage(err, "Discover"));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
    // The `setX` names below all come from useCachedState, which memoises its
    // setter on the cache key — a string literal at every call site here — so
    // every one of these dependency lists is stable across renders. They are
    // named rather than omitted so the lists describe what the closures
    // actually read, which is what makes exhaustive-deps worth enforcing.
  }, [setProgress, setHistory, setStats]);

  const refreshProgress = useCallback(async () => {
    try {
      const progressRes = await api.getSeriesProgress();
      setProgress(progressRes ?? []);
    } catch { /* keep showing the last known list */ }
  }, [setProgress]);

  // The panel can mark episodes watched, which moves the rows on this page.
  useOnTitleClose(() => { void refreshProgress(); });

  const profileId = runtimeConfig.getProfileId();

  // AI config gates both rails: a failure here means neither can be fetched, so
  // it surfaces as a failure on both rather than as two empty rows.
  //
  // Destructured rather than closing over the two section objects: those are
  // rebuilt on every render, so depending on them would make this callback new
  // on every render too — and the effect that calls it runs on its identity.
  // Everything below is stable for the life of the section.
  const {
    claim: claimMovieRecs,
    isCurrent: movieRecsIsCurrent,
    load: loadMovieRecs,
    setLoading: setMovieRecsLoading,
    setFailed: setMovieRecsFailed,
  } = movieRecs;
  const {
    claim: claimSeriesRecs,
    isCurrent: seriesRecsIsCurrent,
    load: loadSeriesRecs,
    setLoading: setSeriesRecsLoading,
    setFailed: setSeriesRecsFailed,
  } = seriesRecsSection;

  const loadAiSection = useCallback(async () => {
    // Both rails restart from here, so both tokens move — an answer either rail
    // had in flight belongs to the previous run of this section and is no
    // longer the one on screen.
    const movieToken = claimMovieRecs();
    const seriesToken = claimSeriesRecs();
    const superseded = () => !movieRecsIsCurrent(movieToken) || !seriesRecsIsCurrent(seriesToken);
    setMovieRecsLoading(true);
    setSeriesRecsLoading(true);
    setMovieRecsFailed(false);
    setSeriesRecsFailed(false);
    try {
      const configRes = await api.getAiConfig();
      if (superseded()) return;
      setAiActive(configRes.configured);
      setAiLastGeneratedAt(configRes.lastGeneratedAt ?? null);

      if (!configRes.configured) {
        setMovieRecsLoading(false);
        setSeriesRecsLoading(false);
        return;
      }

      void loadMovieRecs();
      void loadSeriesRecs();
    } catch (err) {
      console.error("Failed to fetch AI config:", err);
      if (superseded()) return;
      setMovieRecsFailed(true);
      setSeriesRecsFailed(true);
      setMovieRecsLoading(false);
      setSeriesRecsLoading(false);
    }
  }, [
    claimMovieRecs,
    claimSeriesRecs,
    movieRecsIsCurrent,
    seriesRecsIsCurrent,
    loadMovieRecs,
    loadSeriesRecs,
    setMovieRecsLoading,
    setSeriesRecsLoading,
    setMovieRecsFailed,
    setSeriesRecsFailed,
  ]);

  const loadDetailed = detailed.load;
  const loadTrending = trending.load;
  const loadCalendar = calendar.load;
  useEffect(() => {
    void loadDetailed();
    void loadTrending();
    void loadAiSection();
    void loadCalendar();
  }, [profileId, loadDetailed, loadTrending, loadAiSection, loadCalendar]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, profileId]);

  // The "marked!" confirmation on a Continue Watching card holds for 1.2s and
  // then reloads Discover, so the timer that does it can outlive the page:
  // mark an episode, tap through to Settings inside that window, and five
  // requests fire for a page that no longer exists — and their answers are then
  // written into the shared cache, under whichever profile is active by the
  // time they land. One timer per card, since several can be marked at once.
  const markTimersRef = useRef(new Set<ReturnType<typeof setTimeout>>());
  // Read by the failure path in `handleMarkNext`, which reloads from inside an
  // async handler that can resume after the page is gone.
  const mountedRef = useRef(true);
  useEffect(() => {
    // Assigned on mount rather than only at declaration: a Strict Mode
    // remount reuses the ref, and the first cleanup would otherwise leave the
    // second mount marked unmounted for the rest of its life.
    mountedRef.current = true;
    const timers = markTimersRef.current;
    return () => {
      mountedRef.current = false;
      for (const timer of timers) clearTimeout(timer);
      timers.clear();
    };
  }, []);

  // Pulled out of the rail objects rather than called as methods on them.
  // useHorizontalScroll hands back a fresh object literal every render, so a
  // dependency on `continueScroll` re-arms this timer on every render; the
  // `checkScroll` it carries is a useCallback with an empty dependency list and
  // never changes. Same four calls, but now the dependency list says so.
  const { checkScroll: checkContinueScroll } = continueScroll;
  const { checkScroll: checkRecentScroll } = recentScroll;
  const { checkScroll: checkRecsScroll } = recsScroll;
  const { checkScroll: checkSeriesRecsScroll } = seriesRecsScroll;

  useEffect(() => {
    if (loading) return;
    const timer = setTimeout(() => {
      checkContinueScroll();
      checkRecentScroll();
      checkRecsScroll();
      checkSeriesRecsScroll();
    }, 50);
    return () => clearTimeout(timer);
  }, [loading, trending.loading, movieRecs.loading, seriesRecsSection.loading, progress.length, history.length,
    checkContinueScroll, checkRecentScroll, checkRecsScroll, checkSeriesRecsScroll]);

  const handleMarkNext = async (imdbId: string) => {
    setMarkingNext((prev) => new Set(prev).add(imdbId));
    try {
      await api.markNextEpisodeWatched(imdbId);
      // The request can outlast the page, and the cleanup below has then already
      // run: a timer armed past it is one nothing will ever clear, which is the
      // leak this whole path is about, reintroduced a second later.
      if (!mountedRef.current) return;
      setMarkedDone((prev) => new Set(prev).add(imdbId));
      const timer = setTimeout(() => {
        markTimersRef.current.delete(timer);
        setMarkedDone((prev) => { const next = new Set(prev); next.delete(imdbId); return next; });
        void load();
      }, 1200);
      markTimersRef.current.add(timer);
    } catch {
      // Mark failed (e.g. the series turned out to already be fully watched) —
      // reload so a now-stale card can be dropped from the list. Same reason the
      // timer above is cleared on unmount: the await this resumes from can land
      // after the page is gone.
      if (mountedRef.current) void load();
    } finally {
      setMarkingNext((prev) => { const next = new Set(prev); next.delete(imdbId); return next; });
    }
  };

  /*
   * Whether the page has anything true to show without this load: a cached
   * answer from an earlier visit, or one this mount already received.
   *
   * A failed refresh with that in hand is a banner above the cached sections,
   * not a page that throws them away — the whole screen used to go to "Unable
   * to connect to the API" while every section below had data to show. With
   * nothing in hand, the error is the page, because the empty states the
   * sections would otherwise fall back to ("No series in progress") would be
   * claims about a library this page never heard from.
   */
  const hasContent =
    progressMeta.hadCachedValue || historyMeta.hadCachedValue || stats != null || progress.length > 0 || history.length > 0;

  const retryLoad = () => {
    setError(null);
    // Skeletons while the retry is out, rather than the empty states a page
    // with nothing loaded would otherwise fall through to for a moment.
    if (!hasContent) setLoading(true);
    void load();
  };

  if (error && !hasContent) {
    return (
      <>
        <PageHeading />
        <SectionError variant="page" message={error} onRetry={retryLoad}>
          <p className="mt-2 text-sm" style={{ color: "var(--text-dim)" }}>
            API base: <span className="font-mono">{runtimeConfig.getApiBase()}</span>
          </p>
          <Link
            to="/settings"
            className="mt-1 inline-block py-1 text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline"
          >
            Check the connection in Settings &rarr;
          </Link>
        </SectionError>
      </>
    );
  }

  const handleCheckout = async (logWatch: boolean) => {
    await api.endCheckin(logWatch);
    setActiveCheckin(null);
    if (logWatch) void load();
  };

  // Includes the failure case so the column — and the two-column grid with it —
  // stays put and reports the problem instead of quietly reflowing to one column.
  const hasUpcoming = calendar.loading || calendarEntries.length > 0 || calendar.failed;

  // The check-in hero and the Continue Watching hero can end up showing the same series
  // (e.g. actively checked into the episode that's also furthest along in progress).
  // When that happens, skip the redundant Continue hero and fold that series into the row instead.
  const checkinSeriesId = activeCheckin ? (activeCheckin.type === "episode" ? activeCheckin.seriesImdbId : activeCheckin.imdbId) : null;
  const checkinMatchesTopProgress = checkinSeriesId != null && progress[0]?.imdbId === checkinSeriesId;
  const continueHeroItem = checkinMatchesTopProgress ? undefined : progress[0];
  const continueRowItems = checkinMatchesTopProgress ? progress : progress.slice(1);

  return (
    <div className="space-y-8">
      {/* ── Header stat row ── */}
      <DiscoverHeader
        playsThisWeek={stats?.playsThisWeek ?? 0}
        streak={detailedStats?.currentStreak ?? 0}
        longestStreak={detailedStats?.longestStreak ?? 0}
        totalMovies={stats?.totalMovies ?? 0}
        totalEpisodes={stats?.totalEpisodes ?? 0}
        topGenre={detailedStats?.genreDistribution[0]?.genre}
        loading={loading}
        statsLoading={detailed.loading}
        statsFailed={detailed.failed}
        onRetryStats={() => void loadDetailed()}
      />

      {/* A refresh that failed over a cached page: the sections below keep the
          last answer, and this says it is not the newest one. */}
      {error && <LoadErrorBanner message={error} onRetry={retryLoad} className="" />}

      {/* ── Hero: Now Watching ── */}
      {activeCheckin && <NowWatchingHero checkin={activeCheckin} onCheckout={(logWatch) => void handleCheckout(logWatch)} />}

      {/* ── Live: Plex/Jellyfin scrobble sessions ── */}
      <NowPlayingList sessions={nowPlaying} />

      {/* ── Continue Watching ── */}
      <section>
        <SectionHeader title="Continue Watching" count={progress.length}>
          {!loading && progress.length > 0 && (
            <ScrollArrows
              canScrollLeft={continueScroll.canScrollLeft}
              canScrollRight={continueScroll.canScrollRight}
              onScroll={continueScroll.scroll}
            />
          )}
        </SectionHeader>
        {loading ? (
          <CarouselSkeleton />
        ) : progress.length === 0 ? (
          <div className="rounded-2xl py-12 text-center" style={{ border: "1px dashed var(--border-strong)" }}>
            <Tv className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
            <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
              No series in progress. Series you start watching pick up here.
            </p>
            <Link to="/search" className="mt-1 inline-block py-1 text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
              Find something to watch &rarr;
            </Link>
          </div>
        ) : (
          <>
            {continueHeroItem && (
              <ContinueWatchingHero
                s={continueHeroItem}
                isMarking={markingNext.has(continueHeroItem.imdbId)}
                isDone={markedDone.has(continueHeroItem.imdbId)}
                onMarkNext={() => void handleMarkNext(continueHeroItem.imdbId)}
                onSelect={() => openTitle(toSearchResult(continueHeroItem.imdbId, "series", continueHeroItem.name, { poster: continueHeroItem.poster, background: continueHeroItem.background }))}
              />
            )}
            {continueRowItems.length > 0 && (
              <CarouselTrack
                scrollRef={continueScroll.ref}
                canScrollLeft={continueScroll.canScrollLeft}
                canScrollRight={continueScroll.canScrollRight}
                className="gap-4"
              >
                {continueRowItems.map((s, index) => (
                  <ContinueWatchingCard
                    key={s.imdbId}
                    s={s}
                    eager={index < 4}
                    isMarking={markingNext.has(s.imdbId)}
                    isDone={markedDone.has(s.imdbId)}
                    onMarkNext={() => void handleMarkNext(s.imdbId)}
                    onSelect={() => openTitle(toSearchResult(s.imdbId, "series", s.name, { poster: s.poster, background: s.background }))}
                  />
                ))}
              </CarouselTrack>
            )}
          </>
        )}
      </section>

      {/* ── Trending Now + Upcoming — two-column, breaks scroll monotony ── */}
      <div className={`grid gap-5 ${hasUpcoming ? "lg:grid-cols-[1fr_268px]" : ""}`}>
        <section>
          <SectionHeader title="Trending Now">
            <Link to="/search" className="text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
              Search &rarr;
            </Link>
          </SectionHeader>
          {trending.loading ? (
            <div className="grid grid-cols-2 gap-3 sm:[grid-template-columns:repeat(auto-fit,var(--poster-card-w))]">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="skeleton aspect-poster rounded-xl" />
              ))}
            </div>
          ) : trendingMovies.length === 0 ? (
            /* Three different things, which used to share one "Unable to load"
               card: a key to go and set, a request to retry, and a week that
               simply had nothing in it. */
            trendingNeedsTmdb ? (
              <div className="rounded-2xl py-12 text-center" style={{ border: "1px dashed var(--border-strong)" }}>
                <TrendingUp className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
                <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
                  Trending needs a TMDB API key to fetch content.
                </p>
                <Link to="/settings?tab=integrations" className="mt-1 inline-block py-1 text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
                  Set it up in Settings &rarr;
                </Link>
              </div>
            ) : trending.failed ? (
              <SectionError message="Couldn't load trending titles." onRetry={() => void loadTrending()} />
            ) : (
              <div className="rounded-2xl py-12 text-center" style={{ border: "1px dashed var(--border-strong)" }}>
                <TrendingUp className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
                <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
                  Nothing trending this week.
                </p>
              </div>
            )
          ) : (
            <div className="grid grid-cols-2 gap-3 sm:[grid-template-columns:repeat(auto-fit,var(--poster-card-w))]">
              {trendingMovies.slice(0, 4).map((item, index) => (
                <DiscoveryCard
                  key={item.id}
                  item={item}
                  eager={index < 4}
                  fill
                  onSelect={(i) => openTitle(toSearchResult(i.id, (i.type ?? "movie") as "movie" | "series", i.name, { poster: i.poster, year: i.year, description: item.description, genres: i.genres, rating: i.rating }))}
                />
              ))}
            </div>
          )}
        </section>

        {hasUpcoming && (
          <section>
            <SectionHeader title="Upcoming" count={calendarEntries.length}>
              <Link to="/calendar" className="text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
                Full calendar &rarr;
              </Link>
            </SectionHeader>
            {calendar.loading ? (
              <div className="space-y-2">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="skeleton h-16 rounded-xl" />
                ))}
              </div>
            ) : calendar.failed ? (
              <SectionError message="Couldn't load upcoming episodes." onRetry={() => void loadCalendar()} />
            ) : (
              <UpcomingList entries={calendarEntries} />
            )}
          </section>
        )}
      </div>

      {/* ── Discover: shared section for both recommendation rails ── */}
      {(movieRecs.loading || recommendations.length > 0 || movieRecs.failed || seriesRecsSection.loading || seriesRecs.length > 0 || seriesRecsSection.failed) && (
        <section>
          <SectionHeader title={aiActive ? "AI Picks" : "Discover"}>
            {aiActive && aiLastGeneratedAt && (() => {
              const nextRefresh = new Date(new Date(aiLastGeneratedAt).getTime() + 7 * 24 * 60 * 60 * 1000);
              const label = nextRefresh <= new Date() ? "Refresh due" : `Refreshes ${timeUntil(nextRefresh.toISOString())}`;
              return (
                <span title={`Next refresh: ${nextRefresh.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })}`} className="flex items-center gap-1 text-xs cursor-default" style={{ color: "var(--text-dim)" }}>
                  <Clock size={12} />
                  {label}
                </span>
              );
            })()}
          </SectionHeader>
          <div className="space-y-5">
            <DiscoverSubRow
              label="Movies"
              loading={movieRecs.loading}
              failed={movieRecs.failed}
              onRetry={() => void (aiActive ? loadMovieRecs() : loadAiSection())}
              items={recommendations}
              reasons={movieReasons}
              aiActive={aiActive}
              scroll={recsScroll}
              onSelect={(i) => openTitle(toSearchResult(i.id, (i.type ?? "movie") as "movie" | "series", i.name, { poster: i.poster, year: i.year, description: i.description, genres: i.genres, rating: i.rating }))}
            />
            <DiscoverSubRow
              label="Series"
              loading={seriesRecsSection.loading}
              failed={seriesRecsSection.failed}
              onRetry={() => void (aiActive ? loadSeriesRecs() : loadAiSection())}
              items={seriesRecs}
              reasons={seriesReasons}
              aiActive={aiActive}
              scroll={seriesRecsScroll}
              onSelect={(i) => openTitle(toSearchResult(i.id, "series", i.name, { poster: i.poster, year: i.year, description: i.description, genres: i.genres, rating: i.rating }))}
            />
          </div>
        </section>
      )}

      {/* ── Recently Watched ── */}
      <section>
        <SectionHeader title="Recently Watched" count={history.length}>
          {!loading && history.length > 0 && (
            <ScrollArrows
              canScrollLeft={recentScroll.canScrollLeft}
              canScrollRight={recentScroll.canScrollRight}
              onScroll={recentScroll.scroll}
            />
          )}
        </SectionHeader>
        {loading ? (
          <CarouselSkeleton count={6} />
        ) : history.length === 0 ? (
          <div className="rounded-2xl py-12 text-center" style={{ border: "1px dashed var(--border-strong)" }}>
            <Film className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
            <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
              No watch history yet. Anything you mark watched shows up here.
            </p>
            <Link to="/search" className="mt-1 inline-block py-1 text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
              Find something to watch &rarr;
            </Link>
          </div>
        ) : (
          <CarouselTrack
            scrollRef={recentScroll.ref}
            canScrollLeft={recentScroll.canScrollLeft}
            canScrollRight={recentScroll.canScrollRight}
            className="gap-4"
          >
            {/* One card per title: a run of one show is one poster with a
                count, not the same poster six times. */}
            {recentTitles(history).map((title) => {
              const event = title.latest;
              return (
                <RecentlyWatchedCard
                  key={event.id}
                  title={title}
                  onSelect={() =>
                    openTitle(
                      toSearchResult(
                        historyItemImdbId(event),
                        event.type === "movie" ? "movie" : "series",
                        watchEventTitle(event),
                        { poster: event.poster }
                      )
                    )
                  }
                />
              );
            })}
          </CarouselTrack>
        )}
      </section>

    </div>
  );
}
