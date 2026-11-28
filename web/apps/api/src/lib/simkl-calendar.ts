import type { FastifyBaseLogger } from "fastify";
import { LRUCache } from "lru-cache";
import { getConnection } from "./connections.js";
import { isSimklConfigured } from "./simkl.js";

// Episode air times from Simkl's public TV calendar, for a profile connected to
// Simkl: TMDB, which the calendar is built from, has only dates, and Trakt's
// calendar only serves profiles connected to Trakt. The TV app reads the same
// data (app/.../data/simkl/SimklCalendarAirTimes.kt).
//
// Simkl publishes one file per month for every show it tracks
// (data.simkl.in/calendar/v2/{year}/{month}/tv.json, month not zero-padded,
// from July 2024), regenerated every 6 to 24 hours. Times are UTC. The files are
// global, not per account, so one month's read serves every profile.

const CALENDAR_BASE = "https://data.simkl.in/calendar/v2";
const APP_NAME = process.env.SIMKL_APP_NAME?.trim() || "fetch";
const APP_VERSION = "web";
const REQUEST_TIMEOUT_MS = 20_000;
/** Simkl's earliest monthly file. */
const FIRST_MONTH = { year: 2024, month: 7 };

/** One month's air times by `imdb:season:episode`, kept as long as Simkl's fastest regeneration. */
const monthCache = new LRUCache<string, Map<string, string>>({ max: 8, ttl: 6 * 60 * 60 * 1000 });

type SimklCalendarFile = {
  calendar?: { simkl_id?: number; date?: string; episode?: { season?: number; episode?: number } | null }[];
  metadata?: Record<string, { ids?: { imdb?: string | null } | null } | undefined>;
};

const easternClock = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/New_York",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/**
 * Air times by `imdb:season:episode` from one month's file. A time of exactly
 * midnight US Eastern is left out: it's by far the files' most common time
 * (05:00Z in winter, 04:00Z in summer) and stands for a date with no time
 * known, so a "12:00 AM" would be wrong far more often than right.
 */
export const simklTimesFromFile = (file: SimklCalendarFile): Map<string, string> => {
  const times = new Map<string, string>();
  for (const item of file.calendar ?? []) {
    const season = item.episode?.season;
    const number = item.episode?.episode;
    if (!item.date || season == null || number == null || item.simkl_id == null) continue;
    const instant = new Date(item.date);
    if (Number.isNaN(instant.getTime())) continue;
    if (easternClock.format(instant) === "00:00") continue;
    const imdb = file.metadata?.[String(item.simkl_id)]?.ids?.imdb;
    if (!imdb) continue;
    times.set(`${imdb}:${season}:${number}`, item.date);
  }
  return times;
};

/** The `{year, month}` of every month the days from `from` through `to` touch, Simkl's first onward. */
export const monthsCovering = (from: Date, to: Date): { year: number; month: number }[] => {
  const months: { year: number; month: number }[] = [];
  let year = from.getUTCFullYear();
  let month = from.getUTCMonth() + 1;
  const lastYear = to.getUTCFullYear();
  const lastMonth = to.getUTCMonth() + 1;
  while (year < lastYear || (year === lastYear && month <= lastMonth)) {
    if (year > FIRST_MONTH.year || (year === FIRST_MONTH.year && month >= FIRST_MONTH.month)) months.push({ year, month });
    month += 1;
    if (month > 12) {
      month = 1;
      year += 1;
    }
  }
  return months;
};

const loadMonth = async (year: number, month: number, clientId: string): Promise<Map<string, string>> => {
  const key = `${year}/${month}`;
  const cached = monthCache.get(key);
  if (cached) return cached;
  // Simkl asks every request to name the app; the CDN ignores query strings for caching.
  const url = new URL(`${CALENDAR_BASE}/${year}/${month}/tv.json`);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("app-name", APP_NAME);
  url.searchParams.set("app-version", APP_VERSION);
  const response = await fetch(url, {
    headers: { Accept: "application/json", "User-Agent": `Fetch/${APP_VERSION}` },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    redirect: "error",
  });
  if (!response.ok) throw new Error(`Simkl calendar ${key} failed (${response.status})`);
  const times = simklTimesFromFile((await response.json()) as SimklCalendarFile);
  monthCache.set(key, times);
  return times;
};

/**
 * The instant each episode airs in the days from `from` through `to`, keyed by
 * `imdb:season:episode`, for a profile connected to Simkl. Empty otherwise, and
 * on any failure: a time of day is a refinement, and the calendar is right
 * without one.
 */
export async function simklAirTimes(
  profileId: string,
  from: Date,
  to: Date,
  log?: FastifyBaseLogger
): Promise<Map<string, string>> {
  const times = new Map<string, string>();
  const clientId = process.env.SIMKL_CLIENT_ID?.trim();
  if (!isSimklConfigured() || !clientId) return times;
  try {
    if (!(await getConnection(profileId, "simkl"))) return times;
    const months = await Promise.all(monthsCovering(from, to).map(({ year, month }) => loadMonth(year, month, clientId)));
    for (const month of months) for (const [key, value] of month) times.set(key, value);
  } catch (err) {
    log?.warn({ err }, "Simkl calendar unavailable; the calendar shows dates without Simkl times");
  }
  return times;
}
