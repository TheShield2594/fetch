import { useEffect, useState, useCallback, useMemo } from "react";
import { Clock, Sparkles } from "lucide-react";
import {
  api,
  ApiError,
  type CalendarEntry,
  type CatalogList,
  type CheckIn,
  type ListItemWithMeta,
  type MediaType,
  runtimeConfig,
  type ScrobbleSession,
  type SearchResult,
  type SeriesProgress,
  type TrendingMeta,
  type WatchEvent,
} from "../api";
import { Link } from "react-router";
import { useOnTitleClose, useTitlePanel } from "../components/media-detail/titlePanel";
import { historyItemImdbId, watchEventTitle } from "../utils/watchEvents";
import { timeUntil } from "../utils/timeAgo";
import { loadErrorMessage } from "../utils/loadError";
import { useCachedState } from "../hooks/useCachedState";
import { useToast } from "../hooks/useToast";
import { useListMembership } from "../hooks/useListMembership";
import { useMarkNextEpisode } from "../hooks/useMarkNextEpisode";
import { SectionHeader } from "../components/SectionHeader";
import { SectionError } from "../components/SectionError";
import { LoadErrorBanner } from "../components/LoadErrorBanner";
import { useDiscoverSection } from "../hooks/useDiscoverSection";
import { PageFrame, PageHeader } from "../components/PageFrame";
import { EmptyState, EMPTY_STATE_ACTION } from "../components/EmptyState";
import { UpNextCard, type UpNextItem } from "../components/UpNextCard";
import { DiscoverFeature } from "../components/discover/DiscoverFeature";
import { DiscoverRail } from "../components/discover/DiscoverRail";
import type { DiscoveryItem } from "../components/discover/DiscoveryCard";
import { NowPlayingList } from "../components/discover/NowPlayingList";
import { NowWatchingHero } from "../components/discover/NowWatchingHero";
import { UpcomingTimeline } from "../components/discover/UpcomingTimeline";
import { shelfEntryFromSeriesProgress } from "./shelf";

/*
 * Discover: things the viewer has not seen.
 *
 * It used to open on a greeting and five stat chips, then lead with the show
 * the viewer was already watching, then spend two-thirds of the page on the
 * same in-progress shows the Shelf opens with — under a "Continue Watching"
 * that meant something else on the Shelf, with a different progress bar and a
 * different kind of feedback for the same tap.
 *
 * "What's mine and what's next" belongs to the Shelf. Discover keeps one small
 * strip of it, drawn with the Shelf's own card, ruler and toast, and gives the
 * rest of the page to a lead title the viewer hasn't seen and rails that each
 * say why they are there.
 */

/** What `GET /recommendations/ai` answers with. */
type AiRecommendationsResponse = { metas: TrendingMeta[]; reasons?: Record<string, string> | undefined };

/** The watchlist, if the profile has one, and what is on it. */
type Watchlist = { list: Pick<CatalogList, "id" | "name"> | null; items: TrendingMeta[] };

/** "Because you watched X": the title the rail is seeded from, and its neighbours. */
type BecauseYouWatched = { seed: { imdbId: string; type: MediaType; name: string } | null; metas: TrendingMeta[] };

/** How many in-progress shows the pick-up strip holds. The rest are on the Shelf. */
const PICK_UP_LIMIT = 3;

const fromListItem = (item: ListItemWithMeta): TrendingMeta => ({
  id: item.imdbId,
  type: item.type,
  name: item.metadata?.name ?? item.title ?? item.imdbId,
  poster: item.metadata?.poster ?? undefined,
  year: item.metadata?.year ?? undefined,
  genres: item.metadata?.genres,
  rating: item.metadata?.rating ?? undefined,
});

/** The newest watch, as the title a "Because you watched" rail is about. */
function seedFrom(history: WatchEvent[]): BecauseYouWatched["seed"] {
  const latest = history[0];
  if (!latest) return null;
  return {
    imdbId: historyItemImdbId(latest),
    type: latest.type === "movie" ? "movie" : "series",
    name: watchEventTitle(latest),
  };
}

/** Films and shows alternately, so the lead and the rails aren't all one kind. */
function interleave<T>(a: T[], b: T[]): T[] {
  const out: T[] = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (i < a.length) out.push(a[i]!);
    if (i < b.length) out.push(b[i]!);
  }
  return out;
}

export function DiscoverPage() {
  // Each section keeps its data in the shared cache, so leaving Discover and
  // coming back paints the same content in the first frame rather than
  // spinning while the requests it already made are made again.
  const [progress, setProgress, progressMeta] = useCachedState<SeriesProgress[]>("dash:progress", []);
  const [history, setHistory, historyMeta] = useCachedState<WatchEvent[]>("dash:history", []);

  const [loading, setLoading] = useState(!progressMeta.hadCachedValue && !historyMeta.hadCachedValue);
  const [error, setError] = useState<string | null>(null);

  const [trendingNeedsTmdb, setTrendingNeedsTmdb] = useState(false);
  const [aiActive, setAiActive] = useState(false);
  const [aiLastGeneratedAt, setAiLastGeneratedAt] = useState<string | null>(null);
  const [movieReasons, setMovieReasons] = useState<Record<string, string>>({});
  const [seriesReasons, setSeriesReasons] = useState<Record<string, string>>({});

  const [activeCheckin, setActiveCheckin] = useState<CheckIn | null>(null);
  const [nowPlaying, setNowPlaying] = useState<ScrobbleSession[]>([]);

  const { showToast } = useToast();
  const { setMembership, isPending } = useListMembership(showToast);
  const [addedToWatchlist, setAddedToWatchlist] = useState<ReadonlySet<string>>(() => new Set());

  // Each rail loads, fails and retries on its own. See `useDiscoverSection`.
  const isTmdbMissing = useCallback(
    (err: unknown) =>
      (err instanceof ApiError && err.code === "tmdb_not_configured") || (err instanceof Error && /tmdb/i.test(err.message)),
    []
  );

  const trendingMovies = useDiscoverSection<TrendingMeta[], { metas: TrendingMeta[] }>(
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
      // An unconfigured TMDB key is the one failure worth naming: the page then
      // says what to go and set rather than offering a Retry that cannot work.
      onError: useCallback((err: unknown) => setTrendingNeedsTmdb(isTmdbMissing(err)), [isTmdbMissing]),
    },
  );

  const trendingSeries = useDiscoverSection<TrendingMeta[], { metas: TrendingMeta[] }>(
    "dash:trending:series",
    [],
    useCallback(() => api.getTrending("series", "week"), []),
    { apply: (res, set) => set(res.metas ?? []) },
  );

  const watchlistSection = useDiscoverSection<Watchlist>(
    "dash:watchlist",
    { list: null, items: [] },
    useCallback(async () => {
      const { lists } = await api.getLists();
      const list = lists.find((l) => l.kind === "watchlist");
      if (!list) return { list: null, items: [] };
      const { items } = await api.getListItems(list.id);
      return { list: { id: list.id, name: list.name }, items: items.map(fromListItem) };
    }, []),
  );

  const seed = useMemo(() => seedFrom(history), [history]);
  const because = useDiscoverSection<BecauseYouWatched>(
    "dash:because",
    { seed: null, metas: [] },
    useCallback(async () => {
      if (!seed) return { seed: null, metas: [] };
      const res = await api.getRecommendationsFor(seed.type, seed.imdbId);
      return { seed, metas: res.metas ?? [] };
    }, [seed]),
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

  const { openTitle } = useTitlePanel();

  const toSearchResult = useCallback((imdbId: string, type: "movie" | "series", name: string, opts?: {
    poster?: string | null | undefined;
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

  const openMeta = useCallback(
    (i: DiscoveryItem | TrendingMeta) =>
      openTitle(
        toSearchResult(i.id, i.type === "series" ? "series" : "movie", i.name, {
          poster: i.poster,
          year: i.year,
          description: i.description,
          genres: i.genres,
          rating: i.rating,
          background: "background" in i ? i.background : undefined,
        })
      ),
    [openTitle, toSearchResult]
  );

  // Takes the signal the mount effect cancels on unmount: requests that outlive
  // the page are answers arriving for a profile that may no longer be the one
  // on screen.
  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [progressRes, historyRes, checkinRes, nowPlayingRes] = await Promise.all([
        api.getSeriesProgress(signal),
        api.getWatchHistory(20, 0, { signal }),
        api.getCheckin(signal).catch(() => ({ checkin: null })),
        api.getNowPlaying(signal).catch(() => ({ sessions: [] })),
      ]);
      if (signal?.aborted) return;
      setProgress(progressRes ?? []);
      setHistory(historyRes ?? []);
      setActiveCheckin(checkinRes.checkin);
      setNowPlaying(nowPlayingRes.sessions ?? []);
      setError(null);
    } catch (err) {
      if (signal?.aborted) return;
      setError(loadErrorMessage(err, "Discover"));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, [setProgress, setHistory]);

  const refreshProgress = useCallback(async () => {
    try {
      const progressRes = await api.getSeriesProgress();
      setProgress(progressRes ?? []);
    } catch { /* keep showing the last known list */ }
  }, [setProgress]);

  // The same mark, toast and Undo as the Shelf's Up next.
  const { markNext, isMarking } = useMarkNextEpisode(refreshProgress);

  // The panel can mark episodes watched and change lists, which moves the rows
  // on this page.
  const loadWatchlist = watchlistSection.load;
  useOnTitleClose(() => {
    void refreshProgress();
    void loadWatchlist();
  });

  const profileId = runtimeConfig.getProfileId();

  // AI config gates both rails: a failure here means neither can be fetched, so
  // it surfaces as a failure on both rather than as two empty rows.
  //
  // Destructured rather than closing over the two section objects: those are
  // rebuilt on every render, and the effect that calls this runs on its identity.
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
    // Both rails restart from here, so both tokens move.
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

  const loadTrendingMovies = trendingMovies.load;
  const loadTrendingSeries = trendingSeries.load;
  const loadCalendar = calendar.load;
  useEffect(() => {
    void loadTrendingMovies();
    void loadTrendingSeries();
    void loadWatchlist();
    void loadAiSection();
    void loadCalendar();
  }, [profileId, loadTrendingMovies, loadTrendingSeries, loadWatchlist, loadAiSection, loadCalendar]);

  // Asked again whenever the newest watch changes, which is what it is about.
  const loadBecause = because.load;
  const seedId = seed?.imdbId ?? null;
  useEffect(() => {
    if (seedId) void loadBecause();
  }, [profileId, seedId, loadBecause]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, profileId]);

  /*
   * Whether the page has anything true to show without this load: a cached
   * answer from an earlier visit, or one this mount already received. A failed
   * refresh with that in hand is a banner above the cached sections; with
   * nothing in hand, the error is the page.
   */
  const hasContent =
    progressMeta.hadCachedValue || historyMeta.hadCachedValue || progress.length > 0 || history.length > 0;

  const retryLoad = () => {
    setError(null);
    if (!hasContent) setLoading(true);
    void load();
  };

  /* ── What the viewer has already met, so the page can leave it out ── */

  const watchlist = watchlistSection.value;
  const seen = useMemo(() => {
    const ids = new Set<string>();
    for (const s of progress) ids.add(s.imdbId);
    for (const e of history) ids.add(historyItemImdbId(e));
    return ids;
  }, [progress, history]);
  const onWatchlist = useMemo(
    () => new Set([...watchlist.items.map((i) => i.id), ...addedToWatchlist]),
    [watchlist.items, addedToWatchlist]
  );

  const trending = useMemo(
    () => interleave(trendingMovies.value, trendingSeries.value),
    [trendingMovies.value, trendingSeries.value]
  );
  // The lead: the first trending title the viewer has neither watched nor
  // saved, preferring one with a backdrop to draw it on.
  const feature = useMemo(() => {
    const fresh = trending.filter((m) => !seen.has(m.id) && !watchlist.items.some((i) => i.id === m.id));
    return fresh.find((m) => m.background) ?? fresh[0] ?? null;
  }, [trending, seen, watchlist.items]);

  const unseen = useCallback(
    (items: TrendingMeta[]) => items.filter((m) => !seen.has(m.id) && m.id !== feature?.id),
    [seen, feature]
  );

  if (error && !hasContent) {
    return (
      <PageFrame>
        <PageHeader title="Discover" />
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
      </PageFrame>
    );
  }

  const handleCheckout = async (logWatch: boolean) => {
    await api.endCheckin(logWatch);
    setActiveCheckin(null);
    if (logWatch) void load();
  };

  const addFeatureToWatchlist = (item: TrendingMeta) => {
    const list = watchlist.list;
    if (!list) return;
    void setMembership(
      list,
      { type: item.type, imdbId: item.id, name: item.name },
      true,
      (member) =>
        setAddedToWatchlist((prev) => {
          const next = new Set(prev);
          if (member) next.add(item.id);
          else next.delete(item.id);
          return next;
        })
    );
  };

  const pickUp: UpNextItem[] = progress.slice(0, PICK_UP_LIMIT).map((s) => {
    const entry = shelfEntryFromSeriesProgress(s, activeCheckin);
    return {
      key: entry.key,
      entry,
      action: {
        icon: "play",
        label: `Mark S${s.nextSeason}:E${s.nextEpisode} of ${s.name} watched`,
        onClick: () => void markNext(s),
        busy: isMarking(s.imdbId),
      },
    };
  });

  const watchlistRail = watchlist.items.filter((i) => !seen.has(i.id));
  const becauseRail = because.value.seed?.imdbId === seedId ? unseen(because.value.metas) : [];
  const trendingFailed = trendingMovies.failed && trendingSeries.failed;
  const trendingLoading = trendingMovies.loading && trendingSeries.loading && trending.length === 0;
  const hasUpcoming = calendar.loading || calendar.value.length > 0 || calendar.failed;

  const aiMarker = (
    <span className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold text-white" style={{ background: "rgba(0,0,0,0.6)" }}>
      <Sparkles aria-hidden="true" className="h-2.5 w-2.5" />
      AI
    </span>
  );
  const aiRefresh = aiActive && aiLastGeneratedAt ? (() => {
    const nextRefresh = new Date(new Date(aiLastGeneratedAt).getTime() + 7 * 24 * 60 * 60 * 1000);
    return nextRefresh <= new Date() ? "refresh due" : `refreshes ${timeUntil(nextRefresh.toISOString())}`;
  })() : null;
  const aiReason = (
    <span className="inline-flex items-center gap-1.5">
      Chosen by your AI provider from what you watch
      {aiRefresh && (
        <>
          <span aria-hidden="true">&middot;</span>
          <Clock aria-hidden="true" size={12} />
          {aiRefresh}
        </>
      )}
    </span>
  );

  const seeAll = (to: string, label: string) => (
    <Link to={to} className="whitespace-nowrap text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline">
      {label} &rarr;
    </Link>
  );

  return (
    <PageFrame className="space-y-9">
      <PageHeader title="Discover" className="" />

      {/* A refresh that failed over a cached page: the sections below keep the
          last answer, and this says it is not the newest one. */}
      {error && <LoadErrorBanner message={error} onRetry={retryLoad} className="" />}

      {/* ── The lead: something new ── */}
      {trendingNeedsTmdb && trending.length === 0 ? (
        <EmptyState
          title="Trending needs a TMDB key"
          action={<Link to="/settings?section=tmdb" className={EMPTY_STATE_ACTION}>Set it up in Settings &rarr;</Link>}
        >
          Trending needs a TMDB API key to fetch content.
        </EmptyState>
      ) : trendingLoading ? (
        <div className="skeleton min-h-[26rem] rounded-3xl sm:min-h-[50vh] lg:min-h-[min(60vh,42rem)]" aria-hidden="true" />
      ) : feature ? (
        <DiscoverFeature
          item={feature}
          reason={`Trending this week · ${feature.type === "series" ? "Show" : "Film"}`}
          onOpen={() => openMeta(feature)}
          watchlist={
            watchlist.list
              ? {
                  state: onWatchlist.has(feature.id)
                    ? "added"
                    : isPending(watchlist.list.id, { type: feature.type, imdbId: feature.id })
                      ? "busy"
                      : "idle",
                  onAdd: () => addFeatureToWatchlist(feature),
                }
              : null
          }
        />
      ) : null}

      {/* ── Yours, briefly: what's playing, and where you left off ── */}
      {activeCheckin && <NowWatchingHero checkin={activeCheckin} onCheckout={(logWatch) => void handleCheckout(logWatch)} />}
      <NowPlayingList sessions={nowPlaying} />
      {!loading && pickUp.length > 0 && (
        <section aria-labelledby="discover-pick-up">
          <div className="mb-3 flex items-end justify-between gap-4">
            <h2 id="discover-pick-up" className="text-[1.0625rem] font-bold" style={{ color: "var(--text)" }}>
              Pick up where you left off
            </h2>
            {seeAll("/", "Your Shelf")}
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {pickUp.map((item, index) => (
              <UpNextCard
                key={item.key}
                item={item}
                lead={false}
                eager={index < 3}
                className="w-full"
                onOpen={() => item.entry.item && openTitle(item.entry.item)}
              />
            ))}
          </div>
        </section>
      )}

      {/* ── Rails, each with its reason ── */}
      {!trendingNeedsTmdb && (
        <>
          <DiscoverRail
            title="Trending films"
            reason="What people are watching this week"
            items={unseen(trendingMovies.value)}
            loading={trendingMovies.loading && trendingMovies.value.length === 0}
            failed={trendingMovies.failed && trendingMovies.value.length === 0}
            onRetry={() => void loadTrendingMovies()}
            empty={trendingFailed ? undefined : <EmptyState size="section" title="Nothing trending this week" />}
            onSelect={openMeta}
          />
          <DiscoverRail
            title="Trending shows"
            reason="The series everyone is talking about this week"
            items={unseen(trendingSeries.value)}
            loading={trendingSeries.loading && trendingSeries.value.length === 0}
            failed={trendingSeries.failed && trendingSeries.value.length === 0}
            onRetry={() => void loadTrendingSeries()}
            onSelect={openMeta}
          />
        </>
      )}

      {seed && (
        <DiscoverRail
          title={`Because you watched ${seed.name}`}
          reason="Titles people who liked it went on to watch"
          items={becauseRail}
          loading={because.loading && becauseRail.length === 0}
          onSelect={openMeta}
        />
      )}

      {hasUpcoming && (
        <section>
          <SectionHeader title="Coming up">
            {seeAll("/calendar", "Full calendar")}
          </SectionHeader>
          <p className="-mt-2 mb-3 text-sm" style={{ color: "var(--text-dim)" }}>
            New episodes and releases from your library in the next two weeks
          </p>
          {calendar.loading && calendar.value.length === 0 ? (
            <div className="flex gap-4 overflow-hidden" aria-hidden="true">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="skeleton aspect-video w-[15rem] flex-none rounded-2xl" />
              ))}
            </div>
          ) : calendar.failed && calendar.value.length === 0 ? (
            <SectionError message="Couldn't load upcoming episodes." onRetry={() => void loadCalendar()} />
          ) : (
            <UpcomingTimeline
              entries={calendar.value}
              onOpen={(entry) =>
                openTitle(
                  toSearchResult(entry.seriesImdbId, entry.kind === "movie" ? "movie" : "series", entry.seriesName, {
                    poster: entry.poster,
                    background: entry.backdrop,
                  })
                )
              }
            />
          )}
        </section>
      )}

      {watchlist.list && (
        <DiscoverRail
          title="From your watchlist"
          reason="Saved and not started yet"
          items={watchlistRail}
          loading={watchlistSection.loading && watchlistRail.length === 0}
          action={seeAll("/lists", "All lists")}
          onSelect={openMeta}
        />
      )}

      <DiscoverRail
        title="Films picked for you"
        reason={aiReason}
        items={movieRecs.value}
        loading={movieRecs.loading && aiActive}
        failed={movieRecs.failed}
        onRetry={() => void (aiActive ? loadMovieRecs() : loadAiSection())}
        reasons={movieReasons}
        marker={aiMarker}
        onSelect={openMeta}
      />
      <DiscoverRail
        title="Shows picked for you"
        reason={aiReason}
        items={seriesRecsSection.value}
        loading={seriesRecsSection.loading && aiActive}
        failed={seriesRecsSection.failed}
        onRetry={() => void (aiActive ? loadSeriesRecs() : loadAiSection())}
        reasons={seriesReasons}
        marker={aiMarker}
        onSelect={(i) => openMeta({ ...i, type: "series" })}
      />
    </PageFrame>
  );
}
