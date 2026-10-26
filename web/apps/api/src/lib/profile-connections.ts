import type { prisma } from "./prisma.js";
import {
  MDBLIST_ACTIVITIES_KEY,
  MDBLIST_DEVICE_KEY,
  PLEX_PIN_KEY,
  SIMKL_LAST_SYNCED_AT_KEY,
  SIMKL_PIN_KEY,
  STREMIO_WATERMARK_KEY,
  TRAKT_HISTORY_BACKFILLED_AT_KEY,
  TRAKT_LAST_POLLED_AT_KEY,
  TRAKT_WATCHLIST_SNAPSHOT_KEY,
} from "./owner-keys.js";

// Trakt, Stremio and the other tracking and media-server connections belong to
// a profile, as they do in the TV app: each profile signs in to its own. A
// connection's row is keyed (and cascades) by the profile's id, and its sync
// state in KV is "<base>:<profileId>".

/** A KV key for one profile's copy of some connection's sync state. */
export const connectionKvKey = (base: string, profileId: string): string => `${base}:${profileId}`;

/** Every connection's sync state kept in KV, named here so a deleted profile's can't be missed. */
export const PROFILE_CONNECTION_KV_KEYS = [
  TRAKT_WATCHLIST_SNAPSHOT_KEY,
  TRAKT_LAST_POLLED_AT_KEY,
  TRAKT_HISTORY_BACKFILLED_AT_KEY,
  STREMIO_WATERMARK_KEY,
  SIMKL_PIN_KEY,
  SIMKL_LAST_SYNCED_AT_KEY,
  MDBLIST_DEVICE_KEY,
  MDBLIST_ACTIVITIES_KEY,
  PLEX_PIN_KEY,
];

type Tx = Pick<typeof prisma, "kV">;

/**
 * Removes deleted profiles' connection sync state. The connections themselves
 * cascade with the profile; KV has no foreign key, so this runs beside the
 * delete.
 */
export const deleteProfileConnectionState = async (tx: Tx, profileIds: string[]): Promise<void> => {
  if (profileIds.length === 0) return;
  await tx.kV.deleteMany({
    where: {
      key: { in: profileIds.flatMap((profileId) => PROFILE_CONNECTION_KV_KEYS.map((key) => connectionKvKey(key, profileId))) },
    },
  });
};
