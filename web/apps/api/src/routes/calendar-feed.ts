import type { FastifyPluginAsync } from "fastify";
import { getCalendar } from "../lib/calendar.js";
import { buildCalendarFeed } from "../lib/ical.js";
import { accountOwnerForProfile } from "../lib/integration-owner.js";
import { prisma } from "../lib/prisma.js";
import { resolveProfile } from "../lib/profile.js";
import { getSpoilerProtection } from "../lib/settings.js";
import { deriveShareKey, isLiveGeneration, nextGeneration, retiredGeneration, shareKeyMatches } from "../lib/share-keys.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

/*
 * A profile's calendar as an iCal feed, for a phone or desktop calendar to
 * subscribe to. A calendar app can't sign in, so the feed's address is its own
 * credential (lib/share-keys.ts) and the feed is off until the profile turns it
 * on. What it carries is what the Calendar page shows: upcoming episodes of the
 * shows being watched and on the watchlist, and watchlisted films' releases.
 */

/** A fortnight back, so an episode that aired last week is still there to see, and three months on. */
const FEED_DAYS_BACK = 14;
const FEED_DAYS = 90;

const feedPath = (profileId: string, generation: number | null): string | null => {
  if (!isLiveGeneration(generation)) return null;
  const key = deriveShareKey("calendar-feed", profileId, generation);
  return key ? `/calendar/feed/${profileId}/${key}/calendar.ics` : null;
};

const calendarFeedRoutes: FastifyPluginAsync = async (app) => {
  // ─── Turning it on and off: the signed-in profile's own ───

  const feedState = async (profileId: string) => {
    const profile = await prisma.profile.findUnique({
      where: { id: profileId },
      select: { calendarFeedGeneration: true },
    });
    const path = feedPath(profileId, profile?.calendarFeedGeneration ?? null);
    return { enabled: path !== null, path };
  };

  app.get("/calendar/feed", { preHandler: resolveProfile }, async (request) => feedState(request.profileId!));

  // Turns the feed on, or replaces its address when it is already on: the old
  // address stops working, which is how a link that got out is taken back.
  app.post("/calendar/feed", { preHandler: resolveProfile }, async (request) => {
    const profileId = request.profileId!;
    const profile = await prisma.profile.findUnique({
      where: { id: profileId },
      select: { calendarFeedGeneration: true },
    });
    await prisma.profile.update({
      where: { id: profileId },
      data: { calendarFeedGeneration: nextGeneration(profile?.calendarFeedGeneration) },
    });
    return feedState(profileId);
  });

  // Off. The number is kept (see `retiredGeneration`), so turning it back on
  // can never bring an old address back to life.
  app.delete("/calendar/feed", { preHandler: resolveProfile }, async (request, reply) => {
    const profileId = request.profileId!;
    const profile = await prisma.profile.findUnique({
      where: { id: profileId },
      select: { calendarFeedGeneration: true },
    });
    if (isLiveGeneration(profile?.calendarFeedGeneration)) {
      await prisma.profile.update({
        where: { id: profileId },
        data: { calendarFeedGeneration: retiredGeneration(profile.calendarFeedGeneration) },
      });
    }
    return reply.code(204).send();
  });

  // ─── The feed itself: public, the key in the path is the credential ───

  app.get<{ Params: { profileId: string; key: string } }>(
    "/calendar/feed/:profileId/:key/calendar.ics",
    { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const { profileId, key } = request.params;
      // One answer for every way of being wrong, so the route says nothing
      // about which profiles exist or have a feed.
      const notFound = () => reply.code(404).send({ error: "Not found" });
      if (!UUID_V4_PATTERN.test(profileId)) return notFound();

      const profile = await prisma.profile.findUnique({
        where: { id: profileId },
        select: { name: true, calendarFeedGeneration: true },
      });
      if (!profile || !shareKeyMatches(key, "calendar-feed", profileId, profile.calendarFeedGeneration)) {
        return notFound();
      }

      const start = new Date();
      start.setUTCHours(0, 0, 0, 0);
      start.setUTCDate(start.getUTCDate() - FEED_DAYS_BACK);
      const owner = await accountOwnerForProfile(profileId);
      const spoilerProtection = await getSpoilerProtection(owner ?? undefined);
      const calendar = await getCalendar(profileId, { start, days: FEED_DAYS }, spoilerProtection, request.log);

      return reply
        .header("Content-Type", "text/calendar; charset=utf-8")
        .header("Content-Disposition", 'inline; filename="fetch.ics"')
        // The address is a credential: nothing between here and the calendar
        // app should keep a copy of what it returned.
        .header("Cache-Control", "private, max-age=900")
        .send(buildCalendarFeed(calendar, { name: `Fetch · ${profile.name}` }));
    }
  );
};

export default calendarFeedRoutes;
