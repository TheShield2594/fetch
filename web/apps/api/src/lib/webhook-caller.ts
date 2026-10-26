import type { FastifyRequest } from "fastify";
import { verifyWebhookSecret } from "./webhook-auth.js";
import { findWebhookKeyAccount, WEBHOOK_KEY_PREFIX } from "./webhook-key.js";
import { findAdminAccountId } from "./sessions.js";

/**
 * Who a webhook is from. `accountId` limits which profiles its plays can land
 * in; null only for `WEBHOOK_SECRET` on an install with no accounts yet, where
 * every profile is the operator's.
 */
export type WebhookCaller = { accountId: string | null };

const presentedCredential = (request: FastifyRequest): string | null => {
  const header = request.headers["x-webhook-secret"];
  if (typeof header === "string" && header.length > 0) return header;
  const token = (request.query as Record<string, unknown> | undefined)?.token;
  return typeof token === "string" && token.length > 0 ? token : null;
};

/**
 * Authenticates a Plex/Jellyfin/Emby webhook, or returns null.
 *
 * An account's own webhook key (`fwh_…`, made under Settings) arrives in the
 * same `token` parameter or `x-webhook-secret` header as `WEBHOOK_SECRET`, and
 * ties the webhook to that account. `WEBHOOK_ALLOWED_IPS` does not apply to it:
 * a friend's media server is on their network, not yours, and the key is the
 * credential.
 *
 * `WEBHOOK_SECRET` is the operator's, set in `.env`, so it acts for the admin
 * account: it can't put plays into another account's profiles.
 */
export const authenticateWebhook = async (request: FastifyRequest): Promise<WebhookCaller | null> => {
  const credential = presentedCredential(request);
  if (credential?.startsWith(WEBHOOK_KEY_PREFIX)) {
    const accountId = await findWebhookKeyAccount(credential);
    return accountId ? { accountId } : null;
  }
  if (!verifyWebhookSecret(request)) return null;
  return { accountId: await findAdminAccountId() };
};
