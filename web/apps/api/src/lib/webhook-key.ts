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
    data: { webhookKeyHash: hashSecret(key), webhookKeyCreatedAt: createdAt },
  });
  return { key, createdAt };
};

export const revokeWebhookKey = async (accountId: string): Promise<void> => {
  await prisma.account.update({
    where: { id: accountId },
    data: { webhookKeyHash: null, webhookKeyCreatedAt: null },
  });
};

/** The account a webhook key belongs to, or null for an unknown key. */
export const findWebhookKeyAccount = async (key: string): Promise<string | null> => {
  const account = await prisma.account.findUnique({ where: { webhookKeyHash: hashSecret(key) }, select: { id: true } });
  return account?.id ?? null;
};
