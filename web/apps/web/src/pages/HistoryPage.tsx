import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { Calendar, ChevronDown, Film, NotebookPen, Search, Trash2, Tv, X } from "lucide-react";
import { api, type SearchResult, type WatchEvent } from "../api";
import { useTitlePanel } from "../components/media-detail/titlePanel";
import { useToast } from "../hooks/useToast";
import { useCachedState } from "../hooks/useCachedState";
import { useClockBoundary } from "../hooks/useClockBoundary";
import { relogWatchEvent, watchEventLabel, watchEventTitle } from "../utils/watchEvents";
import { PAGE_TITLE, KICKER } from "../components/typography";
import { PAGE_SIZE } from "./history-paging";
import { SectionError } from "../components/SectionError";
import { Poster } from "../components/Poster";
import { MyRating } from "../components/MyRating";
import { SelectField } from "../components/SelectField";
import {
  dayLabel,
  dayTotals,
  episodeRangeLabel,
  groupHistory,
  monthEndIso,
  monthLabel,
  type EpisodeRun,
} from "./history-groups";

type TypeFilter = "all" | "movie" | "episode";

// A short page is the end of the list — but only of the list the request was
// asking about, so each caller decides this after its own staleness check
// rather than having the fetch decide for whichever list is on screen now.
// Module scope because it depends on nothing else: a copy per render would be
// one more identity for the effect that reads it to have to track.
const isLastPage = (page: WatchEvent[]) => page.length < PAGE_SIZE;

/** How long typing has to pause before the filter is sent. */
const QUERY_DEBOUNCE_MS = 300;

const timeOf = (event: WatchEvent): string | null =>
  event.dateUnknown
    ? null
    : new Date(event.watchedAt).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });

// Row actions used to appear only on hover from `sm` up, so on a desktop there
// was nothing to say a row could be annotated or removed until the pointer was
// already on it. They are always drawn now, quietly, and come up to full
// strength on the row's hover or their own focus.
const ROW_ACTION =
  "relative z-10 flex h-9 w-9 flex-none items-center justify-center rounded-lg opacity-60 transition-[opacity,color,background-color] duration-fast group-hover:opacity-100 hover:opacity-100 focus:opacity-100 focus:outline-none focus-visible:ring-2 focus-ring-offset";

function toSearchResult(event: WatchEvent): SearchResult {
  const isEpisode = event.type === "episode";
  return {
    imdbId: isEpisode ? (event.seriesImdbId ?? event.imdbId) : event.imdbId,
    type: isEpisode ? "series" : "movie",
    name: watchEventTitle(event),
    year: null,
    poster: event.poster ?? null,
    description: null,
    genres: [],
    rating: null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
  };
}

/**
 * The watch-history page: a paginated, filterable timeline of watch events.
 * Only the first page is cached; later pages are held separately and merged for
 * rendering so appending never rewrites the cache.
 */
export function HistoryPage() {
  // Only the first page goes through the cache. Appended pages are held
  // separately and merged for rendering: writing them through would grow the
  // cached entry without bound as you scroll, and — because a remount restarts
  // pagination at offset 0 — the first load back would then replace a long
  // cached list with a single page, collapsing the view.
  //
  // Keyed by the filter, because the filter is part of the question the server
  // answered. Under one shared key, filtering to Movies and navigating away
  // left a movies-only list cached as the whole history — and the next mount,
  // which starts at "All", read it back and painted it as complete, with
  // `hadCachedValue` suppressing even the skeleton that would have hinted more
  // was coming. CalendarPage keys on its day range for the same reason.
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("all");
  /*
   * Where in the history the list starts, and which titles it is limited to.
   *
   * Both live in the URL rather than in state: `/history?month=2026-03` is what
   * a month on the Stats chart links to, and a filter that survives a reload
   * and Back is the difference between a search and a thing you have to redo.
   * Written with the functional form throughout, so the `title` parameter of
   * an open panel is carried rather than dropped.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const monthParam = searchParams.get("month");
  const month = monthParam && monthEndIso(monthParam) ? monthParam : null;
  const query = (searchParams.get("q") ?? "").trim();
  const narrowed = month !== null || query !== "";
  // "Today" and "Yesterday" are relative to when the grouping runs, not to when
  // the page mounted — a tab left open overnight headed yesterday's watches
  // "Today" until it was reloaded.
  const now = useClockBoundary();
  const [cachedFirstPage, setCachedFirstPage, firstPageMeta] = useCachedState<WatchEvent[]>(
    `history:events:${typeFilter}`,
    []
  );
  // A searched or jumped-to list is not "the history", and is not cached as it:
  // every distinct query would be an entry of its own, kept for the session,
  // and the unfiltered key must never hold a filtered answer (see above).
  const [narrowedFirstPage, setNarrowedFirstPage] = useState<WatchEvent[]>([]);
  const firstPage = narrowed ? narrowedFirstPage : cachedFirstPage;
  const setFirstPage = useCallback(
    (next: WatchEvent[] | ((previous: WatchEvent[]) => WatchEvent[])) => {
      if (narrowed) setNarrowedFirstPage(next);
      else setCachedFirstPage(next);
    },
    [narrowed, setCachedFirstPage]
  );
  const [extraPages, setExtraPages] = useState<WatchEvent[]>([]);
  const events = useMemo(() => [...firstPage, ...extraPages], [firstPage, extraPages]);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(!firstPageMeta.hadCachedValue || narrowed);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null);
  const [noteDraft, setNoteDraft] = useState("");
  const [savingNote, setSavingNote] = useState(false);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const { showToast } = useToast();
  const { openTitle } = useTitlePanel();
  const [months, setMonths] = useState<{ month: string; events: number }[]>([]);
  // Which collapsed runs are open, by day and show.
  const [expandedRuns, setExpandedRuns] = useState<ReadonlySet<string>>(() => new Set());
  const [queryDraft, setQueryDraft] = useState(query);

  // Typing filters as you go, but not on every keystroke: each change is a
  // request and a replaced list.
  useEffect(() => {
    const next = queryDraft.trim();
    if (next === query) return;
    const timer = setTimeout(() => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev);
          if (next) params.set("q", next);
          else params.delete("q");
          return params;
        },
        // One act of searching is one history entry, not one per pause.
        { replace: true }
      );
    }, QUERY_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [queryDraft, query, setSearchParams]);

  const jumpToMonth = (next: string | null) => {
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      if (next) params.set("month", next);
      else params.delete("month");
      return params;
    });
  };

  // The months there are to jump to. Best-effort: an API older than the route
  // has none to offer, and the page is whole without the menu.
  useEffect(() => {
    const controller = new AbortController();
    api
      .getWatchHistoryMonths({ type: typeFilter === "all" ? undefined : typeFilter, signal: controller.signal })
      .then((list) => { if (!controller.signal.aborted) setMonths(list); })
      .catch(() => { if (!controller.signal.aborted) setMonths([]); });
    return () => controller.abort();
  }, [typeFilter]);

  // The filter goes to the server, not to the loaded page: filtering in memory
  // only ever saw the rows already fetched, so picking "Movies" on a history
  // whose most recent 25 events are all episodes showed an empty list.
  const loadPage = useCallback(async (nextOffset: number, signal?: AbortSignal) => {
    const page = await api.getWatchHistory(PAGE_SIZE, nextOffset, {
      type: typeFilter === "all" ? undefined : typeFilter,
      q: query || undefined,
      // Everything before the month's end: the list opens at that month's
      // last watch, and offsets count from there.
      before: month ? (monthEndIso(month) ?? undefined) : undefined,
      signal,
    });
    return signal?.aborted ? [] : page;
  }, [typeFilter, query, month]);

  /*
   * Which set of rows the loaded pages describe.
   *
   * An offset only means anything against a particular server-side list, and
   * two things replace that list underneath a request already in flight: a new
   * filter (the type, the title search or the month), and a delete or
   * restore, which shifts every row after it. The reset effect below moves the generation on both counts; `loadMore` captures it
   * and drops a page that lands under an older one — otherwise tapping
   * "Movies" near the bottom of the page appends the episodes page that was
   * already on its way to the movies-only list.
   */
  const generation = useRef(0);
  const [reloadToken, setReloadToken] = useState(0);

  // Where the next page starts, counted from the rows on screen rather than
  // tracked alongside them. A delete removes the row here *and* on the server,
  // so the server's own offsets shift by one at the same time; keeping a
  // separate counter meant adjusting it by ±1 to compensate, which double-counts
  // as soon as a delete and a page load overlap. A count can't drift from what
  // it counts.
  const offset = events.length;

  // Re-runs when loadPage changes identity, i.e. whenever the filter changes —
  // pagination has to restart from 0 against the new result set.
  useEffect(() => {
    // Aborted rather than only flagged: `setFirstPage` writes through to the
    // shared cache, so a response that arrives after a profile switch has to be
    // dropped at the request, not just ignored at the setter.
    const controller = new AbortController();
    generation.current++;
    setLoading(true);
    (async () => {
      try {
        const page = await loadPage(0, controller.signal);
        if (!controller.signal.aborted) {
          setFirstPage(page);
          setExtraPages([]);
          setHasMore(!isLastPage(page));
          setError(null);
        }
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : "Failed to load watch history");
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    })();
    return () => controller.abort();
    // `setFirstPage` only changes identity with `narrowed`, which changes
    // `loadPage` in the same render.
    // `reloadToken` is what Retry bumps: the first page has no loader of its own
    // to call, so asking again means asking this effect to run again.
  }, [loadPage, setFirstPage, reloadToken]);

  const loadMore = useCallback(async () => {
    const token = generation.current;
    setLoadingMore(true);
    try {
      const page = await loadPage(offset);
      if (generation.current !== token) return;
      setExtraPages((prev) => [...prev, ...page]);
      setHasMore(!isLastPage(page));
    } catch (err) {
      if (generation.current !== token) return;
      setError(err instanceof Error ? err.message : "Failed to load more history");
    } finally {
      // Cleared either way: this is the flag for *this* call, and the scroll
      // sentinel is re-observed when it flips, which is what asks for the page
      // again against the list that replaced the one this was reading.
      setLoadingMore(false);
    }
  }, [loadPage, offset]);

  // Infinite scroll
  useEffect(() => {
    if (!hasMore || loading) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting && !loadingMore) void loadMore();
    }, { rootMargin: "200px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loading, loadingMore, loadMore]);

  // A re-logged event can come back as one already on screen — the server folds
  // same-day identical plays into a single row's play count — so the insert is
  // keyed on id rather than appending blindly.
  const restoreEvent = (event: WatchEvent) => {
    // A restored row belongs at its date, which is within the first page for
    // anything recent — and the first page is the list that persists.
    setFirstPage((prev) =>
      prev.some((e) => e.id === event.id) || extraPages.some((e) => e.id === event.id)
        ? prev
        : [...prev, event].sort(
            (a, b) => new Date(b.watchedAt).getTime() - new Date(a.watchedAt).getTime()
          )
    );
    // The row is back in the server's list too, so every offset past it has
    // moved: a page already in flight is reading the old numbering.
    generation.current++;
  };

  const handleUndoDelete = async (event: WatchEvent) => {
    try {
      restoreEvent(await relogWatchEvent(event));
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not restore watch event", "error");
    }
  };

  // The delete button sits on a row whose whole surface is also a tap target and
  // renders at full opacity on touch, so a mis-tap is easy. The removal is
  // optimistic and the toast carries the way back, rather than a confirm dialog
  // gating every removal.
  const handleDelete = async (event: WatchEvent) => {
    setDeletingId(event.id);
    // The row can be in either list, so both are filtered.
    setFirstPage((prev) => prev.filter((e) => e.id !== event.id));
    setExtraPages((prev) => prev.filter((e) => e.id !== event.id));
    // Same as a restore, in the other direction — the rows after this one all
    // shift up by one server-side.
    generation.current++;
    try {
      await api.deleteWatchEvent(event.id);
      // Metadata is backfilled in the background, so a row can reach here
      // without a name.
      showToast(event.name ? `Removed ${event.name} from history` : "Removed from history", "info", {
        action: { label: "Undo", onAction: () => void handleUndoDelete(event) },
      });
    } catch (err) {
      restoreEvent(event);
      showToast(err instanceof Error ? err.message : "Failed to delete watch event", "error");
    } finally {
      setDeletingId(null);
    }
  };

  // A note is written when the watch is logged and was unreachable afterwards —
  // there was no way to correct or remove one from the app. Editing here writes
  // through `PATCH /watch/:eventId` and takes the row the server sends back
  // rather than the draft, so what's on screen is what was stored.
  const openNoteEditor = (event: WatchEvent) => {
    setEditingNoteId(event.id);
    setNoteDraft(event.note ?? "");
  };

  const closeNoteEditor = () => {
    setEditingNoteId(null);
    setNoteDraft("");
  };

  const handleSaveNote = async (event: WatchEvent) => {
    const note = noteDraft.trim() || null;
    if (note === (event.note ?? null)) {
      closeNoteEditor();
      return;
    }
    setSavingNote(true);
    try {
      const { watchEvent } = await api.updateWatchEventNote(event.id, note);
      const applyNote = (list: WatchEvent[]) =>
        list.map((e) => (e.id === event.id ? { ...e, note: watchEvent.note ?? null } : e));
      setFirstPage(applyNote);
      setExtraPages(applyNote);
      closeNoteEditor();
      showToast(note ? "Note saved" : "Note removed", "success");
    } catch (err) {
      // The editor stays open with the draft intact, so a failed save is a
      // retry rather than retyping.
      showToast(err instanceof Error ? err.message : "Failed to save note", "error");
    } finally {
      setSavingNote(false);
    }
  };

  const days = useMemo(() => groupHistory(events), [events]);

  const toggleRun = (key: string) => {
    setExpandedRuns((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  // The jump menu, by year. A month the URL names is offered even when the
  // list of months has not arrived (or never will), so the select can show it.
  const monthOptions = useMemo(() => {
    const all = month && !months.some((m) => m.month === month) ? [{ month, events: 0 }, ...months] : months;
    const byYear = new Map<string, { month: string; events: number }[]>();
    for (const entry of [...all].sort((a, b) => b.month.localeCompare(a.month))) {
      const year = entry.month.slice(0, 4);
      byYear.set(year, [...(byYear.get(year) ?? []), entry]);
    }
    return [...byYear.entries()];
  }, [months, month]);

  const noteEditor = (event: WatchEvent) =>
    editingNoteId === event.id && (
      <div className="px-3 pb-3">
        <label className="sr-only" htmlFor={`note-${event.id}`}>
          Note on {event.name || "this watch"}
        </label>
        <textarea
          id={`note-${event.id}`}
          autoFocus
          rows={2}
          value={noteDraft}
          onChange={(e) => setNoteDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); closeNoteEditor(); } }}
          placeholder="What did you think?"
          className="w-full resize-y rounded-lg px-2.5 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-focus"
          style={{ border: "1px solid var(--border)", background: "var(--bg-0)", color: "var(--text)" }}
        />
        <div className="mt-2 flex justify-end gap-2">
          <button type="button" className="btn-secondary btn-sm" onClick={closeNoteEditor} disabled={savingNote}>
            Cancel
          </button>
          <button
            type="button"
            className="btn-primary btn-sm"
            onClick={() => void handleSaveNote(event)}
            disabled={savingNote}
          >
            {savingNote ? "Saving…" : "Save note"}
          </button>
        </div>
      </div>
    );

  // `relative z-10` puts these above the row overlay, which would otherwise
  // take the click. They do not need to stop the event: the overlay is a
  // sibling, so nothing bubbles from here to it.
  const rowActions = (event: WatchEvent) => (
    <>
      <button
        type="button"
        onClick={() => openNoteEditor(event)}
        className={`${ROW_ACTION} hover:bg-[var(--surface-strong)] focus-visible:ring-focus`}
        aria-label={event.note ? `Edit note on ${watchEventLabel(event)}` : `Add note to ${watchEventLabel(event)}`}
        title={event.note ? "Edit note" : "Add note"}
      >
        <NotebookPen aria-hidden="true" className="h-4 w-4" style={{ color: event.note ? "var(--accent-text)" : "var(--text-mute)" }} />
      </button>
      <button
        type="button"
        onClick={() => void handleDelete(event)}
        disabled={deletingId === event.id}
        className={`${ROW_ACTION} hover:bg-rose-500/10 disabled:opacity-50 focus-visible:ring-danger`}
        aria-label={`Delete watch of ${watchEventLabel(event)}`}
        title="Remove from history"
      >
        <Trash2 aria-hidden="true" className="h-4 w-4 text-danger dark:text-danger" />
      </button>
    </>
  );

  // A 2:3 box, so a poster fills it instead of being cropped to a square
  // through its middle. It was 40×60, which is as small as a poster can be and
  // still be recognised; this is the first size at which it reads as artwork.
  const rowPoster = (event: WatchEvent) => (
    <div
      className="pointer-events-none flex aspect-poster w-12 flex-none items-center justify-center overflow-hidden rounded-lg sm:w-14"
      style={{ background: "var(--surface-strong)" }}
    >
      {event.poster ? (
        // `Poster`, not a bare `<img>`: it brings the srcset, the placeholder
        // while the bytes are in flight, and the fallback when the image 404s
        // — which a raw tag renders as a hole.
        <Poster src={event.poster} alt={watchEventTitle(event)} className="h-full w-full" sizes="56px" />
      ) : event.type === "movie" ? (
        <Film className="h-5 w-5" style={{ color: "var(--text-mute)" }} />
      ) : (
        <Tv className="h-5 w-5" style={{ color: "var(--text-mute)" }} />
      )}
    </div>
  );

  // The row's whole surface, as one click target behind its content. The
  // content is `pointer-events-none` so clicks fall through to this; the action
  // buttons opt back in and stack above it. A sibling of those buttons, never
  // their ancestor — a button inside a button has no defined mapping.
  const openOverlay = (event: WatchEvent, label: string) => (
    <button
      type="button"
      onClick={() => openTitle(toSearchResult(event))}
      aria-label={`View details for ${label}`}
      className="absolute inset-0 z-0 cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus"
    />
  );

  /** One watch: a film, or an episode that is not part of a run. */
  const singleRow = (event: WatchEvent) => {
    const time = timeOf(event);
    // An episode's own rating if it has one, the show's if not; a film's.
    const rating = event.rating ?? (event.type === "episode" ? event.seriesRating : null);
    return (
      <div key={event.id}>
        <div className="group relative flex items-center gap-3 px-3 py-2.5 transition-colors hover:bg-[var(--surface-strong)]">
          {openOverlay(event, watchEventLabel(event))}
          {rowPoster(event)}
          <div className="pointer-events-none relative min-w-0 flex-1">
            <p className="truncate text-sm font-medium" style={{ color: "var(--text)" }}>
              {watchEventTitle(event)}
              {event.type === "episode" && event.season != null && event.episode != null && (
                <span className="meta-row" style={{ color: "var(--text-mute)" }}>
                  {" "}S{event.season}E{event.episode}
                </span>
              )}
            </p>
            <p className="meta-row flex items-center gap-2" style={{ color: "var(--text-mute)" }}>
              {time && <time dateTime={event.watchedAt}>{time}</time>}
              {rating != null && <MyRating rating={rating} />}
            </p>
            {event.note && editingNoteId !== event.id && (
              <p className="mt-1 whitespace-pre-wrap text-xs italic" style={{ color: "var(--text-dim)" }}>
                {event.note}
              </p>
            )}
          </div>
          {rowActions(event)}
        </div>
        {/* Outside the row: a field inside the click target would open the
            title on every click into it. */}
        {noteEditor(event)}
      </div>
    );
  };

  /**
   * A run of one show: one row for the sitting, which opens to its episodes.
   *
   * Six episodes of a show used to be six rows carrying the same poster and
   * the same title, with only the episode code to tell them apart — most of a
   * screen spent saying one thing. The run says it once, with the range and
   * the count, and the episodes are still there underneath for the things that
   * are per-watch: the time, the note, the delete.
   */
  const runRow = (run: EpisodeRun, dayKey: string) => {
    const [latest] = run;
    const title = watchEventTitle(latest);
    const key = `${dayKey}:${latest.seriesImdbId ?? latest.imdbId}`;
    const panelId = `run-${key}`;
    // Opened by hand, or because a note inside it is being written.
    const open = expandedRuns.has(key) || run.some((e) => e.id === editingNoteId);
    const first = timeOf(run[run.length - 1]!);
    const last = timeOf(latest);
    const notes = run.filter((e) => e.note).length;
    return (
      <div key={`${key}:${latest.id}`}>
        <div className="group relative flex items-center gap-3 px-3 py-2.5 transition-colors hover:bg-[var(--surface-strong)]">
          {openOverlay(latest, title)}
          {rowPoster(latest)}
          <div className="pointer-events-none relative min-w-0 flex-1">
            <p className="truncate text-sm font-medium" style={{ color: "var(--text)" }}>{title}</p>
            <p className="meta-row truncate" style={{ color: "var(--text-dim)" }}>
              {episodeRangeLabel(run)} · {run.length} episodes
            </p>
            <p className="meta-row flex items-center gap-2" style={{ color: "var(--text-mute)" }}>
              {first && last && <span>{first === last ? last : `${first} – ${last}`}</span>}
              {latest.seriesRating != null && <MyRating rating={latest.seriesRating} />}
              {notes > 0 && (
                <span className="inline-flex items-center gap-1">
                  <NotebookPen aria-hidden="true" className="h-3 w-3" style={{ color: "var(--accent-text)" }} />
                  {notes === 1 ? "1 note" : `${notes} notes`}
                </span>
              )}
            </p>
          </div>
          <button
            type="button"
            onClick={() => toggleRun(key)}
            aria-expanded={open}
            aria-controls={panelId}
            aria-label={`${open ? "Hide" : "Show"} the ${run.length} episodes of ${title}`}
            className="relative z-10 flex h-9 flex-none items-center gap-1 rounded-lg px-2 text-xs font-medium transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
            style={{ color: "var(--text-dim)" }}
          >
            <span aria-hidden="true">{open ? "Hide" : "Show"}</span>
            <ChevronDown aria-hidden="true" className={`h-4 w-4 transition-transform duration-fast ${open ? "rotate-180" : ""}`} />
          </button>
        </div>
        {open && (
          <ul id={panelId} aria-label={`Episodes of ${title}`} className="pb-1">
            {run.map((event) => (
              <li key={event.id}>
                {/* Indented to the text column, so the episodes read as
                    belonging to the row above rather than as rows of their own. */}
                <div className="group flex items-center gap-3 py-0.5 pl-[4.5rem] pr-3 sm:pl-20">
                  <div className="min-w-0 flex-1">
                    <p className="meta-row flex flex-wrap items-center gap-x-2" style={{ color: "var(--text-dim)" }}>
                      <span className="font-medium" style={{ color: "var(--text)" }}>
                        {event.season != null && event.episode != null ? `S${event.season} E${event.episode}` : "Episode"}
                      </span>
                      {timeOf(event) && <time dateTime={event.watchedAt}>{timeOf(event)}</time>}
                      {event.rating != null && <MyRating rating={event.rating} />}
                    </p>
                    {event.note && editingNoteId !== event.id && (
                      <p className="mt-0.5 whitespace-pre-wrap text-xs italic" style={{ color: "var(--text-dim)" }}>
                        {event.note}
                      </p>
                    )}
                  </div>
                  {rowActions(event)}
                </div>
                {editingNoteId === event.id && <div className="pl-[3.75rem] sm:pl-[4.25rem]">{noteEditor(event)}</div>}
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>History</h1>

          <div className="relative inline-flex rounded-full p-0.5" style={{ background: "var(--surface-strong)", border: "1px solid var(--border)" }}>
            {(["all", "movie", "episode"] as const).map((opt) => (
              <button
                key={opt}
                type="button"
                onClick={() => setTypeFilter(opt)}
                // Without this the active filter is a background colour and nothing
                // else — SC 1.4.1 and 4.1.2.
                aria-pressed={typeFilter === opt}
                className="relative rounded-full px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
                style={
                  typeFilter === opt
                    ? { background: "var(--accent)", color: "var(--on-accent)" }
                    : { color: "var(--text-dim)" }
                }
              >
                {opt === "all" ? "All" : opt === "movie" ? "Movies" : "Episodes"}
              </button>
            ))}
          </div>
        </div>

        {/* Finding something: by what it was called, or by when. Both go to the
            server — the list is paged, so neither can be answered from the rows
            that happen to be loaded. */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-[12rem] flex-1">
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2" style={{ color: "var(--text-mute)" }} />
            <input
              type="search"
              value={queryDraft}
              onChange={(e) => setQueryDraft(e.target.value)}
              aria-label="Filter history by title"
              placeholder="Filter by title"
              maxLength={100}
              className="field-control h-10 w-full rounded-xl pl-9 pr-9 text-sm [&::-webkit-search-cancel-button]:hidden"
              style={{ border: "1px solid var(--border)", background: "var(--bg-1)", color: "var(--text)" }}
            />
            {queryDraft && (
              <button
                type="button"
                onClick={() => setQueryDraft("")}
                aria-label="Clear the title filter"
                className="absolute right-1.5 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-lg transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
              >
                <X aria-hidden="true" className="h-4 w-4" style={{ color: "var(--text-mute)" }} />
              </button>
            )}
          </div>

          {monthOptions.length > 0 && (
            <SelectField
              aria-label="Jump to a month"
              value={month ?? ""}
              onChange={(e) => jumpToMonth(e.target.value || null)}
              className="h-10 rounded-xl pl-3 text-sm"
              wrapperClassName="flex-none"
            >
              <option value="">Latest</option>
              {monthOptions.map(([year, entries]) => (
                <optgroup key={year} label={year}>
                  {entries.map((entry) => (
                    <option key={entry.month} value={entry.month}>
                      {monthLabel(entry.month, { year: false })}
                      {entry.events > 0 ? ` (${entry.events})` : ""}
                    </option>
                  ))}
                </optgroup>
              ))}
            </SelectField>
          )}
        </div>

        {month && (
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm" style={{ color: "var(--text-dim)" }}>
            <span>From the end of {monthLabel(month)}, going back.</span>
            <button
              type="button"
              onClick={() => jumpToMonth(null)}
              className="font-medium text-claw-text underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            >
              Back to latest
            </button>
          </p>
        )}
      </div>

      {error && events.length > 0 && (
        <p role="alert" className="text-sm text-danger">{error}</p>
      )}

      {/* Skeletons and the failure card render in place of the list rather than
          replacing the page: switching filters now refetches, and a failed
          fetch used to take the filter pills with it, leaving no way back. */}
      {loading ? (
        // Two grouped panels rather than eight loose bars, so the shape that
        // arrives is the shape that was being waited for.
        <div className="space-y-6">
          {[4, 4].map((rows, g) => (
            <div key={g}>
              <div className="skeleton mb-2 h-3 w-24 rounded" />
              <div className="skeleton rounded-2xl" style={{ height: `${rows * 4.25}rem` }} />
            </div>
          ))}
        </div>
      ) : error && events.length === 0 ? (
        <SectionError variant="page" message={error} onRetry={() => setReloadToken((n) => n + 1)} />
      ) : events.length === 0 ? (
        <div className="glass-panel rounded-2xl p-8 text-center" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
          <Calendar className="mx-auto h-10 w-10" style={{ color: "var(--text-mute)" }} />
          {narrowed ? (
            <>
              <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
                {query
                  ? `Nothing in your history matches “${query}”${month ? ` in or before ${monthLabel(month)}` : ""}.`
                  : `Nothing watched in or before ${monthLabel(month ?? "")}.`}
              </p>
              <button
                type="button"
                onClick={() => {
                  setQueryDraft("");
                  setSearchParams((prev) => {
                    const params = new URLSearchParams(prev);
                    params.delete("q");
                    params.delete("month");
                    return params;
                  });
                }}
                className="mt-1 inline-block py-1 text-sm font-medium text-claw-text underline-offset-2 transition-colors hover:underline"
              >
                Show all history
              </button>
            </>
          ) : (
            <>
              <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
                {typeFilter === "movie"
                  ? "No movies in your watch history yet."
                  : typeFilter === "episode"
                    ? "No episodes in your watch history yet."
                    : "No watch history yet."}
                {" "}Anything you mark watched is logged here.
              </p>
              {/* The search page is the way out of this one: a brand-new install
                  lands here with nothing to filter and no other route onward. */}
              <Link to="/search" className="mt-1 inline-block py-1 text-sm font-medium text-claw-text underline-offset-2 transition-colors hover:underline">
                Find something to watch &rarr;
              </Link>
            </>
          )}
        </div>
      ) : (
        <div className="space-y-6">
          {days.map((day) => (
            <section key={day.key} aria-label={dayLabel(day.date, now)}>
              {/* The day, and what it added up to. */}
              <h2 className="mb-2 flex flex-wrap items-baseline justify-between gap-x-3 px-1">
                <span className={KICKER} style={{ color: "var(--text-dim)" }}>{dayLabel(day.date, now)}</span>
                <span className="meta-row" style={{ color: "var(--text-mute)" }}>{dayTotals(day)}</span>
              </h2>
              {/* One surface per day, with hairlines between the rows. Not
                  .glass-row on each: the group container is the .glass-panel,
                  and a row inside it would stack a second blurred layer per row
                  — putting a wall of separate surfaces back on the Glass theme. */}
              <div
                className="glass-panel overflow-hidden rounded-2xl"
                style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}
              >
                {day.rows.map((row, i) => (
                  <div key={row.kind === "single" ? row.event.id : `run-${row.events[0].id}`} style={i > 0 ? { borderTop: "1px solid var(--border)" } : undefined}>
                    {row.kind === "single" ? singleRow(row.event) : runRow(row.events, day.key)}
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      {hasMore && (
        <div ref={sentinelRef} className="flex justify-center py-4">
          {loadingMore && (
            <p className="text-sm" style={{ color: "var(--text-dim)" }}>Loading...</p>
          )}
        </div>
      )}
    </div>
  );
}
