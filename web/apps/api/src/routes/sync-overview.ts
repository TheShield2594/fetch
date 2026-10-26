import type { FastifyPluginAsync } from "fastify";
import { prisma } from "../lib/prisma.js";
import { checkDatabase } from "../lib/db-health.js";
import { getJobRuns } from "../lib/job-status.js";

/**
 * The read-only account dashboard: what each profile has that a TV syncs, and
 * whether this server is healthy enough to sync it.
 *
 * Counts only — nothing here is a title, a URL or a setting — so every profile
 * is listed, locked ones included, the same way `GET /profiles` lists their
 * names. Health reuses the readiness probe and the job-status rows the
 * Settings page already shows.
 */

type CountRow = { profileId: string; _count: { _all: number } };

const countsByProfile = (rows: CountRow[]): Map<string, number> =>
  new Map(rows.map((row) => [row.profileId, row._count._all]));

const syncOverviewRoutes: FastifyPluginAsync = async (app) => {
  app.get("/sync/overview", async (_request, reply) => {
    const [profiles, addons, enabledAddons, lists, resumePoints, watched, documents, database, jobs] =
      await Promise.all([
        prisma.profile.findMany({ orderBy: { createdAt: "asc" }, select: { id: true, name: true } }),
        prisma.profileAddon.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.profileAddon.groupBy({ by: ["profileId"], where: { enabled: true }, _count: { _all: true } }),
        prisma.list.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.playbackProgress.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.watchEvent.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.profileDocument.findMany({ select: { profileId: true, kind: true, updatedAt: true } }),
        checkDatabase(),
        getJobRuns(),
      ]);

    const addonCounts = countsByProfile(addons);
    const enabledAddonCounts = countsByProfile(enabledAddons);
    const listCounts = countsByProfile(lists);
    const resumeCounts = countsByProfile(resumePoints);
    const watchedCounts = countsByProfile(watched);
    const documentUpdatedAt = (profileId: string, kind: string): string | null =>
      documents.find((doc) => doc.profileId === profileId && doc.kind === kind)?.updatedAt.toISOString() ?? null;

    reply.header("Cache-Control", "no-store");
    return {
      profiles: profiles.map((profile) => ({
        id: profile.id,
        name: profile.name,
        counts: {
          addons: addonCounts.get(profile.id) ?? 0,
          enabledAddons: enabledAddonCounts.get(profile.id) ?? 0,
          lists: listCounts.get(profile.id) ?? 0,
          resumePoints: resumeCounts.get(profile.id) ?? 0,
          watchedItems: watchedCounts.get(profile.id) ?? 0,
        },
        tvSettingsUpdatedAt: documentUpdatedAt(profile.id, "tv-settings"),
        homeLayoutUpdatedAt: documentUpdatedAt(profile.id, "home-layout"),
      })),
      server: {
        database: { ok: database.ok, latencyMs: database.latencyMs },
        jobs: jobs.map((run) => ({
          job: run.job,
          status: run.status,
          message: run.message,
          at: run.at,
        })),
      },
    };
  });
};

export default syncOverviewRoutes;
