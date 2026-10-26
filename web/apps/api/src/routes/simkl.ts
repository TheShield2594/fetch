import type { FastifyPluginAsync } from "fastify";
import { resolveProfile } from "../lib/profile.js";
import {
  disconnectSimkl,
  getSimklStatus,
  isSimklConfigured,
  pollSimklPin,
  SimklError,
  startSimklPin,
  syncSimkl,
} from "../lib/simkl.js";

// Each profile connects its own Simkl account, as in the TV app: every route
// acts on the requesting profile's. Signing in is Simkl's PIN flow, the one the
// TV uses: the page shows a code to enter at simkl.com/pin and polls until it
// has been.

// Starting a sign-in asks Simkl for a code, and polling asks it again; bounded
// like the Trakt OAuth routes so a stuck page can't hammer Simkl.
const SIGN_IN_RATE_LIMIT = { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } };

const simklRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/simkl/status", async (request) => getSimklStatus(request.profileId!));

  app.post("/simkl/pin", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    if (!isSimklConfigured()) {
      return reply.code(409).send({ error: "Simkl is not set up on this server (SIMKL_CLIENT_ID)", code: "simkl_not_configured" });
    }
    try {
      return await startSimklPin(request.profileId!);
    } catch (error) {
      request.log.warn({ err: error }, "Simkl sign-in could not start");
      return reply.code(502).send({ error: "Could not reach Simkl. Try again in a moment." });
    }
  });

  app.post("/simkl/pin/poll", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    try {
      const result = await pollSimklPin(request.profileId!);
      return { result, status: await getSimklStatus(request.profileId!) };
    } catch (error) {
      request.log.warn({ err: error }, "Simkl sign-in check failed");
      return reply.code(502).send({ error: "Could not reach Simkl. Try again in a moment." });
    }
  });

  // `{ "full": true }` reads the whole library again rather than what changed.
  app.post("/simkl/sync", async (request, reply) => {
    const { full } = (request.body ?? {}) as { full?: unknown };
    try {
      return await syncSimkl(request.profileId!, request.log, { full: full === true });
    } catch (error) {
      request.log.error({ err: error }, "Simkl sync failed");
      const status = error instanceof SimklError && error.status === 401 ? 401 : 502;
      return reply
        .code(status)
        .send({ error: status === 401 ? "Simkl rejected the sign-in. Connect Simkl again." : "Simkl sync failed" });
    }
  });

  app.post("/simkl/disconnect", async (request) => {
    await disconnectSimkl(request.profileId!);
    return { connected: false };
  });
};

export default simklRoutes;
