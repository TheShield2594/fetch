import type { CalendarEntry } from "../api";
import { localDateFromIsoDate } from "./calendarDate";

/** A stable key for an entry: a film by its id, an episode by its number too. */
export function calendarEntryKey(entry: CalendarEntry): string {
  return entry.kind === "movie"
    ? `${entry.seriesImdbId}-film`
    : `${entry.seriesImdbId}-s${entry.season}e${entry.episode}`;
}

/**
 * What a calendar entry is, in the words its card leads with: "S2 E5 · The
 * Name", "Season 3 premiere", "Series premiere" or "Film release".
 */
export function calendarEntryLabel(entry: CalendarEntry, { withName = true } = {}): string {
  if (entry.kind === "movie") return "Film release";
  if (entry.kind === "premiere") return entry.season === 1 ? "Series premiere" : `Season ${entry.season} premiere`;
  const number = `S${entry.season} E${entry.episode}`;
  return withName && entry.episodeName ? `${number} · ${entry.episodeName}` : number;
}

/**
 * The badge an entry carries when its episode opens or ends something:
 * "Series finale", "Season finale", "Mid-season finale" or "Premiere", else
 * null. One badge: a finale ahead of a premiere, as on the TV app, since a
 * one-episode season's only episode is both and its end is the news.
 */
export function calendarEntryBadge(entry: CalendarEntry): string | null {
  if (entry.kind === "movie") return null;
  switch (entry.finale) {
    case "series":
      return "Series finale";
    case "season":
      return "Season finale";
    case "mid_season":
      return "Mid-season finale";
    default:
      return entry.kind === "premiere" ? "Premiere" : null;
  }
}

/**
 * The local day an entry falls on: the day of its air instant where a source
 * knew one (a 9pm Eastern episode is the next morning in Europe), else the day
 * it was dated. Midnight local time either way. An unreadable date stays an
 * Invalid Date, which groups on its own rather than vanishing.
 */
export function calendarEntryDay(entry: CalendarEntry): Date {
  if (entry.airsAt) {
    const at = new Date(entry.airsAt);
    if (!Number.isNaN(at.getTime())) return new Date(at.getFullYear(), at.getMonth(), at.getDate());
  }
  return localDateFromIsoDate(entry.airDate) ?? new Date(NaN);
}

/** "9:00 PM" in the reader's own clock, or null when only the day is known. */
export function calendarEntryTime(entry: CalendarEntry): string | null {
  if (!entry.airsAt) return null;
  const at = new Date(entry.airsAt);
  return Number.isNaN(at.getTime()) ? null : at.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}
