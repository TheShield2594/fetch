import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const OTHER_ID = "22222222-2222-4222-8222-222222222222";

const count = (profileId: string, n: number) => ({ profileId, _count: { _all: n } });

const prismaMock = {
  profile: { findMany: vi.fn() },
  profileAddon: { groupBy: vi.fn() },
  list: { groupBy: vi.fn() },
  playbackProgress: { groupBy: vi.fn() },
  watchEvent: { groupBy: vi.fn() },
  profileDocument: { findMany: vi.fn() },
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));
vi.mock("../lib/db-health.js", () => ({ checkDatabase: async () => ({ ok: true, latencyMs: 3, error: null }) }));
vi.mock("../lib/job-status.js", () => ({
  getJobRuns: async () => [
    { job: "trakt-history-poll", status: "failed", message: "boom", durationMs: 10, overran: false, at: "2026-10-26T12:00:00.000Z" },
  ],
}));

const buildApp = (): Promise<FastifyInstance> => buildRouteApp(() => import("./sync-overview.js"));

describe("GET /sync/overview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.profile.findMany.mockResolvedValue([
      { id: PROFILE_ID, name: "Default" },
      { id: OTHER_ID, name: "Kids" },
    ]);
    prismaMock.profileAddon.groupBy
      .mockResolvedValueOnce([count(PROFILE_ID, 3)])
      .mockResolvedValueOnce([count(PROFILE_ID, 2)]);
    prismaMock.list.groupBy.mockResolvedValue([count(PROFILE_ID, 4), count(OTHER_ID, 1)]);
    prismaMock.playbackProgress.groupBy.mockResolvedValue([count(OTHER_ID, 7)]);
    prismaMock.watchEvent.groupBy.mockResolvedValue([count(PROFILE_ID, 120)]);
    prismaMock.profileDocument.findMany.mockResolvedValue([
      { profileId: PROFILE_ID, kind: "tv-settings", updatedAt: new Date("2026-10-25T10:00:00Z") },
    ]);
  });

  it("counts what each profile syncs and reports server health", async () => {
    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/sync/overview" });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      profiles: [
        {
          id: PROFILE_ID,
          name: "Default",
          counts: { addons: 3, enabledAddons: 2, lists: 4, resumePoints: 0, watchedItems: 120 },
          tvSettingsUpdatedAt: "2026-10-25T10:00:00.000Z",
          homeLayoutUpdatedAt: null,
        },
        {
          id: OTHER_ID,
          name: "Kids",
          counts: { addons: 0, enabledAddons: 0, lists: 1, resumePoints: 7, watchedItems: 0 },
          tvSettingsUpdatedAt: null,
          homeLayoutUpdatedAt: null,
        },
      ],
      server: {
        database: { ok: true, latencyMs: 3 },
        jobs: [{ job: "trakt-history-poll", status: "failed", message: "boom", at: "2026-10-26T12:00:00.000Z" }],
      },
    });
  });
});
