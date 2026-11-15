/**
 * Getting your data out, and getting someone else's in.
 */

import { request } from "./client";
import type {
  ExportPayload,
  ImportSummary,
} from "./types";

/** A Stremio account import: the link code to approve, then the import it starts. */
export type StremioImportState =
  | { state: "idle" }
  | { state: "waiting"; code: string; link: string; expiresAt: string }
  | { state: "importing" }
  | {
      state: "done";
      summary: { movies: number; episodes: number; resumePoints: number; skipped: number; unreadable: number };
    }
  | { state: "expired" }
  | { state: "failed"; error: string };

export type ExternalImportFormat =
  | "letterboxd-diary"
  | "letterboxd-ratings"
  | "letterboxd-watched"
  | "letterboxd-watchlist"
  | "imdb-ratings"
  | "imdb-watchlist"
  | "imdb-list"
  | "simkl";

/** Watches that look like one watch recorded more than once, as `GET /watch/duplicates` returns them. */
export type DuplicateWatchGroup = {
  type: "movie" | "episode";
  imdbId: string;
  season: number | null;
  episode: number | null;
  title: string | null;
  /** The row a merge keeps. */
  keepId: string;
  events: {
    id: string;
    watchedAt: string;
    dateUnknown: boolean;
    plays: number;
    note: string | null;
    fromTrakt: boolean;
  }[];
};

export const dataApi = {
  findDuplicateWatches() {
    return request<{ groups: DuplicateWatchGroup[]; truncated: boolean }>("/watch/duplicates", { timeoutMs: 60000 });
  },
  /** Each inner array is one group's event ids, folded into one watch. */
  mergeDuplicateWatches(groups: string[][]) {
    return request<{ merged: number; removed: number }>("/watch/duplicates/merge", {
      method: "POST",
      body: JSON.stringify({ groups }),
      timeoutMs: 60000,
    });
  },
  // Data export / import
  exportData() {
    return request<ExportPayload>("/export", { timeoutMs: 60000 });
  },
  /** Clears the active profile's watch history (and resume points), its ratings, or both. */
  clearProfileData(what: { history?: boolean; ratings?: boolean }) {
    return request<{ cleared: { watchEvents: number; seriesProgress: number; playbackProgress: number; ratings: number } }>(
      "/data/clear",
      { method: "POST", body: JSON.stringify(what) }
    );
  },
  importData(payload: ExportPayload) {
    return request<{ status: string; summary: ImportSummary }>("/import", {
      method: "POST",
      body: JSON.stringify(payload),
      timeoutMs: 60000,
    });
  },
  importCsv(csv: string) {
    return request<{ status: string; summary: { imported: number; skipped: number } }>("/import/csv", {
      method: "POST",
      body: JSON.stringify({ csv }),
      timeoutMs: 60000,
    });
  },
  startStremioImport() {
    return request<StremioImportState>("/import/stremio/link", { method: "POST" });
  },
  pollStremioImport() {
    return request<StremioImportState>("/import/stremio/link/poll", { method: "POST" });
  },
  cancelStremioImport() {
    return request<StremioImportState>("/import/stremio/link/cancel", { method: "POST" });
  },
  /** [listName] is the custom list an `imdb-list` import fills; it is created when there is none. */
  importExternal(format: ExternalImportFormat, csv: string, listName?: string) {
    return request<{
      status: string;
      format: string;
      summary: { imported: number; ratingsImported: number; listed: number; skipped: number };
    }>("/import/external", {
      method: "POST",
      body: JSON.stringify({ format, csv, ...(listName ? { listName } : {}) }),
      timeoutMs: 120000,
    });
  },
};
