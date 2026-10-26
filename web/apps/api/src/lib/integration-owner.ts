import type { FastifyRequest } from "fastify";
import { accountScope } from "./request-auth.js";
import { findAdminAccountId } from "./sessions.js";
import { prisma } from "./prisma.js";
import { type OutboundScope, PRIVATE_NETWORK_ALLOWED, PUBLIC_ONLY } from "./ssrf.js";
import { deleteSecretKv, readSecretKv, writeSecretKv } from "./secret-store.js";
import { invalidateKv, readKv } from "./kv.js";
import {
  accountOfOwner,
  AI_CONFIG_KEY,
  AI_LAST_RECS_GENERATED_AT_KEY,
  type IntegrationOwner,
  LEGACY_OWNER,
  ownerKvKey,
  STREMIO_WATERMARK_KEY,
  TRAKT_HISTORY_BACKFILLED_AT_KEY,
  TRAKT_LAST_POLLED_AT_KEY,
  TRAKT_WATCHLIST_SNAPSHOT_KEY,
} from "./owner-keys.js";

// The database half of lib/owner-keys.ts: who owns what, and moving and
// removing an owner's state. Re-exports the pure half, so callers import one
// module.
export * from "./owner-keys.js";

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

/**
 * Where an owner's own outbound integrations (its AI provider, its profiles'
 * notification channels) may point. Only the admin's, and the pre-accounts
 * install's, may reach the private network the server is on.
 */
export const outboundScopeForOwner = async (owner: IntegrationOwner): Promise<OutboundScope> => {
  const accountId = accountOfOwner(owner);
  if (accountId === null) return PRIVATE_NETWORK_ALLOWED;
  const account = await prisma.account.findUnique({ where: { id: accountId }, select: { isAdmin: true } });
  return account?.isAdmin ? PRIVATE_NETWORK_ALLOWED : PUBLIC_ONLY;
};

/** The outbound scope for a profile's integrations: its account's. */
export const outboundScopeForProfile = async (profileId: string): Promise<OutboundScope> => {
  const owner = await ownerForProfile(profileId);
  return owner ? outboundScopeForOwner(owner) : PUBLIC_ONLY;
};

// Plain values, moved to the admin's key as they are. The AI config is a
// credential, encrypted under its own key's name, so it is re-encrypted rather
// than renamed (`adoptLegacyAiConfig`).
const CLAIMABLE_KV_KEYS = [
  TRAKT_WATCHLIST_SNAPSHOT_KEY,
  TRAKT_LAST_POLLED_AT_KEY,
  TRAKT_HISTORY_BACKFILLED_AT_KEY,
  STREMIO_WATERMARK_KEY,
  AI_LAST_RECS_GENERATED_AT_KEY,
];

// Region and spoiler protection, per account (lib/settings.ts). Not claimed:
// the unsuffixed values stay as the default every account starts from.
const ACCOUNT_PREFERENCE_KV_KEYS = ["settings:region", "settings:spoilerProtection"];

const OWNER_KV_KEYS = [...CLAIMABLE_KV_KEYS, AI_CONFIG_KEY, ...ACCOUNT_PREFERENCE_KV_KEYS];

type Tx = Pick<typeof prisma, "traktToken" | "stremioAuth" | "kV">;

/**
 * Hands the pre-accounts Trakt and Stremio connections, and their sync state,
 * to the admin account. Run when the admin is created, inside that transaction.
 */
export const claimLegacyIntegrations = async (tx: Tx, adminAccountId: string): Promise<void> => {
  await tx.traktToken.updateMany({ where: { id: LEGACY_OWNER }, data: { id: adminAccountId } });
  await tx.stremioAuth.updateMany({ where: { id: LEGACY_OWNER }, data: { id: adminAccountId } });
  for (const key of CLAIMABLE_KV_KEYS) {
    await tx.kV.updateMany({ where: { key }, data: { key: ownerKvKey(key, adminAccountId) } });
  }
};

/**
 * Moves the pre-accounts AI provider config to the admin account, once there
 * is one. Run after the admin is created and at startup, so an install whose
 * admin predates per-account AI picks it up too. Leaves the old row alone when
 * it can't be read (the startup secret sweep reports that).
 */
export const adoptLegacyAiConfig = async (): Promise<void> => {
  const adminId = await findAdminAccountId();
  if (!adminId) return;
  const legacy = await readSecretKv(AI_CONFIG_KEY);
  if (legacy === null) return;
  const target = ownerKvKey(AI_CONFIG_KEY, adminId);
  if ((await readKv(target)) === null) await writeSecretKv(target, legacy);
  await deleteSecretKv(AI_CONFIG_KEY);
};

/** Removes an account's connections and their sync state, when the account goes. */
export const deleteOwnerIntegrations = async (tx: Tx, accountId: string): Promise<void> => {
  await tx.traktToken.deleteMany({ where: { id: accountId } });
  await tx.stremioAuth.deleteMany({ where: { id: accountId } });
  await tx.kV.deleteMany({
    where: { key: { in: OWNER_KV_KEYS.map((key) => ownerKvKey(key, accountId)) } },
  });
  // These are read through the KV cache.
  for (const key of [AI_CONFIG_KEY, ...ACCOUNT_PREFERENCE_KV_KEYS]) invalidateKv(ownerKvKey(key, accountId));
};
