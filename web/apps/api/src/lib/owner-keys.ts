// Whose account-level settings something is: the AI provider, its
// recommendation watermark, streaming region and spoiler protection. Each
// account has its own, kept in KV as "<base>:<accountId>". (Trakt, Stremio and
// the other connections are per profile instead: lib/profile-connections.ts.)
//
// An install from before accounts kept one copy with no suffix, the
// "default" owner below, until the admin account is created.

/** The account id that owns a setting, or LEGACY_OWNER before accounts exist. */
export type IntegrationOwner = string;

export const LEGACY_OWNER: IntegrationOwner = "default";

/** A KV key for one owner's copy of a setting. */
export const ownerKvKey = (base: string, owner: IntegrationOwner): string =>
  owner === LEGACY_OWNER ? base : `${base}:${owner}`;

/** The account a setting belongs to, or null for the pre-accounts one. */
export const accountOfOwner = (owner: IntegrationOwner): string | null =>
  owner === LEGACY_OWNER ? null : owner;

// Connection sync state, keyed per profile (lib/profile-connections.ts).
export const TRAKT_WATCHLIST_SNAPSHOT_KEY = "trakt:watchlistSnapshot";
export const TRAKT_LAST_POLLED_AT_KEY = "trakt:lastPolledAt";
export const TRAKT_HISTORY_BACKFILLED_AT_KEY = "trakt:historyBackfilledAt";
export const STREMIO_WATERMARK_KEY = "stremio:libraryWatermark";
export const SIMKL_PIN_KEY = "simkl:pin";
export const SIMKL_LAST_SYNCED_AT_KEY = "simkl:lastSyncedAt";

// Per-account settings (above).
export const AI_CONFIG_KEY = "ai:config";
export const AI_LAST_RECS_GENERATED_AT_KEY = "ai:lastRecsGeneratedAt";
