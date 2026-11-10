import type { FastifyReply, FastifyRequest } from "fastify";
import { authenticateWebhook, type WebhookCaller } from "./webhook-caller.js";

/**
 * Body limits for the media-server webhooks, well under the app-wide
 * `MAX_BODY_SIZE_MB` (sized for library backups, 32 MB by default).
 *
 * Plex posts `multipart/form-data` whose `payload` part is a few KB of JSON,
 * but some events (play, rate, library.new) also carry a `thumb` part — the
 * poster as a JPEG, usually a few hundred KB. The route reads the whole body as
 * a string to find the payload part, so the limit has to clear the thumbnail.
 * Jellyfin and Emby post plain JSON a few KB long; Emby's carries the whole
 * item, which is still nowhere near 1 MB.
 */
export const PLEX_WEBHOOK_BODY_LIMIT = 5 * 1024 * 1024;
export const JSON_WEBHOOK_BODY_LIMIT = 1024 * 1024;

const callers = new WeakMap<FastifyRequest, WebhookCaller>();

/**
 * Route-level `preParsing` hook that authenticates a webhook before its body is
 * read off the wire.
 *
 * `/webhooks/*` is public to the global auth gate (the media servers can't send
 * a bearer token), so without this the body was buffered and parsed — up to
 * the route's `bodyLimit` — before the handler checked the secret, letting an
 * unauthenticated caller make the server do that work for free. The credential
 * is in the header or query string, so nothing here needs the body.
 *
 * `preParsing` rather than `onRequest` for the same reason as `auth-gate.ts`:
 * `@fastify/rate-limit`'s hook is route-level `onRequest`, and an earlier phase
 * would turn floods away before the limiter counted them.
 */
export const requireWebhookCaller = async (request: FastifyRequest, reply: FastifyReply): Promise<void> => {
  const caller = await authenticateWebhook(request);
  if (!caller) {
    // Not returned: Fastify expects a payload stream from `preParsing`, and
    // stops the lifecycle on its own once the reply is sent.
    await reply.code(403).send({ error: "Invalid webhook secret" });
    return;
  }
  callers.set(request, caller);
};

/** The caller `requireWebhookCaller` authenticated. Throws if the hook didn't run. */
export const webhookCallerOf = (request: FastifyRequest): WebhookCaller => {
  const caller = callers.get(request);
  if (!caller) throw new Error("webhook route is missing the requireWebhookCaller preParsing hook");
  return caller;
};
