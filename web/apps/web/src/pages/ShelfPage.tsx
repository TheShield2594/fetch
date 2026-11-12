import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import {
  api,
  type CatalogList,
  type CheckIn,
  type ListItemWithMeta,
  OfflineWriteQueuedError,
  type PlaybackProgress,
  type SeriesProgress,
  type WatchEvent,
} from "../api";
import { useOnTitleClose, useTitlePanel } from "../components/media-detail/titlePanel";
import { GhostLoader } from "../components/GhostLoader";
import { LoadErrorBanner } from "../components/LoadErrorBanner";
import { SectionError } from "../components/SectionError";
import { LandscapeCard, type LandscapeAction } from "../components/LandscapeCard";
import { ProgressRuler } from "../components/ProgressRuler";
import { SegmentedControl, type SegmentedOption } from "../components/SegmentedControl";
import { SelectField } from "../components/SelectField";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { useCachedState } from "../hooks/useCachedState";
import { useToast } from "../hooks/useToast";
import { loadErrorMessage } from "../utils/loadError";
import { timeAgo } from "../utils/timeAgo";
import {
  applyShelfFilter,
  buildShelf,
  countByKind,
  isResumedNextEpisode,
  ratingKey,
  shelfEntryFromPlayback,
  shelfEntryFromSeriesProgress,
  shelfSummary,
  sortShelf,
  SHELF_FILTERS,
  SHELF_SORTS,
  type ShelfEntry,
  type ShelfFilter,
  type ShelfSort,
} from "./shelf";
import { PosterGrid } from "../components/PosterGrid";
import { PosterCard } from "../components/PosterCard";

/*
 * The Shelf.
 *
 * Lists and History were separate destinations for one idea, separated by the
 * table each thing happened to be stored in. This page is that idea with
 * the tabs removed: everything you track, in one grid, behind one filter, with
 * whatever you are part-way through pinned above it.
 *
 * The unification is only worth doing if it costs no precision, so nothing here
 * flattens a show and a film into a shared percentage — see `shelf.ts`, where
 * each kind's units are chosen, and `ProgressRuler`, which draws episodes as
 * ticks and continuous quantities as a bar. A grid of forty tells you what is
 * part-watched, and in what unit, with no badge and no hover.
 *
 * The pages this replaces as a *browsing* surface still exist as the places you
 * manage things: /lists creates and edits lists, /history is the full log. They are reachable from the rail's
 * "Manage" group, and from the tab bar's "More" sheet.
 */

// Same reasoning as the Lists page's constant of the same name: an imported
// Letterboxd collection can carry thousands of rows, and the cost is the DOM
// nodes rather than the fetch. Grow the rendered slice on scroll instead of
// paginating a list that is already sorted and filtered in memory.
const RENDER_PAGE_SIZE = 60;

/**
 * Shared with Discover's `dash:progress`. Two surfaces asking the same question should not each pay
 * for it, and arriving on either from the other then paints in the first frame.
 */
const PROGRESS_CACHE_KEY = "dash:progress";
/** The Shelf's own union. The per-list rows behind it stay under the Lists page's key. */
const SHELF_ITEMS_CACHE_KEY = "shelf:items";
const PLAYBACK_CACHE_KEY = "shelf:playback";
const LISTS_CACHE_KEY = "shelf:lists";

/* ─── The filter row ───────────────────────────────────────── */

/**
 * The kind filter, as options for the segmented control.
 *
 * The counts moved out of the labels and into the accessible names. They were
 * carried as a second number inside each button — "Shows 12" — which is a
 * useful fact and the wrong place for it: a segmented control is a row of peers
 * whose widths are fixed and equal, and labels each carrying a variable
 * number is how that row starts wrapping on a 320px phone. The page already
 * writes the totals out under its title, and the Library header repeats the
 * count for whatever filter is on.
 *
 * The fact itself is not lost. It stays in the announced name — "Shows, 12
 * titles" — which is where it was doing the most work anyway: it is the reason
 * an empty kind can be disabled rather than hidden, and a disabled control with
 * no explanation is the thing that reads as a fault.
 */
function kindOptions(counts: Record<ShelfFilter, number>): SegmentedOption<ShelfFilter>[] {
  return SHELF_FILTERS.map((filter) => {
    const count = counts[filter.value];
    return {
      value: filter.value,
      label: filter.label,
      // The visible label leads, which is what SC 2.5.3 asks of a name longer
      // than its text.
      ariaLabel: `${filter.label}, ${count} ${count === 1 ? "title" : "titles"}`,
      // A kind with nothing in it is still shown, but disabled — hiding it
      // would make the row change length as a library grows, and a viewer who
      // has learned where "Films" sits would find "Shows" there instead.
      disabled: count === 0,
    };
  });
}

/* ─── Up Next ──────────────────────────────────────────────── */

/**
 * One card in Up Next: what it is, and the one thing its action does.
 *
 * Continue Watching and Up Next were two rows of the same card a heading
 * apart, and the difference between them — a resume point versus the next
 * episode — is the action, not the section. So they are one ordered row: what
 * you paused comes first, newest pause first, then the shows whose next
 * episode is waiting, in the order the API ranks them.
 */
type UpNextItem = {
  key: string;
  entry: ShelfEntry;
  action?: LandscapeAction | undefined;
  dismiss?: { label: string; onClick: () => void } | undefined;
};

function UpNextCard({
  item,
  lead,
  eager,
  onOpen,
}: {
  item: UpNextItem;
  lead: boolean;
  eager: boolean;
  onOpen: () => void;
}) {
  const { entry } = item;
  return (
    <LandscapeCard
      // The landscape still if the record carries one, the poster if not. A
      // poster in a 16:9 frame is cropped to its middle, which is a worse
      // picture than the still and a much better one than a grey box.
      art={entry.item?.background ?? entry.art}
      artIsBackdrop={!!entry.item?.background}
      title={entry.title}
      eyebrow={entry.meta}
      progress={
        entry.ruler ? (
          <ProgressRuler
            value={entry.ruler.value}
            total={entry.ruler.total}
            partial={entry.ruler.partial}
            discrete={entry.ruler.discrete}
            // Nothing on the card writes the count out in words — the ticks
            // are the count — so this is the only thing that can say "4 of 10
            // episodes watched" to someone who can't see them.
            label={entry.ruler.label}
            onArt
          />
        ) : undefined
      }
      onOpen={onOpen}
      openLabel={`Open details for ${entry.title}`}
      action={item.action}
      dismiss={item.dismiss}
      size={lead ? "lead" : "rail"}
      eager={eager}
      className={lead ? "w-full" : "w-[15rem] flex-none snap-start"}
    />
  );
}

/* ─── A cell in the grid ───────────────────────────────────── */

/**
 * One cell of the library: the shared poster card, with the kind as the first
 * token of its meta run rather than a badge over the art — the same cell for
 * shows and films, so the run underneath is where they differ.
 */
function ShelfCell({
  entry,
  eager,
  myRating,
  onOpen,
}: {
  entry: ShelfEntry;
  eager: boolean;
  /** Shown while the library is sorted by it, so the order has its reason beside it. */
  myRating: number | null;
  onOpen: () => void;
}) {
  return (
    <PosterCard
      poster={entry.art}
      name={entry.title}
      onOpen={onOpen}
      openLabel={`Open details for ${entry.title}`}
      eager={eager}
      meta={entry.meta}
      rating={myRating != null ? { source: "mine", value: myRating } : null}
    />
  );
}

/* ─── The page ─────────────────────────────────────────────── */

export function ShelfPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filterParam = searchParams.get("kind");
  const filter: ShelfFilter = SHELF_FILTERS.some((f) => f.value === filterParam)
    ? (filterParam as ShelfFilter)
    : "all";
  // `/?genre=Drama` — what a genre on the Stats page links to. Not a control of
  // its own on this page: it arrives by link and is cleared from the chip it
  // shows up as.
  const genre = searchParams.get("genre")?.trim() || null;
  const sortParam = searchParams.get("sort");
  const sort: ShelfSort = SHELF_SORTS.some((s) => s.value === sortParam) ? (sortParam as ShelfSort) : "added";
  // `/?list=<id>` — the Library narrowed to one list. Like a genre, a question
  // about the whole library, so it is answered from all of it.
  const listParam = searchParams.get("list") || null;

  const [items, setItems, itemsMeta] = useCachedState<ListItemWithMeta[]>(SHELF_ITEMS_CACHE_KEY, []);
  const [lists, setLists] = useCachedState<CatalogList[]>(LISTS_CACHE_KEY, []);
  // Asked for only once the Library is sorted by it: most visits never are.
  const [ratings, setRatings] = useState<ReadonlyMap<string, number> | null>(null);
  const [progress, setProgress] = useCachedState<SeriesProgress[]>(PROGRESS_CACHE_KEY, []);
  const [playback, setPlayback] = useCachedState<PlaybackProgress[]>(PLAYBACK_CACHE_KEY, []);
  const [checkin, setCheckin] = useState<CheckIn | null>(null);
  const [lastEvent, setLastEvent] = useState<WatchEvent | null>(null);

  const [loading, setLoading] = useState(!itemsMeta.hadCachedValue);
  const [error, setError] = useState<string | null>(null);
  // Whether there is a real answer on screen — from this load or the cache. The
  // summary line counts what is on the shelf, and before a load has succeeded
  // "0 titles" is not a count, it is a guess.
  const [loaded, setLoaded] = useState(itemsMeta.hadCachedValue);
  const [markingId, setMarkingId] = useState<string | null>(null);
  const [playbackBusyId, setPlaybackBusyId] = useState<string | null>(null);
  const [renderLimit, setRenderLimit] = useState(RENDER_PAGE_SIZE);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const loadAbortRef = useRef<AbortController | null>(null);

  const { showToast } = useToast();
  const { openTitle } = useTitlePanel();

  const load = useCallback(async () => {
    loadAbortRef.current?.abort();
    const controller = new AbortController();
    loadAbortRef.current = controller;
    const signal = controller.signal;
    setError(null);

    try {
      // The five questions the page opens with, asked at once. Each one that
      // isn't the shelf itself is allowed to fail on its own: a broken check-in
      // should cost the row its "42 min left", not the whole grid.
      const [lists, progressRes, checkinRes, historyRes, playbackRes] = await Promise.all([
        api.getLists(signal),
        api.getSeriesProgress(signal).catch(() => [] as SeriesProgress[]),
        api.getCheckin(signal).catch(() => ({ checkin: null })),
        api.getWatchHistory(1, 0, { signal }).catch(() => [] as WatchEvent[]),
        api.getPlayback(signal).catch(() => ({ playback: [] as PlaybackProgress[] })),
      ]);

      // One request per list, in parallel. The alternative — a union endpoint —
      // would be the right answer if a list could be large *and* numerous, but
      // a profile has a handful of lists and each is one indexed query, so this
      // costs a few concurrent requests rather than a new API surface. A list
      // that fails contributes nothing rather than emptying the shelf.
      const itemPages = await Promise.all(
        lists.lists.map((list) =>
          api.getListItems(list.id, signal).then((res) => res.items).catch(() => [] as ListItemWithMeta[])
        )
      );

      if (loadAbortRef.current !== controller) return;
      setLists(lists.lists);
      setItems(itemPages.flat());
      setProgress(progressRes ?? []);
      setCheckin(checkinRes.checkin);
      setLastEvent(historyRes[0] ?? null);
      setPlayback(playbackRes.playback ?? []);
      setLoaded(true);
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      if (loadAbortRef.current !== controller) return;
      setError(loadErrorMessage(err, "your shelf"));
    } finally {
      if (loadAbortRef.current === controller) setLoading(false);
    }
  }, [setItems, setLists, setProgress, setPlayback]);

  useEffect(() => {
    void load();
    return () => loadAbortRef.current?.abort();
  }, [load]);

  // The panel can mark episodes watched and change lists, which moves the rows
  // on this page — and rate things, which moves the Library under "My rating".
  useOnTitleClose(() => {
    void load();
    setRatings(null);
  });

  useEffect(() => {
    if (sort !== "rating" || ratings !== null) return;
    let cancelled = false;
    // The two kinds a list row can be. Season and episode ratings don't place
    // a title in the Library, so they are not asked for.
    void Promise.all([
      api.getAllRatings("movie", 200).catch(() => ({ ratings: [] })),
      api.getAllRatings("series", 200).catch(() => ({ ratings: [] })),
    ]).then(([movies, series]) => {
      if (cancelled) return;
      setRatings(new Map([...movies.ratings, ...series.ratings].map((r) => [ratingKey(r.type, r.imdbId), r.rating])));
    });
    return () => {
      cancelled = true;
    };
  }, [sort, ratings]);

  /* ── Continue Watching ── */

  // Already newest-paused first from the API, which is the order to resume in.
  const continueWatching = useMemo(() => playback.map(shelfEntryFromPlayback), [playback]);
  const visibleContinue = useMemo(() => applyShelfFilter(continueWatching, filter), [continueWatching, filter]);

  /* ── In progress ── */

  const inProgress = useMemo(() => {
    // The series progress feed is already ordered by recency at the API.
    return progress
      .filter((s) => !isResumedNextEpisode(s, playback))
      .map((s) => shelfEntryFromSeriesProgress(s, checkin));
  }, [progress, checkin, playback]);

  const visibleInProgress = useMemo(() => applyShelfFilter(inProgress, filter), [inProgress, filter]);

  // A narrowed Library (a genre, a list) is a question about the whole
  // library, so Up Next steps aside while it is on — see `rest` below.
  const narrowed = genre !== null || listParam !== null;

  /* ── Everything else ── */

  const shelf = useMemo(() => buildShelf(items), [items]);

  /*
   * Everything the page can render, which is not the same as `shelf`.
   *
   * The grid is built from lists; the in-progress block is
   * built from the series-progress feed, and a show you watch through a Plex
   * webhook without ever adding it to a list appears only in the second. Counting
   * `shelf` alone therefore undercounts the page — and worse, a library whose
   * only series is one of those left the "Shows" filter reading 0 and *disabled*
   * while shows were visible on screen above it.
   *
   * In-progress wins a key collision: its entry carries the ruler and the
   * episode the list row knows nothing about.
   */
  const everything = useMemo(() => {
    const byKey = new Map(shelf.map((entry) => [entry.key, entry]));
    for (const entry of continueWatching) byKey.set(entry.key, entry);
    for (const entry of inProgress) byKey.set(entry.key, entry);
    return [...byKey.values()];
  }, [shelf, continueWatching, inProgress]);

  const counts = useMemo(() => countByKind(everything), [everything]);

  // What is already pinned at the top doesn't also belong in the grid — the
  // section is called "everything else" and has to mean it.
  const inProgressKeys = useMemo(
    () => new Set([...continueWatching, ...inProgress].map((entry) => entry.key)),
    [continueWatching, inProgress]
  );
  const rest = useMemo(() => {
    let ofKind = applyShelfFilter(shelf, filter);
    // A genre or a list is a question about the whole library, so it is
    // answered from the whole library: the row pinned above is hidden while it
    // is on (it comes from feeds that carry neither), and a title that would
    // have been up there is counted here instead of nowhere.
    if (listParam) ofKind = ofKind.filter((entry) => entry.lists.includes(listParam));
    if (genre) {
      const wanted = genre.toLowerCase();
      ofKind = ofKind.filter((entry) => entry.item?.genres.some((g) => g.toLowerCase() === wanted));
    }
    const unpinned = narrowed ? ofKind : ofKind.filter((entry) => !inProgressKeys.has(entry.key));
    return sortShelf(unpinned, sort, ratings ?? undefined);
  }, [shelf, filter, genre, listParam, narrowed, inProgressKeys, sort, ratings]);

  useEffect(() => setRenderLimit(RENDER_PAGE_SIZE), [filter, genre, listParam, sort]);

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || renderLimit >= rest.length) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          setRenderLimit((prev) => Math.min(prev + RENDER_PAGE_SIZE, rest.length));
        }
      },
      { rootMargin: "800px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [renderLimit, rest.length]);

  const visibleRest = rest.slice(0, renderLimit);

  /* ── Actions ── */

  const openEntry = (entry: ShelfEntry) => {
    if (entry.item) openTitle(entry.item);
  };

  const markNext = async (s: SeriesProgress) => {
    setMarkingId(s.imdbId);
    try {
      await api.markNextEpisodeWatched(s.imdbId);
      showToast(`Marked S${s.nextSeason}:E${s.nextEpisode} of ${s.name}`, "success");
      // The whole in-progress block moves when an episode lands — the show can
      // finish a season, change position, or leave the list entirely — so this
      // refetches rather than patching one row and hoping the rest still agree.
      await load();
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Failed to mark episode", "error");
    } finally {
      setMarkingId(null);
    }
  };

  /**
   * Logs the paused title as watched. That is an ordinary watch, so it goes
   * through the offline queue like any other — and recording it is what clears
   * the resume point at the API. The explicit delete after it is for a browser
   * whose clock runs behind the server's: the API only clears points paused at
   * or before the watch, and a watch stamped a minute "earlier" than the pause
   * would leave the card standing.
   */
  const finishPlayback = async (p: PlaybackProgress, title: string) => {
    setPlaybackBusyId(p.id);
    const label = p.season != null && p.episode != null ? `S${p.season}:E${p.episode} of ${title}` : title;
    try {
      try {
        await api.logWatch({
          type: p.type,
          imdbId: p.imdbId,
          seriesImdbId: p.type === "episode" ? p.imdbId : undefined,
          season: p.season ?? undefined,
          episode: p.episode ?? undefined,
          watchedAt: new Date().toISOString(),
        });
      } catch (err) {
        if (!(err instanceof OfflineWriteQueuedError)) throw err;
        // The watch will land when the connection does, and clear the point
        // then. Until it has, the card would only offer to log it twice.
        setPlayback((prev) => prev.filter((row) => row.id !== p.id));
        showToast(err.message, "info");
        return;
      }
      await api.deletePlayback(p.id).catch(() => undefined);
      showToast(`Marked ${label} watched`, "success");
      // The show's Up Next card moves on with it, so this refetches the whole
      // page rather than dropping one card and hoping the rest still agree.
      await load();
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Failed to mark as watched", "error");
    } finally {
      setPlaybackBusyId(null);
    }
  };

  /**
   * Takes a title off Continue Watching without logging anything — the "I'm
   * not going back to this" action. Undo puts the same position back; the
   * point gets a new id and a fresh pause time, which is what moves it to the
   * front of the row it left.
   */
  const dismissPlayback = async (p: PlaybackProgress, title: string) => {
    setPlaybackBusyId(p.id);
    try {
      await api.deletePlayback(p.id);
      setPlayback((prev) => prev.filter((row) => row.id !== p.id));
      showToast(`Removed ${title} from Up next`, "info", {
        action: {
          label: "Undo",
          onAction: () => {
            void api
              .savePlayback({
                type: p.type,
                imdbId: p.imdbId,
                season: p.season,
                episode: p.episode,
                progress: p.progress,
                positionSeconds: p.positionSeconds,
                durationSeconds: p.durationSeconds,
              })
              // The PUT answers with the point alone; the title and poster the
              // list joins in come from the row that was just removed.
              .then((res) => setPlayback((prev) => [{ ...res.playback, name: p.name, poster: p.poster }, ...prev]))
              .catch((err: unknown) =>
                showToast(err instanceof Error ? err.message : "Couldn't put it back", "error")
              );
          },
        },
      });
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Failed to remove it", "error");
    } finally {
      setPlaybackBusyId(null);
    }
  };

  const upNext: UpNextItem[] = narrowed
    ? []
    : [
        ...visibleContinue.map((entry): UpNextItem => {
          const p = entry.playback!;
          const what =
            p.season != null && p.episode != null ? `S${p.season}:E${p.episode} of ${entry.title}` : entry.title;
          return {
            // By the resume point, not the entry key: one show can be paused
            // on two episodes, which share `series:<id>`.
            key: p.id,
            entry,
            action: {
              icon: "check",
              label: `Mark ${what} watched`,
              onClick: () => void finishPlayback(p, entry.title),
              busy: playbackBusyId === p.id,
            },
            dismiss: { label: `Remove ${what} from Up next`, onClick: () => void dismissPlayback(p, entry.title) },
          };
        }),
        ...visibleInProgress.map((entry): UpNextItem => {
          const series =
            entry.kind === "show" ? progress.find((s) => `series:${s.imdbId}` === entry.key) : undefined;
          return {
            key: entry.key,
            entry,
            action: series
              ? {
                  icon: "play",
                  label: `Mark S${series.nextSeason}:E${series.nextEpisode} of ${entry.title} watched`,
                  onClick: () => void markNext(series),
                  busy: markingId !== null && `series:${markingId}` === entry.key,
                }
              : undefined,
          };
        }),
      ];
  const [leadItem, ...railItems] = upNext;

  const setParam = (name: string, value: string | null) =>
    setSearchParams(
      (prev) => {
        const params = new URLSearchParams(prev);
        if (value) params.set(name, value);
        else params.delete(name);
        return params;
      },
      { replace: true }
    );

  const summary = shelfSummary(
    everything,
    lastEvent ? `Last watched ${timeAgo(lastEvent.watchedAt)}` : null
  );

  /*
   * A failed load with nothing to show is a failure, not an empty shelf.
   *
   * Without this the page rendered three things at once after a 429: "0
   * titles", the error, and the first-run "Nothing on the shelf yet" with its
   * Search and Connect buttons — which reads as a library that has been
   * emptied and invites starting over. With nothing cached the error is the
   * only true thing to say; with something cached, the cache stays on screen
   * and the error is a banner above it.
   */
  const failedEmpty = error != null && everything.length === 0;
  const nothingAtAll = !loading && !failedEmpty && everything.length === 0;

  return (
    <div>
      <header className="mb-4 min-w-0">
        <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>
          Shelf
        </h1>
        {/* 15px, not the 12px caption rank: this is the large title's own
            subtitle, and the only line on the screen that describes the whole
            of what is under it. */}
        {loaded && !failedEmpty && (
          <p className="mt-0.5 truncate text-[0.9375rem] tabular-nums" style={{ color: "var(--text-dim)" }}>
            {summary}
          </p>
        )}
      </header>

      {error && !failedEmpty && <LoadErrorBanner message={error} onRetry={() => void load()} />}

      {!nothingAtAll && !failedEmpty && (
        <SegmentedControl
          label="Filter the shelf by kind"
          options={kindOptions(counts)}
          value={filter}
          onChange={(next) =>
            setSearchParams(
              (prev) => {
                const params = new URLSearchParams(prev);
                if (next === "all") params.delete("kind");
                else params.set("kind", next);
                return params;
              },
              // Replace rather than push: flicking along the filter row is one
              // act of looking, and Back should leave the Shelf rather than
              // walk back through every filter to get there.
              { replace: true }
            )
          }
          className="sm:max-w-md"
        />
      )}

      {failedEmpty ? (
        <div className="mt-6">
          <SectionError variant="page" message={error ?? ""} onRetry={() => void load()} />
        </div>
      ) : loading && shelf.length === 0 && inProgress.length === 0 && continueWatching.length === 0 ? (
        <GhostLoader label="Loading your shelf…" className="items-center py-24" />
      ) : nothingAtAll ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <p className={SECTION_TITLE} style={{ color: "var(--text-dim)" }}>
            Nothing on the shelf yet
          </p>
          <p className="mt-1.5 max-w-sm text-sm" style={{ color: "var(--text-mute)" }}>
            Search for a show or a film to add it to a list, or connect a source in Settings and let
            your history fill this in on its own.
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            <Link to="/search" className="btn-primary btn-sm">
              Search
            </Link>
            <Link to="/settings" className="btn-secondary btn-sm">
              Connect a source
            </Link>
          </div>
        </div>
      ) : (
        // Fades in over the ghost it replaces rather than cutting to it.
        <div className="content-in mt-6 flex flex-col gap-7">
          {leadItem && (
            <section aria-labelledby="shelf-up-next">
              <h2 id="shelf-up-next" className={`${SECTION_TITLE} mb-3`} style={{ color: "var(--text)" }}>
                Up next
              </h2>
              {/*
               * One lead, then a rail.
               *
               * The lead is the most recent thing you were in the middle of,
               * drawn large — the page's entry point, which nine equal cards
               * didn't have. The rest are compact cards in one horizontal row
               * at every width: a wrapping grid of them pushed the library a
               * screen and a half down. From `lg` the rail sits beside the
               * lead rather than under it, which is what keeps the library's
               * first row on a 1440×900 screen.
               *
               * The rail bleeds to the screen edge on a phone (the negative
               * margin) so its last card doesn't stop a gutter short.
               */}
              <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:gap-4">
                {/* Keyed on the item, so when the lead is finished and the next
                    thing moves up, it slides into the place rather than the
                    row being redrawn under you. */}
                <div key={leadItem.key} className="card-advance w-full lg:w-[min(30rem,50%)] lg:flex-none">
                  <UpNextCard item={leadItem} lead eager onOpen={() => openEntry(leadItem.entry)} />
                </div>
                {railItems.length > 0 && (
                  <div
                    role="list"
                    aria-label="More to pick up"
                    className="scrollbar-hide -mx-5 flex min-w-0 snap-x snap-mandatory scroll-px-5 gap-3 overflow-x-auto px-5 pb-1 sm:mx-0 sm:px-0 lg:flex-1"
                  >
                    {railItems.map((item, index) => (
                      <div role="listitem" key={item.key} className="flex-none">
                        <UpNextCard item={item} lead={false} eager={index < 3} onOpen={() => openEntry(item.entry)} />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </section>
          )}

          <section aria-labelledby="shelf-rest">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
              <h2 id="shelf-rest" className={SECTION_TITLE} style={{ color: "var(--text)" }}>
                Library <span className="meta font-normal" style={{ color: "var(--text-mute)" }}>{rest.length}</span>
              </h2>
              {/* Real controls where a label used to look like one: "31 ·
                  Recently added" read as a sort and wasn't. */}
              <div className="flex items-center gap-2">
                {lists.length > 1 && (
                  <SelectField
                    aria-label="Show one list"
                    value={listParam ?? ""}
                    onChange={(e) => setParam("list", e.target.value || null)}
                    className="max-w-[11rem] rounded-full py-1.5 pl-3 pr-8 text-sm"
                    style={{ color: "var(--text-dim)" }}
                  >
                    <option value="">All lists</option>
                    {lists.map((list) => (
                      <option key={list.id} value={list.id}>
                        {list.name}
                      </option>
                    ))}
                  </SelectField>
                )}
                <SelectField
                  aria-label="Sort the library"
                  value={sort}
                  onChange={(e) => setParam("sort", e.target.value === "added" ? null : e.target.value)}
                  className="rounded-full py-1.5 pl-3 pr-8 text-sm"
                  style={{ color: "var(--text-dim)" }}
                >
                  {SHELF_SORTS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </SelectField>
              </div>
            </div>

            {genre && (
              <p className="mb-3 flex flex-wrap items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
                <span
                  className="rounded-full px-3 py-1 text-xs font-medium"
                  style={{ border: "1px solid var(--border)", background: "var(--surface-strong)", color: "var(--text)" }}
                >
                  {genre}
                </span>
                <button
                  type="button"
                  onClick={() =>
                    setSearchParams(
                      (prev) => {
                        const params = new URLSearchParams(prev);
                        params.delete("genre");
                        return params;
                      },
                      { replace: true }
                    )
                  }
                  className="font-medium text-accent-text underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                >
                  Show every genre
                </button>
              </p>
            )}

            {rest.length === 0 ? (
              genre ? (
                <p className="py-10 text-center text-sm" style={{ color: "var(--text-mute)" }}>
                  Nothing on the shelf in {genre}.{" "}
                  <Link
                    to={`/search?genre=${encodeURIComponent(genre)}`}
                    className="font-medium text-accent-text underline-offset-2 hover:underline"
                  >
                    Browse {genre} in Search
                  </Link>
                </p>
              ) : (
                <p className="py-10 text-center text-sm" style={{ color: "var(--text-mute)" }}>
                  {listParam ? "Nothing in this list under this filter." : "Nothing else under this filter."}
                </p>
              )
            ) : (
              /* Three across on a phone rather than two. A poster is
                 recognisable well below the width two-up gives it, and three is
                 what turns the grid into a *library* — you see a shelf of them
                 at once instead of a stack you scroll through a pair at a
                 time. */
              <PosterGrid density="library">
                {visibleRest.map((entry, index) => (
                  <ShelfCell
                    key={entry.key}
                    entry={entry}
                    eager={index < 7}
                    myRating={sort === "rating" ? (ratings?.get(entry.key) ?? null) : null}
                    onOpen={() => openEntry(entry)}
                  />
                ))}
              </PosterGrid>
            )}
            {renderLimit < rest.length && <div ref={sentinelRef} className="h-4" />}
          </section>
        </div>
      )}
    </div>
  );
}
