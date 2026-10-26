import type { FastifyPluginAsync } from "fastify";
import { NON_BLANK } from "../lib/request-schema.js";
import { prisma } from "../lib/prisma.js";
import { checkDatabase } from "../lib/db-health.js";
import { getJobRuns } from "../lib/job-status.js";
import { accountScope, isAdminRequest } from "../lib/request-auth.js";

/**
 * The read-only account dashboard: what each profile has that a TV syncs, and
 * whether this server is healthy enough to sync it.
 *
 * Counts only — nothing here is a title, a URL or a setting — so every profile
 * is listed, locked ones included, the same way `GET /profiles` lists their
 * names. Health reuses the readiness probe and the job-status rows the
 * Settings page already shows.
 *
 * Paired devices are listed with what each reported after its last sync
 * (`PUT /devices/me/report`), so a TV whose sync keeps failing shows up here
 * with the error it hit. That list is left out for a device caller: managing
 * devices is the website's business, as it is under `/devices`.
 */

/** Long enough for any exception message worth reading; the rest is noise. */
const MAX_SYNC_ERROR_LENGTH = 500;

const reportBodySchema = {
  type: "object",
  required: ["ok"],
  properties: {
    appVersion: { type: "string", pattern: NON_BLANK, maxLength: 64 },
    flavor: { type: "string", pattern: NON_BLANK, maxLength: 32 },
    ok: { type: "boolean" },
    error: { type: "string", maxLength: 4_000 },
  },
} as const;

type ReportBody = { appVersion?: string; flavor?: string; ok: boolean; error?: string };

type CountRow = { profileId: string; _count: { _all: number } };

const countsByProfile = (rows: CountRow[]): Map<string, number> =>
  new Map(rows.map((row) => [row.profileId, row._count._all]));

const syncOverviewRoutes: FastifyPluginAsync = async (app) => {
  app.get("/sync/overview", async (request, reply) => {
    reply.header("Cache-Control", "no-store");

    // Asked first and on its own: with the database down every count below
    // would fail, and the one thing the dashboard can still say — that the
    // database is the problem — would be lost in a 500.
    const database = await checkDatabase();
    if (!database.ok) {
      return {
        profiles: [],
        ...(request.deviceId ? {} : { devices: [] }),
        server: { database: { ok: false, latencyMs: database.latencyMs }, jobs: [] },
      };
    }

    // The caller's own account. Counts below are grouped over every profile,
    // and only the rows for these ones are read back out.
    const scope = accountScope(request);
    const [profiles, addons, enabledAddons, lists, resumePoints, watched, documents, jobs] =
      await Promise.all([
        prisma.profile.findMany({
          where: scope ? { accountId: scope } : {},
          orderBy: { createdAt: "asc" },
          select: { id: true, name: true, usesPrimaryAddons: true },
        }),
        prisma.profileAddon.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.profileAddon.groupBy({ by: ["profileId"], where: { enabled: true }, _count: { _all: true } }),
        prisma.list.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.playbackProgress.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.watchEvent.groupBy({ by: ["profileId"], _count: { _all: true } }),
        prisma.profileDocument.findMany({ select: { profileId: true, kind: true, updatedAt: true } }),
        // The scheduled jobs are the install's, and their errors are the admin's to see.
        isAdminRequest(request) ? getJobRuns() : Promise.resolve([]),
      ]);
    const devices = request.deviceId
      ? null
      : await prisma.device.findMany({
          where: scope ? { accountId: scope } : {},
          orderBy: { createdAt: "asc" },
          select: {
            id: true,
            name: true,
            lastSeenAt: true,
            appVersion: true,
            flavor: true,
            lastSyncAt: true,
            lastSyncOk: true,
            lastSyncError: true,
          },
        });

    const addonCounts = countsByProfile(addons);
    const enabledAddonCounts = countsByProfile(enabledAddons);
    const listCounts = countsByProfile(lists);
    const resumeCounts = countsByProfile(resumePoints);
    const watchedCounts = countsByProfile(watched);
    const documentUpdatedAt = (profileId: string, kind: string): string | null =>
      documents.find((doc) => doc.profileId === profileId && doc.kind === kind)?.updatedAt.toISOString() ?? null;

    // The primary profile is the oldest, as it is slot 1 on a TV. A profile set
    // to use its addons runs the primary's list on the TV, so that is the count
    // that describes it.
    const primaryId = profiles[0]?.id;
    const addonSourceFor = (profile: { id: string; usesPrimaryAddons: boolean }): string =>
      profile.usesPrimaryAddons && primaryId ? primaryId : profile.id;

    return {
      profiles: profiles.map((profile) => ({
        id: profile.id,
        name: profile.name,
        addonsFromPrimary: profile.usesPrimaryAddons && profile.id !== primaryId,
        counts: {
          addons: addonCounts.get(addonSourceFor(profile)) ?? 0,
          enabledAddons: enabledAddonCounts.get(addonSourceFor(profile)) ?? 0,
          lists: listCounts.get(profile.id) ?? 0,
          resumePoints: resumeCounts.get(profile.id) ?? 0,
          watchedItems: watchedCounts.get(profile.id) ?? 0,
        },
        tvSettingsUpdatedAt: documentUpdatedAt(profile.id, "tv-settings"),
        homeLayoutUpdatedAt: documentUpdatedAt(profile.id, "home-layout"),
      })),
      ...(devices
        ? {
            devices: devices.map((device) => ({
              id: device.id,
              name: device.name,
              lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
              appVersion: device.appVersion,
              flavor: device.flavor,
              lastSyncAt: device.lastSyncAt?.toISOString() ?? null,
              lastSyncOk: device.lastSyncOk,
              lastSyncError: device.lastSyncError,
            })),
          }
        : {}),
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

  /**
   * A paired device saying how its last sync went. Sent after every sync, so
   * the overview's "last sync" is the device's own account of it rather than a
   * guess from `lastSeenAt`.
   */
  app.put<{ Body: ReportBody }>(
    "/devices/me/report",
    { schema: { body: reportBodySchema } },
    async (request, reply) => {
      if (!request.deviceId) {
        return reply.code(403).send({ error: "Only a paired device can report its sync", code: "device_token_required" });
      }
      const { appVersion, flavor, ok, error } = request.body;
      await prisma.device.update({
        where: { id: request.deviceId },
        data: {
          ...(appVersion !== undefined ? { appVersion: appVersion.trim() } : {}),
          ...(flavor !== undefined ? { flavor: flavor.trim() } : {}),
          lastSyncAt: new Date(),
          lastSyncOk: ok,
          lastSyncError: ok ? null : (error?.trim() || "Sync failed").slice(0, MAX_SYNC_ERROR_LENGTH),
        },
      });
      return reply.code(204).send();
    }
  );
};

export default syncOverviewRoutes;
