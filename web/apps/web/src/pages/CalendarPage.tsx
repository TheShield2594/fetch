import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { api, type CalendarEntry, type SearchResult } from "../api";
import { useTitlePanel } from "../components/media-detail/titlePanel";
import { Poster } from "../components/Poster";
import { LandscapeCard } from "../components/LandscapeCard";
import { EmptyState, EMPTY_STATE_ACTION } from "../components/EmptyState";
import { PageFrame, PageHeader } from "../components/PageFrame";
import { SegmentedControl, type SegmentedOption } from "../components/SegmentedControl";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { useExitAnimation } from "../hooks/useExitAnimation";
import { useVisualViewport } from "../hooks/useVisualViewport";
import { useFocusTrap } from "../hooks/useFocusTrap";
import { useMediaQuery } from "../hooks/useMediaQuery";
import { useScrollLock } from "../hooks/useScrollLock";
import { useCachedState } from "../hooks/useCachedState";
import { useClockBoundary } from "../hooks/useClockBoundary";
import { SECTION_TITLE, KICKER, MICRO_LABEL } from "../components/typography";
import { localDateFromIsoDate } from "../utils/calendarDate";
import { calendarEntryKey as entryKey, calendarEntryLabel as entryLabel } from "../utils/calendarEntry";

/*
 * The calendar.
 *
 * Art-led, like the rest of the app: the agenda is a run of landscape cards —
 * the episode's still where TMDB has one, the title's backdrop where it
 * doesn't — under Today, Tomorrow, This week and Later, and the month grid puts
 * a poster in each day rather than a 12px text chip. It covers what you'd want
 * to know is coming: the next episode of anything you are watching, the
 * premiere of a series on your watchlist, and the release of a film on it.
 *
 * Synopses stay off it. Everything here is unaired, so every synopsis is of an
 * episode you have not seen, and the title panel is one click away for anyone
 * who wants to read ahead.
 *
 * The view, the agenda's range and the month on screen are all in the URL, so
 * a reload, a shared link and Back all land where you were.
 */

type ViewMode = "agenda" | "month";
const AGENDA_RANGES = [14, 30, 60, 90] as const;
type AgendaRange = (typeof AGENDA_RANGES)[number];
const DEFAULT_RANGE: AgendaRange = 30;
const MAX_CALENDAR_DAYS = 90;
// Tailwind's `sm`. Below it a day cell is ~50px wide: room for one poster.
const COMPACT_QUERY = "(max-width: 639px)";
// Posters a month cell shows before the rest are counted.
const MONTH_CELL_THUMBS = 3;
const MONTH_CELL_THUMBS_COMPACT = 1;

const VIEW_OPTIONS: SegmentedOption<ViewMode>[] = [
  { value: "agenda", label: "Agenda" },
  { value: "month", label: "Month" },
];

const RANGE_OPTIONS: SegmentedOption<`${AgendaRange}`>[] = AGENDA_RANGES.map((d) => ({
  value: `${d}`,
  label: `${d} days`,
}));

function toSearchResult(entry: CalendarEntry): SearchResult {
  return {
    imdbId: entry.seriesImdbId,
    type: entry.kind === "movie" ? "movie" : "series",
    name: entry.seriesName,
    year: null,
    poster: entry.poster,
    description: entry.overview,
    genres: [],
    rating: null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
  };
}

// An air date the API sent in a shape this cannot read keeps the Invalid Date
// it has always produced: it groups under its own key and labels as "Invalid
// Date" rather than dropping the entry out of the calendar without a trace.
function parseAirDate(airDate: string): Date {
  return localDateFromIsoDate(airDate) ?? new Date(NaN);
}

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

function addMonths(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth() + n, 1);
}

function dateKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function monthParam(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** `YYYY-MM` from the URL, or null for anything else. */
function parseMonthParam(value: string | null): Date | null {
  const match = value ? /^(\d{4})-(\d{2})$/.exec(value) : null;
  if (!match) return null;
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return new Date(Number(match[1]), month - 1, 1);
}

function parseRange(value: string | null): AgendaRange {
  const n = Number(value);
  return (AGENDA_RANGES as readonly number[]).includes(n) ? (n as AgendaRange) : DEFAULT_RANGE;
}

function dayDiff(date: Date, today: Date): number {
  return Math.round((startOfDay(date).getTime() - today.getTime()) / 86_400_000);
}

const longDate = (d: Date) => d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });

const WEEKDAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
// The visible headers are abbreviated to fit seven columns on a phone. The
// column header's accessible name doesn't have that constraint, and "Sun" read
// aloud is a different word.
const WEEKDAY_FULL = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

/** "4 episodes", or "4 releases" once a film is among them. */
function countLabel(entries: CalendarEntry[]): string {
  const n = entries.length;
  const episodic = entries.every((e) => e.kind !== "movie");
  if (episodic) return `${n} ${n === 1 ? "episode" : "episodes"}`;
  return `${n} ${n === 1 ? "release" : "releases"}`;
}

/** The art an entry leads with: its own still, else the title's backdrop, else the poster. */
function entryArt(entry: CalendarEntry): { art: string | null; isBackdrop: boolean } {
  const wide = entry.still ?? entry.backdrop;
  return wide ? { art: wide, isBackdrop: true } : { art: entry.poster, isBackdrop: false };
}

function EntryCard({ entry, onSelect, eager }: { entry: CalendarEntry; onSelect: (entry: CalendarEntry) => void; eager: boolean }) {
  const { art, isBackdrop } = entryArt(entry);
  const label = entryLabel(entry);
  return (
    <LandscapeCard
      art={art}
      artIsBackdrop={isBackdrop}
      title={entry.seriesName}
      eyebrow={label}
      onOpen={() => onSelect(entry)}
      openLabel={`${entry.seriesName}, ${label}`}
      eager={eager}
      className="w-full"
    />
  );
}

/** A row in the day list: the same art, small, beside the words. */
function EntryRow({ entry, onSelect }: { entry: CalendarEntry; onSelect: (entry: CalendarEntry) => void }) {
  const { art, isBackdrop } = entryArt(entry);
  return (
    <button
      type="button"
      onClick={() => onSelect(entry)}
      className="flex w-full items-center gap-3 rounded-xl p-2 text-left transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
    >
      <div
        className="w-24 flex-none overflow-hidden rounded-lg"
        style={{ aspectRatio: "16 / 9", background: "var(--surface)" }}
      >
        <Poster
          src={art ?? undefined}
          alt=""
          className="h-full w-full"
          sizes="96px"
          variant={isBackdrop ? "backdrop" : "poster"}
        />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold" style={{ color: "var(--text)" }}>{entry.seriesName}</p>
        <p className="meta-row mt-0.5 truncate" style={{ color: "var(--text-dim)" }}>{entryLabel(entry)}</p>
      </div>
    </button>
  );
}

/** Every entry on one day of the month grid. */
function DayEntriesModal({
  date,
  entries,
  onSelect,
  onClose,
}: {
  date: Date;
  entries: CalendarEntry[];
  onSelect: (entry: CalendarEntry) => void;
  onClose: () => void;
}) {
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);
  useScrollLock();
  useEscapeKey(requestClose);
  const viewportStyle = useVisualViewport();

  return (
    <div
      className={`overlay-scrim overlay-fade fixed inset-0 z-50 flex items-start justify-center p-4 sm:pt-[10vh] ${exiting ? "overlay-exit" : ""}`}
      style={viewportStyle}
      onClick={requestClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="calendar-day-modal-title"
        tabIndex={-1}
        inert={exiting}
        className={`glass-surface overlay-dialog flex max-h-full w-full max-w-md flex-col overflow-hidden rounded-3xl shadow-e3 ${exiting ? "overlay-exit" : ""}`}
        style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={onExitAnimationEnd}
      >
        <div className="flex flex-none items-center justify-between border-b px-5 py-4" style={{ borderColor: "var(--border)" }}>
          <div className="min-w-0">
            <h2 id="calendar-day-modal-title" className={`truncate ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>{longDate(date)}</h2>
            <p className="meta-row" style={{ color: "var(--text-mute)" }}>{countLabel(entries)}</p>
          </div>
          <button
            type="button"
            onClick={requestClose}
            aria-label="Close dialog"
            className="ml-3 rounded-lg p-1.5 hover:bg-[var(--surface)] hover:text-[var(--text)]"
            style={{ color: "var(--text-mute)" }}
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="min-h-0 flex-auto space-y-1 overflow-y-auto px-3 py-3 sm:max-h-[60vh]">
          {entries.map((entry) => (
            <EntryRow key={entryKey(entry)} entry={entry} onSelect={onSelect} />
          ))}
        </div>
      </div>
    </div>
  );
}

type Bucket = { label: string; days: { date: Date; label: string; entries: CalendarEntry[] }[] };

/**
 * The agenda's grouping: Today, Tomorrow, the rest of the coming week, and
 * Later. Today and Tomorrow are one day each and need no day label under them;
 * the other two hold several days and label each.
 */
function bucketFor(diff: number): string {
  if (diff <= 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (diff < 7) return "This week";
  return "Later";
}

/**
 * The calendar page: what is coming up, as an agenda of artwork over a chosen
 * range or as a month of posters, opening the title panel on selection.
 */
export function CalendarPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const view: ViewMode = searchParams.get("view") === "month" ? "month" : "agenda";
  const agendaDays = parseRange(searchParams.get("days"));
  const [openDay, setOpenDay] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { openTitle } = useTitlePanel();

  // Re-read at midnight rather than frozen at mount: a tab left open overnight
  // labelled tomorrow's episodes "Today" and highlighted the wrong grid cell.
  const now = useClockBoundary();
  const today = useMemo(() => startOfDay(now), [now]);
  const compact = useMediaQuery(COMPACT_QUERY);

  // A month before this one can only ever be empty — the calendar is forward-
  // looking — so a URL naming one opens on the current month instead.
  const requestedMonth = parseMonthParam(searchParams.get("month"));
  const monthCursor =
    requestedMonth && requestedMonth.getTime() >= startOfMonth(today).getTime() ? requestedMonth : startOfMonth(today);

  const setParams = useCallback(
    (changes: Record<string, string | null>) =>
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(changes)) {
            if (value === null) params.delete(key);
            else params.set(key, value);
          }
          return params;
        },
        // Flicking between views and ranges is one act of looking; Back should
        // leave the calendar, not walk back through every toggle.
        { replace: true }
      ),
    [setSearchParams]
  );

  const setView = (next: ViewMode) => setParams({ view: next === "agenda" ? null : next });
  const setRange = (next: AgendaRange) => setParams({ days: next === DEFAULT_RANGE ? null : `${next}` });
  const setMonth = (next: Date) =>
    setParams({ month: next.getTime() === startOfMonth(today).getTime() ? null : monthParam(next) });

  // How many days ahead to request from the API depends on the active view:
  // agenda uses the selected range directly, month view needs enough days to
  // cover the last day of the displayed month (capped at what the API allows).
  const daysNeeded = useMemo(() => {
    if (view === "agenda") return agendaDays;
    const monthEnd = new Date(monthCursor.getFullYear(), monthCursor.getMonth() + 1, 0);
    return Math.min(Math.max(dayDiff(monthEnd, today), 1), MAX_CALENDAR_DAYS);
  }, [view, agendaDays, monthCursor, today]);

  // Keyed by the range, because the range is part of the question: the agenda's
  // selector and the month grid ask for different spans, and seeding a 7-day
  // answer into a 30-day view would paint the wrong thing for a frame.
  const [entries, setEntries, entriesMeta] = useCachedState<CalendarEntry[]>(
    `calendar:entries:${daysNeeded}`,
    []
  );
  const [loading, setLoading] = useState(!entriesMeta.hadCachedValue);

  // The displayed month is entirely beyond what the API can return.
  const monthOutOfRange = view === "month" && dayDiff(startOfMonth(monthCursor), today) > MAX_CALENDAR_DAYS;
  const atEarliestMonth = startOfMonth(monthCursor).getTime() <= startOfMonth(today).getTime();

  const load = useCallback(async (days: number) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getCalendar(days);
      setEntries(res.calendar);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load calendar");
    } finally {
      setLoading(false);
    }
    // `setEntries` is bound to the current day range's cache key, so it has to
    // be a dependency — capturing the first range's setter would file every
    // later range's results under the wrong key.
  }, [setEntries]);

  // `today` is a dependency for the agenda, where `daysNeeded` is the selected
  // range and so does not change at midnight. The API answers "the next N days
  // from now", so without it a tab left open overnight kept yesterday's window.
  useEffect(() => {
    if (monthOutOfRange) {
      setEntries([]);
      setLoading(false);
      return;
    }
    void load(daysNeeded);
  }, [daysNeeded, today, monthOutOfRange, load, setEntries]);

  // The day list belongs to one month cell; leaving that grid — by paging
  // months or switching view — leaves it behind too.
  const monthKey = monthParam(monthCursor);
  useEffect(() => {
    setOpenDay(null);
  }, [view, monthKey]);

  const entriesByDate = useMemo(() => {
    const map = new Map<string, CalendarEntry[]>();
    for (const entry of entries) {
      const key = dateKey(parseAirDate(entry.airDate));
      const list = map.get(key);
      if (list) list.push(entry);
      else map.set(key, [entry]);
    }
    return map;
  }, [entries]);

  const buckets = useMemo(() => {
    const sorted = [...entries].sort((a, b) => a.airDate.localeCompare(b.airDate));
    const result: Bucket[] = [];
    for (const entry of sorted) {
      const date = parseAirDate(entry.airDate);
      const label = bucketFor(dayDiff(date, today));
      let bucket = result[result.length - 1];
      if (!bucket || bucket.label !== label) {
        bucket = { label, days: [] };
        result.push(bucket);
      }
      const key = dateKey(date);
      const day = bucket.days[bucket.days.length - 1];
      if (day && dateKey(day.date) === key) day.entries.push(entry);
      else bucket.days.push({ date, label: longDate(date), entries: [entry] });
    }
    return result;
  }, [entries, today]);

  const monthWeeks = useMemo(() => {
    const first = monthCursor;
    const gridStart = new Date(first);
    gridStart.setDate(gridStart.getDate() - gridStart.getDay());
    const lastDayOfMonth = new Date(first.getFullYear(), first.getMonth() + 1, 0);
    const gridEnd = new Date(lastDayOfMonth);
    gridEnd.setDate(gridEnd.getDate() + (6 - gridEnd.getDay()));

    const weeks: Date[][] = [];
    const cursor = new Date(gridStart);
    while (cursor <= gridEnd) {
      const week: Date[] = [];
      for (let d = 0; d < 7; d++) {
        week.push(new Date(cursor));
        cursor.setDate(cursor.getDate() + 1);
      }
      weeks.push(week);
    }
    return weeks;
    // Keyed on the month's string so a fresh Date for the same month (one per
    // render, from the URL) doesn't rebuild the grid.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monthKey]);

  const handleSelect = (entry: CalendarEntry) => openTitle(toSearchResult(entry));
  const monthTitle = monthCursor.toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const thumbsPerCell = compact ? MONTH_CELL_THUMBS_COMPACT : MONTH_CELL_THUMBS;
  let cardIndex = 0;

  return (
    <PageFrame className="space-y-6">
      <PageHeader
        title="Calendar"
        actions={<SegmentedControl label="Calendar view" options={VIEW_OPTIONS} value={view} onChange={setView} className="w-44" />}
      />

      {error && (
        <p role="alert" className="rounded-xl bg-rose-500/5 border border-rose-500/20 px-4 py-3 text-danger text-sm">{error}</p>
      )}

      {view === "agenda" ? (
        <>
          <SegmentedControl
            label="How far ahead"
            options={RANGE_OPTIONS}
            value={`${agendaDays}`}
            onChange={(next) => setRange(parseRange(next))}
            className="w-full max-w-sm"
          />

          {loading ? (
            <div aria-busy="true" className="space-y-3">
              <div className="skeleton h-6 w-24 rounded" aria-hidden="true" />
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" aria-hidden="true">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="skeleton aspect-video rounded-2xl" />
                ))}
              </div>
            </div>
          ) : buckets.length === 0 ? (
            <EmptyState
              title="Nothing coming up"
              action={<Link to="/search" className={EMPTY_STATE_ACTION}>Find something to follow &rarr;</Link>}
            >
              Nothing in the next {agendaDays} days. The calendar follows the series you are watching, and the
              films and shows on your watchlist.
            </EmptyState>
          ) : (
            <div className="space-y-8">
              {buckets.map((bucket) => {
                const singleDay = bucket.label === "Today" || bucket.label === "Tomorrow";
                return (
                  <section key={bucket.label} aria-label={bucket.label} className="space-y-3">
                    <h2 className={SECTION_TITLE} style={{ color: "var(--text)" }}>
                      {bucket.label}
                      {singleDay && bucket.days[0] && (
                        <span className="meta ml-2 font-normal" style={{ color: "var(--text-mute)" }}>
                          {bucket.days[0].date.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })}
                        </span>
                      )}
                    </h2>
                    {bucket.days.map((day) => (
                      <div key={dateKey(day.date)} className="space-y-2">
                        {!singleDay && (
                          <h3 className={KICKER} style={{ color: "var(--text-mute)" }}>{day.label}</h3>
                        )}
                        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                          {day.entries.map((entry) => (
                            <EntryCard key={entryKey(entry)} entry={entry} onSelect={handleSelect} eager={cardIndex++ < 4} />
                          ))}
                        </div>
                      </div>
                    ))}
                  </section>
                );
              })}
            </div>
          )}
        </>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                disabled={atEarliestMonth}
                onClick={() => setMonth(addMonths(monthCursor, -1))}
                aria-label="Previous month"
                aria-describedby={atEarliestMonth ? "calendar-forward-only" : undefined}
                title={atEarliestMonth ? "The calendar only shows what is still to come" : undefined}
                className="flex h-8 w-8 items-center justify-center rounded-lg transition-colors hover:bg-[var(--surface-strong)] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
                style={{ border: "1px solid var(--border)", color: "var(--text-dim)" }}
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={() => setMonth(addMonths(monthCursor, 1))}
                aria-label="Next month"
                className="flex h-8 w-8 items-center justify-center rounded-lg transition-colors hover:bg-[var(--surface-strong)]"
                style={{ border: "1px solid var(--border)", color: "var(--text-dim)" }}
              >
                <ChevronRight className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={() => setMonth(startOfMonth(today))}
                className="ml-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors hover:bg-[var(--surface-strong)]"
                style={{ border: "1px solid var(--border)", color: "var(--text-dim)" }}
              >
                Today
              </button>
            </div>
            <h2 className={SECTION_TITLE} style={{ color: "var(--text)" }}>{monthTitle}</h2>
          </div>

          {atEarliestMonth && (
            <p id="calendar-forward-only" className="-mt-3 text-xs" style={{ color: "var(--text-mute)" }}>
              The calendar is forward-looking — it lists what hasn't aired or been released yet, so earlier months
              aren't available.
            </p>
          )}

          {/* Table roles rather than a <table>: a real grid of rows and
              columns, so a screen reader can tell which 12 a cell is, while the
              CSS grid stays as it is. `table` rather than `grid`, which would
              owe arrow-key cell navigation. */}
          {monthOutOfRange ? (
            <EmptyState title="Too far ahead">
              The calendar looks {MAX_CALENDAR_DAYS} days ahead, and this month starts after that.
            </EmptyState>
          ) : (
            <div
              className="overflow-hidden rounded-2xl"
              style={{ border: "1px solid var(--border)" }}
              role={loading ? undefined : "table"}
              aria-label={loading ? undefined : `What's coming in ${monthTitle}`}
            >
              <div role={loading ? undefined : "rowgroup"} style={{ borderBottom: "1px solid var(--border)" }}>
                <div className="grid grid-cols-7" role={loading ? undefined : "row"}>
                  {WEEKDAY_LABELS.map((label, i) => (
                    <div
                      key={label}
                      role={loading ? undefined : "columnheader"}
                      aria-label={WEEKDAY_FULL[i]}
                      className={`px-1 py-2 text-center ${MICRO_LABEL}`}
                      style={{ color: "var(--text-mute)" }}
                    >
                      {compact ? label.charAt(0) : label}
                    </div>
                  ))}
                </div>
              </div>
              {loading ? (
                <div className="p-4" aria-busy="true">
                  <div className="skeleton h-80 rounded-lg" aria-hidden="true" />
                </div>
              ) : (
                <div role="rowgroup">
                  {monthWeeks.map((week, wi) => (
                    <div key={wi} role="row" className="grid grid-cols-7" style={{ borderTop: wi > 0 ? "1px solid var(--border)" : undefined }}>
                      {week.map((day) => {
                        const inMonth = day.getMonth() === monthCursor.getMonth();
                        const isToday = startOfDay(day).getTime() === today.getTime();
                        const dayEntries = inMonth ? entriesByDate.get(dateKey(day)) ?? [] : [];
                        const shown = dayEntries.slice(0, thumbsPerCell);
                        const more = dayEntries.length - shown.length;
                        const dateLabel = day.toLocaleDateString(undefined, {
                          weekday: "long",
                          month: "long",
                          day: "numeric",
                          year: "numeric",
                        });
                        const number = (
                          <span
                            aria-hidden="true"
                            className={`meta inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1 font-semibold ${isToday ? "bg-accent-500 text-accent-on" : ""}`}
                            style={isToday ? undefined : { color: "var(--text-mute)" }}
                          >
                            {day.getDate()}
                          </span>
                        );
                        return (
                          <div
                            key={day.toISOString()}
                            role="cell"
                            className="min-h-[5.5rem] sm:min-h-[8rem]"
                            style={{
                              borderLeft: "1px solid var(--border)",
                              background: inMonth ? undefined : "var(--surface)",
                              opacity: inMonth ? 1 : 0.5,
                            }}
                          >
                            {dayEntries.length === 0 ? (
                              <div className="flex flex-col items-start p-1.5">
                                <span className="sr-only">{dateLabel}{isToday ? " (today)" : ""}</span>
                                {number}
                              </div>
                            ) : (
                              /* The whole day is the target: a tap opens its
                                 list, where each entry is a row big enough to
                                 hit. Posters at 40px are a picture of the day,
                                 not a row of buttons. */
                              <button
                                type="button"
                                onClick={() => setOpenDay(day)}
                                aria-label={`${dateLabel}${isToday ? " (today)" : ""}: ${countLabel(dayEntries)}`}
                                className="flex h-full w-full flex-col items-start p-1.5 text-left transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-inset"
                              >
                                {number}
                                <span className="mt-1.5 flex flex-wrap items-end gap-1" aria-hidden="true">
                                  {shown.map((entry) => (
                                    <span
                                      key={entryKey(entry)}
                                      className="block w-8 flex-none overflow-hidden rounded sm:w-10"
                                      style={{ aspectRatio: "2 / 3", background: "var(--surface-strong)", boxShadow: "0 0 0 1px var(--border)" }}
                                    >
                                      <Poster src={entry.poster ?? undefined} alt="" className="h-full w-full" sizes="40px" />
                                    </span>
                                  ))}
                                  {more > 0 && (
                                    <span className="meta font-semibold" style={{ color: "var(--text-mute)" }}>+{more}</span>
                                  )}
                                </span>
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </>
      )}

      {openDay && (
        <DayEntriesModal
          date={openDay}
          entries={entriesByDate.get(dateKey(openDay)) ?? []}
          // One overlay at a time: the detail panel replaces the day list
          // rather than stacking on top of it.
          onSelect={(entry) => { setOpenDay(null); handleSelect(entry); }}
          onClose={() => setOpenDay(null)}
        />
      )}
    </PageFrame>
  );
}
