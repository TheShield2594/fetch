import type { WatchEvent } from "../api";

/*
 * How a flat list of watch events becomes the History page.
 *
 * The page used to draw one row per event under "This Week" / "This Month"
 * headings. Two things were wrong with that once there was real data in it. A
 * run of one show was the same poster and the same title repeated down the
 * page with only the episode code changing; and a heading that covers a week
 * says nothing about any day in it.
 *
 * So: one group per calendar day, with what the day added up to, and inside a
 * day, consecutive episodes of one show folded into a single row.
 *
 * Kept out of the component because all of it is arithmetic on a list, and the
 * cases that matter — a run broken by a film, a day that straddles two loaded
 * pages, a season boundary inside a run — are far easier to pin down here than
 * through a rendered page.
 */

/** Two or more consecutive episodes of one show, newest first, as they were listed. */
export type EpisodeRun = [WatchEvent, WatchEvent, ...WatchEvent[]];

export type HistoryRow =
  | { kind: "single"; event: WatchEvent }
  | { kind: "run"; events: EpisodeRun };

export type HistoryDay = {
  /** `2026-11-12` in local time, or `unknown` for watches with no date. */
  key: string;
  /** Local midnight of the day, or `null` for the undated group. */
  date: Date | null;
  rows: HistoryRow[];
  episodes: number;
  films: number;
  /** Minutes, summed over the events whose runtime is known. */
  minutes: number;
  /** True when at least one event had no runtime, so `minutes` is a floor. */
  minutesPartial: boolean;
};

const pad = (n: number) => String(n).padStart(2, "0");

/** The local calendar day an event belongs to. */
export function dayKey(event: Pick<WatchEvent, "watchedAt" | "dateUnknown">): string {
  if (event.dateUnknown) return "unknown";
  const d = new Date(event.watchedAt);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// What makes two neighbouring rows the same show. An episode with no series id
// (a row imported before the id was known) has nothing to be grouped by.
const seriesOf = (event: WatchEvent): string | null =>
  event.type === "episode" ? (event.seriesImdbId ?? null) : null;

/**
 * Groups events — already newest first, as the API returns them — into days,
 * and folds each day's consecutive same-show episodes into runs.
 *
 * "Consecutive" is deliberate. Two episodes of a show with a film between them
 * are two sittings, and merging them would move the film out of the order it
 * was watched in.
 */
export function groupHistory(events: readonly WatchEvent[]): HistoryDay[] {
  const newDay = (key: string, date: Date | null): HistoryDay => ({
    key,
    date,
    rows: [],
    episodes: 0,
    films: 0,
    minutes: 0,
    minutesPartial: false,
  });

  const days: HistoryDay[] = [];
  // Undated watches still carry a `watchedAt` — the row needs an order — so
  // they arrive scattered among the dated ones. They are one group, not one
  // per place they happen to fall, and it goes last: it is the part of the
  // list that has no place in time.
  let undated: HistoryDay | null = null;

  // The run being built, and the day it will land in. A run is consecutive in
  // the list as it arrived: anything between two episodes ends it, including
  // a row that belongs to a different group.
  let pending: WatchEvent[] = [];
  let pendingSeries: string | null = null;
  let pendingDay: HistoryDay | null = null;

  const flush = () => {
    const [first, second, ...rest] = pending;
    if (pendingDay && first && second) pendingDay.rows.push({ kind: "run", events: [first, second, ...rest] });
    else if (pendingDay && first) pendingDay.rows.push({ kind: "single", event: first });
    pending = [];
    pendingSeries = null;
    pendingDay = null;
  };

  for (const event of events) {
    let day: HistoryDay;
    if (event.dateUnknown) {
      undated ??= newDay("unknown", null);
      day = undated;
    } else {
      const key = dayKey(event);
      const last = days[days.length - 1];
      if (last && last.key === key) {
        day = last;
      } else {
        const at = new Date(event.watchedAt);
        day = newDay(key, new Date(at.getFullYear(), at.getMonth(), at.getDate()));
        days.push(day);
      }
    }

    if (event.type === "movie") day.films++;
    else day.episodes++;
    if (event.runtime != null && event.runtime > 0) day.minutes += event.runtime;
    else day.minutesPartial = true;

    const series = seriesOf(event);
    if (series !== null && series === pendingSeries && day === pendingDay) {
      pending.push(event);
      continue;
    }
    flush();
    if (series !== null) {
      pending = [event];
      pendingSeries = series;
      pendingDay = day;
    } else {
      day.rows.push({ kind: "single", event });
    }
  }
  flush();
  return undated ? [...days, undated] : days;
}

type Numbered = { season: number; episode: number };

/**
 * `S2 E13–E18` — which episodes a run covers.
 *
 * The run arrives in watch order, newest first, but is described in episode
 * order, because that is how the range reads. A run inside one season with no
 * gaps is a plain range; one with gaps is still given as first–last, since the
 * count beside it says how many of those were actually watched; one that
 * crosses a season boundary names both ends in full.
 */
export function episodeRangeLabel(events: readonly WatchEvent[]): string {
  const numbered = events
    .filter((e): e is WatchEvent & Numbered => e.season != null && e.episode != null)
    .map((e) => ({ season: e.season, episode: e.episode }))
    .sort((a, b) => a.season - b.season || a.episode - b.episode);
  const first = numbered[0];
  const last = numbered[numbered.length - 1];
  if (!first || !last) return "";
  if (first.season === last.season) {
    return first.episode === last.episode
      ? `S${first.season} E${first.episode}`
      : `S${first.season} E${first.episode}–E${last.episode}`;
  }
  return `S${first.season} E${first.episode} – S${last.season} E${last.episode}`;
}

/** `Today`, `Yesterday`, `Tuesday, 3 November` — and the year once it isn't this one. */
export function dayLabel(date: Date | null, now: Date): string {
  if (!date) return "Date unknown";
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diffDays = Math.round((startOfDay(now) - startOfDay(date)) / 86_400_000);
  if (diffDays === 0) return "Today";
  if (diffDays === 1) return "Yesterday";
  return date.toLocaleDateString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  });
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** `3h 40m`, `45m`, `2h`. */
export function formatMinutes(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

/**
 * `3 episodes, 1 film · 3h 40m` — what a day added up to.
 *
 * The time is left off rather than guessed when no runtime is known, and
 * marked as a floor (`3h 40m+`) when only some are.
 */
export function dayTotals(day: Pick<HistoryDay, "episodes" | "films" | "minutes" | "minutesPartial">): string {
  const counts = [
    day.episodes > 0 ? plural(day.episodes, "episode", "episodes") : null,
    day.films > 0 ? plural(day.films, "film", "films") : null,
  ].filter(Boolean).join(", ");
  if (day.minutes <= 0) return counts;
  return `${counts} · ${formatMinutes(day.minutes)}${day.minutesPartial ? "+" : ""}`;
}

/**
 * The instant a month ends, as the `before` the history API takes: asking for
 * everything before it opens the list at that month's last watch.
 *
 * Local time, like the day headings — "March" means the reader's March.
 */
export function monthEndIso(month: string): string | null {
  const match = month.match(/^(\d{4})-(\d{2})$/);
  if (!match) return null;
  const year = Number(match[1]);
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex > 11) return null;
  return new Date(year, monthIndex + 1, 1).toISOString();
}

/** `March 2026` for `2026-03`. */
export function monthLabel(month: string, options: { year?: boolean } = {}): string {
  const [year, m] = month.split("-").map(Number);
  return new Date(year ?? 0, (m ?? 1) - 1, 15).toLocaleDateString(undefined, {
    month: "long",
    ...(options.year === false ? {} : { year: "numeric" }),
  });
}

/**
 * One card per title for a rail of recent watches.
 *
 * The Discover page's "Recently Watched" had the History page's problem turned
 * sideways: six episodes of one show were six identical posters in a row. Here
 * a title appears once, at the position of its most recent watch, with how
 * many of the listed watches were of it.
 */
export type RecentTitle = {
  /** The most recent watch of the title — what the card opens and dates itself by. */
  latest: WatchEvent;
  /** Every listed watch of the title, newest first. */
  events: [WatchEvent, ...WatchEvent[]];
};

export function recentTitles(events: readonly WatchEvent[]): RecentTitle[] {
  const byTitle = new Map<string, RecentTitle>();
  for (const event of events) {
    const id = event.type === "episode" ? (event.seriesImdbId ?? event.imdbId) : event.imdbId;
    const key = `${event.type === "movie" ? "movie" : "series"}:${id}`;
    const existing = byTitle.get(key);
    if (existing) existing.events.push(event);
    else byTitle.set(key, { latest: event, events: [event] });
  }
  return [...byTitle.values()];
}
