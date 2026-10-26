/**
 * The services that feed watch history in: Trakt, Simkl and Stremio. Each is
 * the active profile's own connection.
 */

import { request } from "./client";
import type {
  PlaySignal,
  StremioLibraryStatus,
  StremioSyncSummary,
} from "./types";

export type SimklPin = { userCode: string; verificationUrl: string; expiresAt: string; interval: number };

export type SimklStatus = {
  configured: boolean;
  connected: boolean;
  username: string | null;
  lastSyncedAt: string | null;
  pending: SimklPin | null;
};

export type TrackerSyncSummary = { movies: number; episodes: number; watchlist: number; skipped: number; full: boolean };

export const integrationsApi = {
  getSimklStatus() {
    return request<SimklStatus>("/simkl/status");
  },
  startSimklPin() {
    return request<SimklPin>("/simkl/pin", { method: "POST" });
  },
  pollSimklPin() {
    return request<{ result: "pending" | "connected" | "expired" | "none"; status: SimklStatus }>("/simkl/pin/poll", {
      method: "POST",
    });
  },
  // A first sync reads the whole Simkl library, which can take a while.
  syncSimkl(full = false) {
    return request<TrackerSyncSummary>("/simkl/sync", { method: "POST", body: JSON.stringify({ full }), timeoutMs: 300000 });
  },
  disconnectSimkl() {
    return request<{ connected: boolean }>("/simkl/disconnect", { method: "POST" });
  },
  getTraktStatus() {
    return request<{ connected: boolean; configured: boolean; expiresAt: string | null; redirectUri: string }>("/trakt/status");
  },
  getTraktOAuthUrl() {
    return request<{ url: string }>("/trakt/oauth/authorize");
  },
  traktImport() {
    // A full history import walks every play on the account, so this is sized
    // for a decade-old library on a slow connection rather than a routine sync.
    return request<{ imported: Record<string, number> }>("/trakt/import", { method: "POST", timeoutMs: 900000 });
  },
  traktDisconnect() {
    return request<{ disconnected: boolean }>("/trakt/disconnect", { method: "POST" });
  },
  getStremioLibraryStatus() {
    return request<StremioLibraryStatus>("/stremio/library/status");
  },
  stremioLibraryConnect(email: string, password: string) {
    return request<StremioLibraryStatus>("/stremio/library/connect", {
      method: "POST",
      body: JSON.stringify({ email, password })
    });
  },
  stremioLibraryDisconnect() {
    return request<{ connected: boolean }>("/stremio/library/disconnect", { method: "POST" });
  },
  stremioLibraryImport() {
    return request<StremioSyncSummary>("/stremio/library/import", { method: "POST", timeoutMs: 120000 });
  },
  stremioLibrarySync() {
    return request<StremioSyncSummary>("/stremio/library/sync", { method: "POST", timeoutMs: 60000 });
  },
  getStremioPlaySignals() {
    return request<{ enabled: boolean; signals: PlaySignal[] }>("/stremio/play-signals");
  },
};
