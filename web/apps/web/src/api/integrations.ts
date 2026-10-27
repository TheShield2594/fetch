/**
 * The services that feed watch history in: Trakt, Simkl, MDBList and Stremio. Each is
 * the active profile's own connection.
 */

import type { MdbListRatingSource } from "@fetch/shared/tv-settings";
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

export type MdbListPending = {
  userCode: string;
  verificationUrl: string;
  verificationUrlComplete: string;
  expiresAt: string;
  interval: number;
};

export type MdbListStatus = Omit<SimklStatus, "pending"> & { pending: MdbListPending | null };

/** The profile's MDBList API key for ratings, and whether its MDBList sign-in can stand in for one. */
export type MdbListKeyStatus = { hasKey: boolean; signedIn: boolean };

export type MdbListRating = { source: MdbListRatingSource; value: number; certified: boolean };

export type TrackerSyncSummary = { movies: number; episodes: number; watchlist: number; skipped: number; full: boolean };

export type MediaServer = {
  id: string;
  provider: "plex" | "jellyfin" | "emby";
  serverName: string;
  address: string;
  userName: string;
  importWatchState: boolean;
  lastSyncedAt: string | null;
  connectedAt: string;
};

export type PlexLink = { code: string; linkUrl: string; expiresAt: string; interval: number };

export type MediaServerSyncSummary = { movies: number; episodes: number; resumePoints: number; skipped: number };

export const integrationsApi = {
  getMediaServers() {
    return request<{
      providers: { id: MediaServer["provider"]; name: string; signIn: "link" | "password" }[];
      servers: MediaServer[];
      plexLink: PlexLink | null;
    }>("/media-servers");
  },
  startPlexLink() {
    return request<PlexLink>("/media-servers/plex/link", { method: "POST" });
  },
  pollPlexLink() {
    return request<{ result: "pending" | "connected" | "expired" | "none"; servers: MediaServer[] }>(
      "/media-servers/plex/link/poll",
      { method: "POST", timeoutMs: 60000 }
    );
  },
  signInMediaServer(provider: "jellyfin" | "emby", address: string, username: string, password: string) {
    return request<{ serverName: string; userName: string }>(`/media-servers/${provider}/sign-in`, {
      method: "POST",
      body: JSON.stringify({ address, username, password }),
      timeoutMs: 60000,
    });
  },
  setMediaServerImport(id: string, importWatchState: boolean) {
    return request<MediaServer>(`/media-servers/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ importWatchState }),
    });
  },
  syncMediaServer(id: string) {
    return request<MediaServerSyncSummary>(`/media-servers/${encodeURIComponent(id)}/sync`, {
      method: "POST",
      timeoutMs: 600000,
    });
  },
  removeMediaServer(id: string) {
    return request<void>(`/media-servers/${encodeURIComponent(id)}`, { method: "DELETE" });
  },
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
  getMdbListStatus() {
    return request<MdbListStatus>("/mdblist/status");
  },
  startMdbListDevice() {
    return request<MdbListPending>("/mdblist/device", { method: "POST" });
  },
  pollMdbListDevice() {
    return request<{ result: "pending" | "connected" | "expired" | "denied" | "none"; status: MdbListStatus }>(
      "/mdblist/device/poll",
      { method: "POST" }
    );
  },
  syncMdbList(full = false) {
    return request<TrackerSyncSummary & { unchanged: boolean }>("/mdblist/sync", {
      method: "POST",
      body: JSON.stringify({ full }),
      timeoutMs: 300000,
    });
  },
  disconnectMdbList() {
    return request<{ connected: boolean }>("/mdblist/disconnect", { method: "POST" });
  },
  getMdbListKeyStatus() {
    return request<MdbListKeyStatus>("/mdblist/key");
  },
  setMdbListKey(apiKey: string) {
    return request<MdbListKeyStatus>("/mdblist/key", { method: "PUT", body: JSON.stringify({ apiKey }) });
  },
  removeMdbListKey() {
    return request<MdbListKeyStatus>("/mdblist/key", { method: "DELETE" });
  },
  /** Empty when the profile has MDBList ratings off, or nothing to read them with. */
  getMdbListRatings(type: "movie" | "series", imdbId: string) {
    return request<{ ratings: MdbListRating[] }>(
      `/mdblist/ratings/${encodeURIComponent(type)}/${encodeURIComponent(imdbId)}`
    );
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
