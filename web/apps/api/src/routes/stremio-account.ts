import type { FastifyPluginAsync } from "fastify";
import { resolveProfile } from "../lib/profile.js";
import { importTrackerItems } from "../lib/tracker-import.js";
import { importPlaybackProgress } from "../lib/playback.js";
import {
  cancelStremioLink,
  cinemetaVideos,
  createLinkCode,
  fetchLibrary,
  logout,
  pollStremioLink,
  readLinkCode,
  startStremioLink,
  type StremioImportDeps,
} from "../lib/stremio-account.js";

// Importing a Stremio account's history into the requesting profile, as the TV
// does: the page shows Stremio's link code, the person approves it on
// stremio.com, and the page polls until the import has run. The account is read
// once and signed out; nothing is stored.

// Starting asks Stremio for a code and polling asks it again; bounded like the
// other sign-in routes so a stuck page can't hammer Stremio.
const LINK_RATE_LIMIT = { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } };

export const stremioImportDeps: StremioImportDeps = {
  createLinkCode,
  readLinkCode,
  fetchLibrary,
  logout,
  videosFor: cinemetaVideos,
  write: async (profileId, plan, log) => {
    // Plays first, so a resume point for something watched since is recognised and skipped.
    const watched = await importTrackerItems(profileId, plan.watches, [], log);
    const resumePoints = await importPlaybackProgress(profileId, plan.playback);
    return { movies: watched.movies, episodes: watched.episodes, resumePoints };
  },
  now: () => Date.now(),
};

const stremioAccountRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.post("/import/stremio/link", LINK_RATE_LIMIT, async (request, reply) => {
    try {
      return await startStremioLink(request.profileId!, stremioImportDeps);
    } catch (error) {
      request.log.warn({ err: error }, "Stremio link code could not be created");
      return reply.code(502).send({ error: "Could not reach Stremio. Try again in a moment." });
    }
  });

  app.post("/import/stremio/link/poll", LINK_RATE_LIMIT, async (request, reply) => {
    try {
      return await pollStremioLink(request.profileId!, stremioImportDeps, request.log);
    } catch (error) {
      request.log.warn({ err: error }, "Stremio link check failed");
      return reply.code(502).send({ error: "Could not reach Stremio. Try again in a moment." });
    }
  });

  app.post("/import/stremio/link/cancel", async (request) => {
    cancelStremioLink(request.profileId!);
    return { state: "idle" };
  });
};

export default stremioAccountRoutes;
