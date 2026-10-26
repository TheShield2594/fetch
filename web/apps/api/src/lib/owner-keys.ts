// Whose Trakt or Stremio connection something is. Each account connects its
// own, so a connection's row (`TraktToken.id`, `StremioAuth.id`) and its sync
// state in KV are keyed by the account's id.
//
// An install from before accounts has one connection, stored under "default"
// with KV keys that have no suffix. It stays usable exactly as it was until the
// admin account is created, which takes it over (`claimLegacyIntegrations`).

/** The account id that owns a connection, or LEGACY_OWNER before accounts exist. */
export type IntegrationOwner = string;

export const LEGACY_OWNER: IntegrationOwner = "default";

/** A KV key for one owner's copy of some sync state. */
export const ownerKvKey = (base: string, owner: IntegrationOwner): string =>
  owner === LEGACY_OWNER ? base : `${base}:${owner}`;

/** The account a connection belongs to, or null for the pre-accounts one. */
export const accountOfOwner = (owner: IntegrationOwner): string | null =>
  owner === LEGACY_OWNER ? null : owner;

// The per-owner sync state, named here so claiming and deleting an owner's
// state can't miss a key the sync code added.
export const TRAKT_WATCHLIST_SNAPSHOT_KEY = "trakt:watchlistSnapshot";
export const TRAKT_LAST_POLLED_AT_KEY = "trakt:lastPolledAt";
export const TRAKT_HISTORY_BACKFILLED_AT_KEY = "trakt:historyBackfilledAt";
export const STREMIO_WATERMARK_KEY = "stremio:libraryWatermark";
export const AI_CONFIG_KEY = "ai:config";
export const AI_LAST_RECS_GENERATED_AT_KEY = "ai:lastRecsGeneratedAt";
