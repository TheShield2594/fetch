import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, FastifyPluginAsync } from "fastify";
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
  device: { findMany: vi.fn(), update: vi.fn() },
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));
const database = vi.hoisted(() => ({ ok: true }));
vi.mock("../lib/db-health.js", () => ({
  checkDatabase: async () => ({ ok: database.ok, latencyMs: 3, error: database.ok ? null : "down" }),
}));
vi.mock("../lib/job-status.js", () => ({
  getJobRuns: async () => [
    { job: "trakt-history-poll", status: "failed", message: "boom", durationMs: 10, overran: false, at: "2026-10-26T12:00:00.000Z" },
  ],
}));

const DEVICE_ID = "33333333-3333-4333-8333-333333333333";

/**
 * The real gate sets `request.deviceId` from a device token; here a test
 * header stands in for it, as in devices.test.ts.
 */
const buildApp = (): Promise<FastifyInstance> =>
  buildRouteApp(async () => {
    const { default: routes } = await import("./sync-overview.js");
    const plugin: FastifyPluginAsync = async (app) => {
      app.addHook("onRequest", async (request) => {
        const device = request.headers["x-test-device"];
        if (typeof device === "string") request.deviceId = device;
      });
      await app.register(routes);
    };
    return { default: plugin };
  });

const asDevice = { "x-test-device": DEVICE_ID };

describe("GET /sync/overview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    database.ok = true;
    prismaMock.profile.findMany.mockResolvedValue([
      { id: PROFILE_ID, name: "Default", usesPrimaryAddons: false },
      { id: OTHER_ID, name: "Kids", usesPrimaryAddons: false },
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
    prismaMock.device.findMany.mockResolvedValue([
      {
        id: DEVICE_ID,
        name: "Living room",
        lastSeenAt: new Date("2026-10-26T11:59:00Z"),
        appVersion: "1.4.0",
        flavor: "playstore",
        lastSyncAt: new Date("2026-10-26T11:58:00Z"),
        lastSyncOk: false,
        lastSyncError: "Couldn't reach the website",
      },
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
          addonsFromPrimary: false,
          counts: { addons: 3, enabledAddons: 2, lists: 4, resumePoints: 0, watchedItems: 120 },
          tvSettingsUpdatedAt: "2026-10-25T10:00:00.000Z",
          homeLayoutUpdatedAt: null,
        },
        {
          id: OTHER_ID,
          name: "Kids",
          addonsFromPrimary: false,
          counts: { addons: 0, enabledAddons: 0, lists: 1, resumePoints: 7, watchedItems: 0 },
          tvSettingsUpdatedAt: null,
          homeLayoutUpdatedAt: null,
        },
      ],
      devices: [
        {
          id: DEVICE_ID,
          name: "Living room",
          lastSeenAt: "2026-10-26T11:59:00.000Z",
          appVersion: "1.4.0",
          flavor: "playstore",
          lastSyncAt: "2026-10-26T11:58:00.000Z",
          lastSyncOk: false,
          lastSyncError: "Couldn't reach the website",
        },
      ],
      server: {
        database: { ok: true, latencyMs: 3 },
        jobs: [{ job: "trakt-history-poll", status: "failed", message: "boom", at: "2026-10-26T12:00:00.000Z" }],
      },
    });
  });

  it("counts the primary profile's addons for a profile that uses them", async () => {
    prismaMock.profile.findMany.mockResolvedValue([
      { id: PROFILE_ID, name: "Default", usesPrimaryAddons: false },
      { id: OTHER_ID, name: "Kids", usesPrimaryAddons: true },
    ]);
    const app = await buildApp();
    const kids = (await app.inject({ method: "GET", url: "/sync/overview" })).json().profiles[1];
    expect(kids).toMatchObject({ addonsFromPrimary: true, counts: { addons: 3, enabledAddons: 2 } });
  });

  it("still says the database is down when it can't count anything", async () => {
    database.ok = false;
    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/sync/overview" });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      profiles: [],
      devices: [],
      server: { database: { ok: false, latencyMs: 3 }, jobs: [] },
    });
    expect(prismaMock.profile.findMany).not.toHaveBeenCalled();
  });

  it("leaves the device list out for a device caller", async () => {
    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/sync/overview", headers: asDevice });
    expect(response.json().devices).toBeUndefined();
    expect(prismaMock.device.findMany).not.toHaveBeenCalled();
  });
});

describe("PUT /devices/me/report", () => {
  beforeEach(() => vi.clearAllMocks());

  it("stores the device's build and how its sync went", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "PUT",
      url: "/devices/me/report",
      headers: asDevice,
      payload: { appVersion: "1.4.0", flavor: "full", ok: false, error: "x".repeat(800) },
    });
    expect(response.statusCode).toBe(204);
    const { data } = prismaMock.device.update.mock.calls[0]![0] as { data: Record<string, unknown> };
    expect(data).toMatchObject({ appVersion: "1.4.0", flavor: "full", lastSyncOk: false });
    expect((data.lastSyncError as string).length).toBe(500);
    expect(data.lastSyncAt).toBeInstanceOf(Date);
  });

  it("clears the error once a sync works", async () => {
    const app = await buildApp();
    await app.inject({ method: "PUT", url: "/devices/me/report", headers: asDevice, payload: { ok: true } });
    const { data } = prismaMock.device.update.mock.calls[0]![0] as { data: Record<string, unknown> };
    expect(data).toMatchObject({ lastSyncOk: true, lastSyncError: null });
  });

  it("is only for paired devices", async () => {
    const app = await buildApp();
    const response = await app.inject({ method: "PUT", url: "/devices/me/report", payload: { ok: true } });
    expect(response.statusCode).toBe(403);
  });
});
