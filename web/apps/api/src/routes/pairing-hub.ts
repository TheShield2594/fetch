import type { FastifyPluginAsync } from "fastify";
import {
  claimHubPairing,
  HUB_POLL_INTERVAL_SEC,
  hubLinkUrl,
  parsePublicAddress,
  pollHubPairing,
  startHubPairing,
} from "../lib/pairing-hub.js";

const PAIRING_SECRET_HEADER = "x-pairing-secret";

/**
 * The pairing hub's routes (see `lib/pairing-hub.ts`), registered only on an
 * install with `FETCH_PAIRING_HUB_SERVE=true`. All three are public: a TV has
 * no credential yet, and the claiming website is another install.
 */
const pairingHubRoutes: FastifyPluginAsync = async (app) => {
  app.post<{ Body: unknown }>(
    "/hub/pair/start",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const body = (request.body ?? {}) as { name?: unknown; handoffHash?: unknown };
      const started = startHubPairing({ name: body.name, handoffHash: body.handoffHash, ip: request.ip });
      if (!started.ok) {
        if (started.reason === "invalid") {
          return reply.code(400).send({ error: "handoffHash must be the SHA-256 of a secret, in hex" });
        }
        return reply.code(429).send({ error: "Too many pairings in progress. Try again in a few minutes." });
      }
      return reply.code(201).send({
        code: started.code,
        linkUrl: hubLinkUrl(started.code),
        pollSecret: started.pollSecret,
        expiresAt: new Date(started.expiresAt).toISOString(),
        intervalSec: HUB_POLL_INTERVAL_SEC,
      });
    }
  );

  app.get("/hub/pair/poll", { config: { rateLimit: { max: 40, timeWindow: "1 minute" } } }, async (request, reply) => {
    const header = request.headers[PAIRING_SECRET_HEADER];
    const secret = (Array.isArray(header) ? header[0] : header)?.trim();
    if (!secret) return reply.code(400).send({ error: `${PAIRING_SECRET_HEADER} header is required` });
    const poll = pollHubPairing(secret);
    if (poll.status === "expired") return reply.code(404).send({ status: "expired", error: "This code has expired." });
    if (poll.status === "pending") {
      return reply.code(202).send({ status: "pending", expiresAt: new Date(poll.expiresAt).toISOString() });
    }
    return { status: "linked", api: poll.link.api, web: poll.link.web };
  });

  app.post<{ Body: unknown }>(
    "/hub/pair/claim",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const body = (request.body ?? {}) as { code?: unknown; api?: unknown; web?: unknown };
      const api = parsePublicAddress(body.api);
      if (typeof body.code !== "string" || !api) {
        return reply.code(400).send({ error: "code and api are required" });
      }
      const claim = claimHubPairing(body.code, { api, web: parsePublicAddress(body.web) });
      if (claim.status === "not_found") {
        return reply.code(404).send({ error: "That code is wrong or has expired. Check the TV for a new one." });
      }
      if (claim.status === "taken") return reply.code(409).send({ error: "That code has already been used." });
      return { handoffHash: claim.handoffHash, name: claim.name };
    }
  );
};

export default pairingHubRoutes;
