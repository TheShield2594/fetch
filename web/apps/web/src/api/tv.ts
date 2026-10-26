/**
 * What a TV syncs per profile: its addons, its Home row order and its
 * settings, plus the read-only overview of all three.
 *
 * Every call names the profile in the path — the profile being arranged, which
 * is the active one on every page that uses these.
 *
 * The two documents (`settings/tv`, `home-layout`) are versioned: a save sends
 * the version it was loaded at as `If-Match`, and a 412 means someone else (a
 * TV, another browser) saved in between. Callers show that as "reload", never
 * retry blindly.
 */

import type { TvSettingsDocument } from "@fetch/shared/tv-settings";
import { request } from "./client";

export type TvAddonCatalog = { type: string; id: string; name: string };

export type TvAddon = {
  id: string;
  manifestUrl: string;
  position: number;
  enabled: boolean;
  /** The manifest's own id; null when the manifest has never been readable. */
  addonId: string | null;
  name: string;
  version: string | null;
  description: string | null;
  logo: string | null;
  types: string[];
  resources: string[];
  catalogs: TvAddonCatalog[];
  configurable: boolean;
  configureUrl: string | null;
  reachable: boolean;
  manifestFetchedAt: string | null;
};

export type HomeRowSource = "addon" | "list" | "collection" | "builtin" | "other";

export type HomeRow = {
  key: string;
  title: string;
  hidden: boolean;
  source: HomeRowSource;
  addonName: string | null;
  type: string | null;
  available: boolean;
};

export type HomeLayout = { rows: HomeRow[]; version: number; updatedAt: string | null };

export type TvSettings = { settings: TvSettingsDocument; version: number; updatedAt: string | null };

export type SyncOverviewProfile = {
  id: string;
  name: string;
  counts: { addons: number; enabledAddons: number; lists: number; resumePoints: number; watchedItems: number };
  tvSettingsUpdatedAt: string | null;
  homeLayoutUpdatedAt: string | null;
};

/** A paired TV and what it said after its last sync. The `report` fields are null until it first reports. */
export type SyncOverviewDevice = {
  id: string;
  name: string;
  lastSeenAt: string | null;
  appVersion: string | null;
  flavor: string | null;
  lastSyncAt: string | null;
  lastSyncOk: boolean | null;
  lastSyncError: string | null;
};

export type SyncOverview = {
  profiles: SyncOverviewProfile[];
  /** Absent when a device asks; the website always gets it. */
  devices?: SyncOverviewDevice[] | undefined;
  server: {
    database: { ok: boolean; latencyMs: number };
    jobs: { job: string; status: "ok" | "failed"; message: string | null; at: string }[];
  };
};

const profilePath = (profileId: string, rest: string) => `/profiles/${encodeURIComponent(profileId)}/${rest}`;

const ifMatch = (version: number) => ({ "If-Match": `"${version}"` });

export const tvApi = {
  getSyncOverview() {
    return request<SyncOverview>("/sync/overview");
  },

  // Addons
  getTvAddons(profileId: string) {
    return request<{ addons: TvAddon[]; updatedAt: string | null }>(profilePath(profileId, "addons"));
  },
  /**
   * `manifest` is the fallback for an addon the server can't reach but the
   * browser can — the website's own addon on a public address, typically.
   */
  addTvAddon(profileId: string, manifestUrl: string, manifest?: unknown) {
    return request<{ addon: TvAddon }>(profilePath(profileId, "addons"), {
      method: "POST",
      body: JSON.stringify(manifest === undefined ? { manifestUrl } : { manifestUrl, manifest }),
    });
  },
  updateTvAddon(profileId: string, addonId: string, patch: { enabled?: boolean; manifestUrl?: string }) {
    return request<{ addon: TvAddon }>(profilePath(profileId, `addons/${encodeURIComponent(addonId)}`), {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  },
  refreshTvAddon(profileId: string, addonId: string) {
    return request<{ addon: TvAddon }>(profilePath(profileId, `addons/${encodeURIComponent(addonId)}/refresh`), {
      method: "POST",
    });
  },
  removeTvAddon(profileId: string, addonId: string) {
    return request<void>(profilePath(profileId, `addons/${encodeURIComponent(addonId)}`), { method: "DELETE" });
  },
  reorderTvAddons(profileId: string, ids: string[]) {
    return request<{ addons: TvAddon[] }>(profilePath(profileId, "addons/order"), {
      method: "PUT",
      body: JSON.stringify({ ids }),
    });
  },

  // Home layout
  getHomeLayout(profileId: string) {
    return request<HomeLayout>(profilePath(profileId, "home-layout"));
  },
  saveHomeLayout(profileId: string, rows: Pick<HomeRow, "key" | "hidden" | "title">[], version: number) {
    return request<HomeLayout>(profilePath(profileId, "home-layout"), {
      method: "PUT",
      headers: ifMatch(version),
      body: JSON.stringify({ rows }),
    });
  },

  // TV settings
  getTvSettings(profileId: string) {
    return request<TvSettings>(profilePath(profileId, "settings/tv"));
  },
  saveTvSettings(profileId: string, settings: TvSettingsDocument, version: number) {
    return request<TvSettings>(profilePath(profileId, "settings/tv"), {
      method: "PUT",
      headers: ifMatch(version),
      body: JSON.stringify({ settings }),
    });
  },
};
