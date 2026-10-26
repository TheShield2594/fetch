import type { FastifyPluginAsync } from "fastify";
import { getUpcomingEpisodes } from "../lib/upcoming-episodes.js";
import { resolveProfile } from "../lib/profile.js";
import { getSpoilerProtection } from "../lib/settings.js";
import { ownerForRequest } from "../lib/integration-owner.js";

const calendarRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get<{ Querystring: { days?: string } }>("/calendar", async (request) => {
    const daysAhead = Math.min(Math.max(Number(request.query.days) || 30, 1), 90);
    const spoilerProtection = await getSpoilerProtection(await ownerForRequest(request));
    const calendar = await getUpcomingEpisodes(request.profileId!, daysAhead, 30, spoilerProtection);
    return { calendar };
  });
};

export default calendarRoutes;
