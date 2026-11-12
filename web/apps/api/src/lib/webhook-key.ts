import { randomBytes } from "node:crypto";
import { hashSecret } from "./request-auth.js";
import { prisma } from "./prisma.js";

/**
 * Marks a media-server webhook key, so the webhook routes can tell one apart
 * from `WEBHOOK_SECRET` when it arrives in the same `token` parameter or
 * `x-webhook-secret` header.
 */
export const WEBHOOK_KEY_PREFIX = "fwh_";

/**
 * Replaces the account's webhook key with a new one and returns it. Only the
 * hash is kept, so this is the one time the key is seen; the old key stops
 * working at once.
 */
export const mintWebhookKey = async (accountId: string): Promise<{ key: string; createdAt: Date }> => {
  const key = `${WEBHOOK_KEY_PREFIX}${randomBytes(32).toString("base64url")}`;
  const createdAt = new Date();
  await prisma.account.update({
    where: { id: accountId },
    // A new key has never been used, whatever the old one had done.
    data: { webhookKeyHash: hashSecret(key), webhookKeyCreatedAt: createdAt, webhookKeyLastUsedAt: null },
  });
  return { key, createdAt };
};

export const revokeWebhookKey = async (accountId: string): Promise<void> => {
  await prisma.account.update({
    where: { id: accountId },
    data: { webhookKeyHash: null, webhookKeyCreatedAt: null, webhookKeyLastUsedAt: null },
  });
};

/**
 * How stale `webhookKeyLastUsedAt` may get before a delivery writes it again.
 * Plex sends an event for every play, pause and resume, and the question the
 * timestamp answers — "is anything arriving?" — doesn't need a write for each.
 */
export const WEBHOOK_USE_STAMP_MS = 60_000;

/**
 * The account a webhook key belongs to, or null for an unknown key. A known
 * key is stamped as used (at most once a minute), so Settings can show when a
 * media server last got through.
 */
export const findWebhookKeyAccount = async (key: string, now = new Date()): Promise<string | null> => {
  const account = await prisma.account.findUnique({ where: { webhookKeyHash: hashSecret(key) }, select: { id: true } });
  if (!account) return null;
  const staleBefore = new Date(now.getTime() - WEBHOOK_USE_STAMP_MS);
  // Conditional, so two deliveries in the same minute write once, and best
  // effort: a failed stamp must never turn away a play that authenticated.
  await Promise.resolve()
    .then(() =>
      prisma.account.updateMany({
        where: { id: account.id, OR: [{ webhookKeyLastUsedAt: null }, { webhookKeyLastUsedAt: { lt: staleBefore } }] },
        data: { webhookKeyLastUsedAt: now },
      })
    )
    .catch(() => undefined);
  return account.id;
};
