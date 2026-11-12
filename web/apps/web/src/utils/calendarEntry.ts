import type { CalendarEntry } from "../api";

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
