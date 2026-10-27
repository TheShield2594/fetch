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
import {
  getMdbListKeyStatus,
  getMdbListRatings,
  MdbListKeyRejected,
  removeMdbListKey,
  saveMdbListKey,
} from "../lib/mdblist-ratings.js";

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

  // ─── Ratings ───
  //
  // A pasted API key, as the TV takes one, for the ratings on detail pages.
  // Without one the profile's MDBList sign-in above is used instead.

  app.get("/mdblist/key", async (request) => getMdbListKeyStatus(request.profileId!));

  app.put("/mdblist/key", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    const { apiKey } = (request.body ?? {}) as { apiKey?: unknown };
    if (typeof apiKey !== "string" || !apiKey.trim()) {
      return reply.code(400).send({ error: "apiKey is required" });
    }
    try {
      await saveMdbListKey(request.profileId!, apiKey);
    } catch (error) {
      if (error instanceof MdbListKeyRejected) return reply.code(400).send({ error: error.message, code: "mdblist_key_rejected" });
      request.log.warn({ err: error }, "MDBList API key could not be checked");
      return reply.code(502).send({ error: "Could not reach MDBList to check the key. Try again in a moment." });
    }
    return getMdbListKeyStatus(request.profileId!);
  });

  app.delete("/mdblist/key", async (request) => {
    await removeMdbListKey(request.profileId!);
    return getMdbListKeyStatus(request.profileId!);
  });

  app.get<{ Params: { type: string; imdbId: string } }>("/mdblist/ratings/:type/:imdbId", async (request, reply) => {
    const { type, imdbId } = request.params;
    if ((type !== "movie" && type !== "series") || !/^tt\d{1,10}$/.test(imdbId)) {
      return reply.code(400).send({ error: "Expected /mdblist/ratings/movie|series/tt…" });
    }
    try {
      return { ratings: await getMdbListRatings(request.profileId!, type, imdbId, request.log) };
    } catch (error) {
      // The page falls back to its own scores, so an outage costs nothing but these.
      request.log.warn({ err: error }, "MDBList ratings unavailable");
      return { ratings: [] };
    }
  });
};

export default mdbListRoutes;
