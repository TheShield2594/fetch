import type { FastifyPluginAsync } from "fastify";
import { resolveProfile } from "../lib/profile.js";
import {
  disconnectMdbList,
  getMdbListStatus,
  isMdbListConfigured,
  MdbListError,
  pollMdbListDevice,
  startMdbListDevice,
  syncMdbList,
} from "../lib/mdblist.js";

// Each profile connects its own MDBList account, as in the TV app: every route
// acts on the requesting profile's. Signing in is MDBList's OAuth device flow,
// the one the TV uses: a code to enter on mdblist.com, polled until approved.

const SIGN_IN_RATE_LIMIT = { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } };

const mdbListRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/mdblist/status", async (request) => getMdbListStatus(request.profileId!));

  app.post("/mdblist/device", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    if (!isMdbListConfigured()) {
      return reply
        .code(409)
        .send({ error: "MDBList is not set up on this server (MDBLIST_CLIENT_ID)", code: "mdblist_not_configured" });
    }
    try {
      return await startMdbListDevice(request.profileId!);
    } catch (error) {
      request.log.warn({ err: error }, "MDBList sign-in could not start");
      return reply.code(502).send({ error: "Could not reach MDBList. Try again in a moment." });
    }
  });

  app.post("/mdblist/device/poll", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    try {
      const result = await pollMdbListDevice(request.profileId!);
      return { result, status: await getMdbListStatus(request.profileId!) };
    } catch (error) {
      request.log.warn({ err: error }, "MDBList sign-in check failed");
      return reply.code(502).send({ error: "Could not reach MDBList. Try again in a moment." });
    }
  });

  // `{ "full": true }` reads everything even when MDBList reports no change.
  app.post("/mdblist/sync", async (request, reply) => {
    const { full } = (request.body ?? {}) as { full?: unknown };
    try {
      return await syncMdbList(request.profileId!, request.log, { full: full === true });
    } catch (error) {
      request.log.error({ err: error }, "MDBList sync failed");
      const revoked = error instanceof MdbListError && (error.status === 401 || error.code === "invalid_grant");
      return reply
        .code(revoked ? 401 : 502)
        .send({ error: revoked ? "MDBList rejected the sign-in. Connect MDBList again." : "MDBList sync failed" });
    }
  });

  app.post("/mdblist/disconnect", async (request) => {
    await disconnectMdbList(request.profileId!);
    return { connected: false };
  });
};

export default mdbListRoutes;
