import type { FastifyPluginAsync } from "fastify";
import { getCalendar } from "../lib/calendar.js";
import { resolveProfile } from "../lib/profile.js";
import { getSpoilerProtection } from "../lib/settings.js";
import { ownerForRequest } from "../lib/integration-owner.js";

const MAX_START_OFFSET_DAYS = 366;

/** A UTC midnight for a real `YYYY-MM-DD` day, or null. */
const parseStart = (value: string): Date | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const [y, m, d] = match.slice(1).map(Number) as [number, number, number];
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? date : null;
};

const calendarRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  /*
   * `days` days from `start` (YYYY-MM-DD, default today). The window is at
   * most 90 days long and starts no more than a year either side of today:
   * the month grid pages back through what has aired as well as forward.
   */
  app.get<{ Querystring: { days?: string; start?: string } }>("/calendar", async (request, reply) => {
    const days = Math.min(Math.max(Number(request.query.days) || 30, 1), 90);
    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);
    let start = today;
    if (request.query.start !== undefined) {
      const parsed = parseStart(request.query.start);
      if (!parsed) return reply.code(400).send({ error: "start must be a YYYY-MM-DD date" });
      const offsetDays = Math.round((parsed.getTime() - today.getTime()) / 86_400_000);
      if (Math.abs(offsetDays) > MAX_START_OFFSET_DAYS) {
        return reply.code(400).send({ error: `start must be within ${MAX_START_OFFSET_DAYS} days of today` });
      }
      start = parsed;
    }
    const spoilerProtection = await getSpoilerProtection(await ownerForRequest(request));
    const calendar = await getCalendar(request.profileId!, { start, days }, spoilerProtection, request.log);
    return { calendar };
  });
};

export default calendarRoutes;
