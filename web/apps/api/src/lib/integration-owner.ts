import type { FastifyRequest } from "fastify";
import { accountScope } from "./request-auth.js";
import { findAdminAccountId } from "./sessions.js";
import { prisma } from "./prisma.js";

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

/** Whose connection a profile's activity goes to: the account the profile is in. */
export const ownerForProfile = async (profileId: string): Promise<IntegrationOwner | null> => {
  const profile = await prisma.profile.findUnique({ where: { id: profileId }, select: { accountId: true } });
  if (!profile) return null;
  return profile.accountId ?? LEGACY_OWNER;
};

/**
 * Whose connection a request manages: the signed-in account's. `API_TOKEN`,
 * which has no account, acts as the admin, as it does for webhooks.
 */
export const ownerForRequest = async (request: FastifyRequest): Promise<IntegrationOwner> =>
  accountScope(request) ?? (await findAdminAccountId()) ?? LEGACY_OWNER;

// The per-owner sync state, named here so claiming and deleting an owner's
// state can't miss a key the sync code added.
export const TRAKT_WATCHLIST_SNAPSHOT_KEY = "trakt:watchlistSnapshot";
export const TRAKT_LAST_POLLED_AT_KEY = "trakt:lastPolledAt";
export const TRAKT_HISTORY_BACKFILLED_AT_KEY = "trakt:historyBackfilledAt";
export const STREMIO_WATERMARK_KEY = "stremio:libraryWatermark";

const OWNER_KV_KEYS = [
  TRAKT_WATCHLIST_SNAPSHOT_KEY,
  TRAKT_LAST_POLLED_AT_KEY,
  TRAKT_HISTORY_BACKFILLED_AT_KEY,
  STREMIO_WATERMARK_KEY,
];

type Tx = Pick<typeof prisma, "traktToken" | "stremioAuth" | "kV">;

/**
 * Hands the pre-accounts Trakt and Stremio connections, and their sync state,
 * to the admin account. Run when the admin is created, inside that transaction.
 */
export const claimLegacyIntegrations = async (tx: Tx, adminAccountId: string): Promise<void> => {
  await tx.traktToken.updateMany({ where: { id: LEGACY_OWNER }, data: { id: adminAccountId } });
  await tx.stremioAuth.updateMany({ where: { id: LEGACY_OWNER }, data: { id: adminAccountId } });
  for (const key of OWNER_KV_KEYS) {
    await tx.kV.updateMany({ where: { key }, data: { key: ownerKvKey(key, adminAccountId) } });
  }
};

/** Removes an account's connections and their sync state, when the account goes. */
export const deleteOwnerIntegrations = async (tx: Tx, accountId: string): Promise<void> => {
  await tx.traktToken.deleteMany({ where: { id: accountId } });
  await tx.stremioAuth.deleteMany({ where: { id: accountId } });
  await tx.kV.deleteMany({
    where: { key: { in: OWNER_KV_KEYS.map((key) => ownerKvKey(key, accountId)) } },
  });
};
