/**
 * What has been watched, what is part-way through, and what is on next.
 */

import { request, validated } from "./client";
import { parseCalendarResponse, parseSeriesProgressListResponse, parseWatchHistoryResponse } from "@fetch/shared/contracts";
import type {
  CheckIn,
  DetailedWatchStats,
  PlaybackProgress,
  EpisodeInfo,
  ScrobbleSession,
  WatchEvent,
  WatchStats,
  WatchedEpisode,
  YearInReviewStats,
} from "./types";

/** When a manual mark says the watch happened. */
export interface WatchTime {
  /** ISO 8601. */
  watchedAt: string;
  dateUnknown?: boolean | undefined;
}

export const watchApi = {
  async getSeriesProgress(signal?: AbortSignal) {
    const res = await request<unknown>("/series/progress", { signal });
    return validated("/series/progress", parseSeriesProgressListResponse, res).progress;
  },
  async getWatchHistory(
    limit = 10,
    offset = 0,
    opts?: {
      imdbId?: string | undefined;
      type?: "movie" | "episode" | undefined;
      /** Title text: films by name, episodes by their show's. */
      q?: string | undefined;
      /** ISO instant; only watches before it. Offsets then count from there. */
      before?: string | undefined;
      signal?: AbortSignal | undefined;
    }
  ) {
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
    if (opts?.imdbId) params.set("imdbId", opts.imdbId);
    if (opts?.type) params.set("type", opts.type);
    if (opts?.q) params.set("q", opts.q);
    if (opts?.before) params.set("before", opts.before);
    const res = await request<unknown>(`/watch/history?${params}`, { signal: opts?.signal });
    return validated("/watch/history", parseWatchHistoryResponse, res).history;
  },
  /**
   * Every title the profile has watched, however long ago: films by id, shows
   * by their series id. For filtering out what's been seen, which the newest
   * page of history can't do.
   */
  getWatchedTitles(signal?: AbortSignal) {
    return request<{ movies: string[]; series: string[] }>("/watch/titles", { signal });
  },
  /** The months that have history in them, newest first — for jumping to one. */
  async getWatchHistoryMonths(opts?: { type?: "movie" | "episode" | undefined; signal?: AbortSignal | undefined }) {
    const params = new URLSearchParams();
    // The reader's zone, so a month here is the same month the day headings are in.
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (tz) params.set("tz", tz);
    if (opts?.type) params.set("type", opts.type);
    const res = await request<{ months?: { month: string; events: number }[] }>(
      `/watch/history/months?${params}`,
      { signal: opts?.signal }
    );
    return Array.isArray(res.months) ? res.months : [];
  },
  getWatchStats(signal?: AbortSignal) {
    return request<WatchStats>("/watch/stats", { signal });
  },
  /**
   * Answers with the episode the server marked. An API from before it did
   * answers 204, which arrives as `undefined`.
   */
  markNextEpisodeWatched(imdbId: string) {
    return request<{ season: number; episode: number } | undefined>(`/series/${encodeURIComponent(imdbId)}/watch-next`, {
      method: "POST"
    });
  },
  getDetailedStats(signal?: AbortSignal) {
    return request<DetailedWatchStats>("/watch/stats/detailed", { signal });
  },
  getYearInReview(year: number) {
    return request<YearInReviewStats>(`/watch/stats/year/${year}`);
  },
  getSeasonEpisodes(imdbId: string, seasonNumber: number, signal?: AbortSignal) {
    return request<{ episodes: EpisodeInfo[] }>(
      `/meta/series/${encodeURIComponent(imdbId)}/season/${seasonNumber}/episodes`, { signal }
    );
  },
  getWatchedEpisodes(imdbId: string, signal?: AbortSignal) {
    return request<{ episodes: WatchedEpisode[] }>(
      `/series/${encodeURIComponent(imdbId)}/watched-episodes`, { signal }
    );
  },
  /** Without `when`, the watch is now. */
  markEpisodeWatched(imdbId: string, seasonNumber: number, episodeNumber: number, when?: WatchTime) {
    return request<void>(
      `/series/${encodeURIComponent(imdbId)}/season/${seasonNumber}/episode/${episodeNumber}/watch`,
      when ? { method: "POST", body: JSON.stringify(when) } : { method: "POST" }
    );
  },
  unmarkEpisodeWatched(imdbId: string, seasonNumber: number, episodeNumber: number) {
    return request<void>(
      `/series/${encodeURIComponent(imdbId)}/season/${seasonNumber}/episode/${episodeNumber}/watch`,
      { method: "DELETE" }
    );
  },
  /**
   * `when` is the time every episode is logged at — now, without it — and
   * `watchedAtByEpisode` overrides it for the episodes it names (their air
   * dates, for "Release dates").
   */
  markSeasonWatched(
    imdbId: string,
    seasonNumber: number,
    episodeNumbers: number[],
    when?: WatchTime,
    watchedAtByEpisode?: Record<number, string>
  ) {
    return request<{ marked: number; total: number }>(
      `/series/${encodeURIComponent(imdbId)}/season/${seasonNumber}/watch-all`,
      { method: "POST", body: JSON.stringify({ episodeNumbers, ...when, ...(watchedAtByEpisode ? { watchedAtByEpisode } : {}) }) }
    );
  },
  dropShow(imdbId: string) {
    return request<{ dropped: boolean }>(`/show/${encodeURIComponent(imdbId)}/drop`, { method: "POST" });
  },
  undropShow(imdbId: string) {
    return request<{ dropped: boolean }>(`/show/${encodeURIComponent(imdbId)}/drop`, { method: "DELETE" });
  },
  deleteWatchEvent(eventId: string) {
    return request<void>(`/watch/${encodeURIComponent(eventId)}`, { method: "DELETE" });
  },
  // Sets or clears the note on a watch that already exists — the only way to
  // change one, since the note travels with the event at creation time.
  updateWatchEventNote(eventId: string, note: string | null) {
    return request<{ watchEvent: WatchEvent }>(`/watch/${encodeURIComponent(eventId)}`, {
      method: "PATCH",
      body: JSON.stringify({ note }),
    });
  },
  logWatch(payload: { type: "movie" | "episode"; imdbId: string; seriesImdbId?: string | undefined; season?: number | undefined; episode?: number | undefined; watchedAt: string; dateUnknown?: boolean | undefined; note?: string | null | undefined }) {
    return request<{ watchEvent: { id: string } }>("/watch", { method: "POST", body: JSON.stringify(payload) });
  },
  // Check-in
  getCheckin(signal?: AbortSignal) {
    return request<{ checkin: CheckIn | null }>("/checkin", { signal });
  },
  startCheckin(payload: { type: "movie" | "episode"; imdbId: string; seriesImdbId?: string | undefined; name: string; poster?: string | undefined; season?: number | undefined; episode?: number | undefined; runtime?: number | null | undefined }) {
    return request<{ checkin: CheckIn }>("/checkin", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  endCheckin(logWatch = false) {
    return request<void>(`/checkin?log=${logWatch}`, { method: "DELETE" });
  },
  // Now playing (live Plex/Jellyfin scrobble sessions)
  getNowPlaying(signal?: AbortSignal) {
    return request<{ sessions: ScrobbleSession[] }>("/scrobble/now-playing", { signal });
  },
  // Resume points — where playback stopped part-way through a title
  getPlayback(signal?: AbortSignal) {
    return request<{ playback: PlaybackProgress[] }>("/playback", { signal });
  },
  // Puts one back — the Undo after removing it from Continue Watching.
  savePlayback(payload: {
    type: "movie" | "episode";
    imdbId: string;
    season?: number | null | undefined;
    episode?: number | null | undefined;
    progress: number;
    positionSeconds?: number | null | undefined;
    durationSeconds?: number | null | undefined;
  }) {
    return request<{ playback: PlaybackProgress }>("/playback", { method: "PUT", body: JSON.stringify(payload) });
  },
  deletePlayback(id: string) {
    return request<void>(`/playback/${encodeURIComponent(id)}`, { method: "DELETE" });
  },
  // Calendar
  /** `days` days from `start` (YYYY-MM-DD, today when omitted); the start may be in the past. */
  async getCalendar(days = 30, start?: string) {
    const query = start ? `?days=${days}&start=${encodeURIComponent(start)}` : `?days=${days}`;
    return validated("/calendar", parseCalendarResponse, await request<unknown>(`/calendar${query}`));
  },
};
