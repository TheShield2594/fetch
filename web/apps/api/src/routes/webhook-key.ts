import type { FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import { prisma } from "../lib/prisma.js";
import { mintWebhookKey, revokeWebhookKey } from "../lib/webhook-key.js";

// Each account's own key for the Plex/Jellyfin/Emby webhooks. It goes in the
// webhook URL in place of WEBHOOK_SECRET, and puts plays in that account's
// profiles only. Kept as a hash, so the key is shown once, when it is made.

const signedInAccount = (request: FastifyRequest, reply: FastifyReply): string | null => {
  if (request.auth?.kind === "session") return request.auth.accountId;
  void reply.code(400).send({ error: "Sign in to the website to manage your webhook key" });
  return null;
};

const webhookKeyRoutes: FastifyPluginAsync = async (app) => {
  app.get("/auth/webhook-key", async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;
    const account = await prisma.account.findUnique({
      where: { id: accountId },
      select: { webhookKeyHash: true, webhookKeyCreatedAt: true },
    });
    return { configured: Boolean(account?.webhookKeyHash), createdAt: account?.webhookKeyCreatedAt ?? null };
  });

  // Makes a key, or replaces the one there is: the old one stops working.
  app.post("/auth/webhook-key", async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;
    return reply.code(201).send(await mintWebhookKey(accountId));
  });

  app.delete("/auth/webhook-key", async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;
    await revokeWebhookKey(accountId);
    return reply.code(204).send();
  });
};

export default webhookKeyRoutes;
