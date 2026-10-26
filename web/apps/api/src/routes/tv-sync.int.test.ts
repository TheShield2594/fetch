import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, FastifyPluginAsync } from "fastify";
import { prisma } from "../lib/prisma.js";
import { buildIntApp, createProfile } from "../lib/test-fixtures/int-db.js";
import profileAddonsRoutes from "./profile-addons.js";
import profileDocumentsRoutes from "./profile-documents.js";

// What the mocked route tests can't see: the new tables as the migration
// creates them, the conditional write racing itself for real, and the cascade
// from a deleted profile.

// IP literals, so no DNS is consulted; every fetch fails, so the manifest the
// caller supplies is what gets stored.
const ADDON_A = "http://10.0.0.5:7000/secret-config/manifest.json";
const ADDON_B = "http://10.0.0.6:7000/manifest.json";

const MANIFEST_A = {
  id: "com.example.a",
  name: "Addon A",
  version: "1.0.0",
  resources: ["catalog"],
  catalogs: [{ type: "movie", id: "top", name: "Top movies" }],
};

const bothRoutes: FastifyPluginAsync = async (app) => {
  await app.register(profileAddonsRoutes);
  await app.register(profileDocumentsRoutes);
};

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildIntApp(bothRoutes);
});

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => {
    throw new TypeError("fetch failed");
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("versioned profile documents", () => {
  it("lets exactly one of two racing first writes win", async () => {
    const { id } = await createProfile();
    const put = (value: string) =>
      app.inject({
        method: "PUT",
        url: `/profiles/${id}/settings/tv`,
        headers: { "if-match": '"0"' },
        payload: { settings: { version: 1, features: { player_settings: { k: { type: "string", value } } } } },
      });

    const results = await Promise.all([put("first"), put("second")]);
    expect(results.map((r) => r.statusCode).sort()).toEqual([200, 412]);

    const stored = await prisma.profileDocument.findUnique({ where: { profileId_kind: { profileId: id, kind: "tv-settings" } } });
    expect(stored?.version).toBe(1);
  });

  it("moves forward only from the current version", async () => {
    const { id } = await createProfile();
    const put = (ifMatch: string) =>
      app.inject({
        method: "PUT",
        url: `/profiles/${id}/settings/tv`,
        headers: { "if-match": ifMatch },
        payload: { settings: { version: 1 } },
      });

    expect((await put('"0"')).json().version).toBe(1);
    expect((await put('"1"')).json().version).toBe(2);
    const stale = await put('"1"');
    expect(stale.statusCode).toBe(412);
    expect(stale.json().version).toBe(2);
  });
});

describe("addons and the Home layout", () => {
  it("keeps the TV's list in order, even addons this server can't reach", async () => {
    const { id } = await createProfile();
    const response = await app.inject({
      method: "PUT",
      url: `/profiles/${id}/addons`,
      payload: {
        addons: [
          { manifestUrl: ADDON_B, name: "Addon B", enabled: false },
          { manifestUrl: ADDON_A, manifest: MANIFEST_A },
        ],
      },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().addons.map((a: { name: string; reachable: boolean; enabled: boolean }) => [a.name, a.reachable, a.enabled])).toEqual([
      ["Addon B", false, false],
      ["Addon A", true, true],
    ]);
  });

  it("drops a removed addon's rows from the stored layout and keeps the rest of the order", async () => {
    const { id } = await createProfile();
    const list = await prisma.list.create({ data: { profileId: id, kind: "custom", name: "Friday films" } });
    const add = await app.inject({
      method: "POST",
      url: `/profiles/${id}/addons`,
      payload: { manifestUrl: ADDON_A, manifest: MANIFEST_A },
    });
    expect(add.statusCode).toBe(201);
    const addonId = add.json().addon.id as string;

    const layout = await app.inject({ method: "GET", url: `/profiles/${id}/home-layout` });
    const keys = layout.json().rows.map((row: { key: string }) => row.key);
    expect(keys).toEqual(["builtin:continue_watching", "addon:com.example.a:movie:top", `list:${list.id}`]);

    const saved = await app.inject({
      method: "PUT",
      url: `/profiles/${id}/home-layout`,
      headers: { "if-match": '"0"' },
      payload: {
        rows: [
          { key: `list:${list.id}`, hidden: false },
          { key: "addon:com.example.a:movie:top", hidden: false },
          { key: "builtin:continue_watching", hidden: true },
        ],
      },
    });
    expect(saved.statusCode).toBe(200);

    expect((await app.inject({ method: "DELETE", url: `/profiles/${id}/addons/${addonId}` })).statusCode).toBe(204);

    const stored = await prisma.profileDocument.findUnique({ where: { profileId_kind: { profileId: id, kind: "home-layout" } } });
    expect(stored?.version).toBe(2);
    expect(stored?.document).toEqual({
      rows: [
        { key: `list:${list.id}`, hidden: false },
        { key: "builtin:continue_watching", hidden: true },
      ],
    });
  });

  it("goes with the profile when it is deleted", async () => {
    const { id } = await createProfile();
    await app.inject({ method: "POST", url: `/profiles/${id}/addons`, payload: { manifestUrl: ADDON_A, manifest: MANIFEST_A } });
    await app.inject({
      method: "PUT",
      url: `/profiles/${id}/settings/tv`,
      headers: { "if-match": '"0"' },
      payload: { settings: { version: 1 } },
    });

    await prisma.profile.delete({ where: { id } });

    expect(await prisma.profileAddon.count({ where: { profileId: id } })).toBe(0);
    expect(await prisma.profileDocument.count({ where: { profileId: id } })).toBe(0);
  });

  it("refuses the same addon twice through the database's own constraint", async () => {
    const { id } = await createProfile();
    await prisma.profileAddon.create({ data: { profileId: id, manifestUrl: ADDON_A, position: 0, name: "A" } });
    await expect(
      prisma.profileAddon.create({ data: { profileId: id, manifestUrl: ADDON_A, position: 1, name: "A again" } })
    ).rejects.toMatchObject({ code: "P2002" });
  });
});
