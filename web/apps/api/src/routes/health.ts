import type { FastifyPluginAsync } from "fastify";
import { checkDatabase } from "../lib/db-health.js";
import { SERVER_VERSION, versionInfo } from "../lib/version.js";

// Two probes, because they answer two different questions and a restart is only
// the right response to one of them.
//
// `/health` is liveness: is this process up and serving? It deliberately
// depends on nothing, so it keeps answering 200 during a database outage — a
// probe that fails when Postgres is down would have Docker restart a perfectly
// healthy API, repeatedly, for a fault it cannot fix.
//
// `/health/ready` is readiness: can this process actually do its job right now?
// That means the database, so it runs `SELECT 1` and answers 503 when it can't.
// This is what docker-compose waits on before starting the services that depend
// on the API, and what a proxy should drain on.
//
// Both are reachable without the API token (see the auth hook in index.ts):
// probes carry no credentials. So the failure body says only that the database
// is not answering — the error itself goes to the log and to the token-guarded
// `/metrics`.
//
// `/version` is the compatibility handshake (#639): the server's version, the
// API version the TV checks against, the oldest TV app this server serves, and
// the optional features it has. Public, like the probes, so a TV can ask before
// it has paired.
const healthRoutes: FastifyPluginAsync = async (app) => {
  app.get("/health", async () => ({ status: "ok", service: "api", version: SERVER_VERSION }));

  app.get("/version", async (_request, reply) => {
    reply.header("Cache-Control", "no-store");
    return versionInfo();
  });

  app.get("/health/ready", async (request, reply) => {
    const database = await checkDatabase();

    // A cached readiness answer is a wrong readiness answer.
    reply.header("Cache-Control", "no-store");

    if (!database.ok) {
      request.log.error(
        { reason: database.error, latencyMs: database.latencyMs },
        "Readiness probe failed: the database did not answer"
      );
      return reply
        .code(503)
        .send({ status: "error", service: "api", database: { ok: false, latencyMs: database.latencyMs } });
    }

    return reply.send({ status: "ok", service: "api", database: { ok: true, latencyMs: database.latencyMs } });
  });
};

export default healthRoutes;
