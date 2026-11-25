import type { CalendarEntry } from "@fetch/shared";

/**
 * A profile's calendar as an iCalendar (RFC 5545) document, for a phone or
 * desktop calendar to subscribe to. Pure: the entries in, the text out.
 */

/** Backslash, semicolon, comma and line breaks are the characters TEXT values must escape. */
const escapeText = (value: string): string =>
  value.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");

/**
 * Folds a content line at 75 octets, as the format requires, without cutting
 * a multi-byte character in half — a show called "Pokémon" must survive.
 */
const fold = (line: string): string => {
  const parts: string[] = [];
  let current = "";
  let bytes = 0;
  for (const char of line) {
    const size = Buffer.byteLength(char, "utf8");
    // Continuation lines start with a space, which counts toward their 75.
    if (bytes + size > (parts.length === 0 ? 75 : 74)) {
      parts.push(current);
      current = "";
      bytes = 0;
    }
    current += char;
    bytes += size;
  }
  parts.push(current);
  return parts.join("\r\n ");
};

const pad = (value: number): string => String(value).padStart(2, "0");

/** `20261125T031500Z` */
const utcStamp = (date: Date): string =>
  `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}` +
  `T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`;

/** `2026-11-25` → `20261125`, and the day after it for an all-day event's exclusive end. */
const dayStamp = (isoDay: string, offsetDays = 0): string => {
  const [year, month, day] = isoDay.split("-").map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day + offsetDays));
  return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}`;
};

const episodeCode = (entry: CalendarEntry): string | null =>
  entry.season !== null && entry.episode !== null ? `S${pad(entry.season)}E${pad(entry.episode)}` : null;

const summaryOf = (entry: CalendarEntry): string => {
  const code = episodeCode(entry);
  if (!code) return entry.seriesName;
  return entry.episodeName ? `${entry.seriesName} ${code} · ${entry.episodeName}` : `${entry.seriesName} ${code}`;
};

/** Stable per episode or film, so a calendar app updates an event that moved rather than adding a second. */
const uidOf = (entry: CalendarEntry): string => {
  const code = episodeCode(entry);
  return `${entry.seriesImdbId}${code ? `-${code.toLowerCase()}` : ""}@fetch`;
};

export const buildCalendarFeed = (
  entries: CalendarEntry[],
  options: { name: string; now?: Date }
): string => {
  const stamp = utcStamp(options.now ?? new Date());
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Fetch//Calendar//EN",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    `X-WR-CALNAME:${escapeText(options.name)}`,
    // A hint to clients that honour it; most poll on their own schedule.
    "REFRESH-INTERVAL;VALUE=DURATION:PT6H",
    "X-PUBLISHED-TTL:PT6H",
  ];

  for (const entry of entries) {
    lines.push("BEGIN:VEVENT", `UID:${uidOf(entry)}`, `DTSTAMP:${stamp}`);
    const airsAt = entry.airsAt ? new Date(entry.airsAt) : null;
    if (airsAt && !Number.isNaN(airsAt.getTime())) {
      // A known air time. No source gives a running time here, so an hour.
      lines.push(`DTSTART:${utcStamp(airsAt)}`, "DURATION:PT1H");
    } else {
      // TMDB knows the day only: an all-day event, whose end is exclusive.
      lines.push(`DTSTART;VALUE=DATE:${dayStamp(entry.airDate)}`, `DTEND;VALUE=DATE:${dayStamp(entry.airDate, 1)}`);
    }
    lines.push(`SUMMARY:${escapeText(summaryOf(entry))}`);
    if (entry.overview) lines.push(`DESCRIPTION:${escapeText(entry.overview)}`);
    lines.push("TRANSP:TRANSPARENT", "END:VEVENT");
  }

  lines.push("END:VCALENDAR");
  return `${lines.map(fold).join("\r\n")}\r\n`;
};
