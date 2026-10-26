import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const LIST_ID = "22222222-2222-4222-8222-222222222222";
const UPDATED_AT = new Date("2026-10-26T12:00:00Z");

const prismaMock = {
  profileDocument: { findUnique: vi.fn(), create: vi.fn(), updateMany: vi.fn() },
  profileAddon: { findMany: vi.fn() },
  list: { findMany: vi.fn() },
  profileCollections: { findUnique: vi.fn() },
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

vi.mock("../lib/profile.js", () => ({
  resolveProfileParam: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));

const buildApp = (): Promise<FastifyInstance> => buildRouteApp(() => import("./profile-documents.js"));

const SETTINGS = {
  version: 1,
  features: { player_settings: { stream_auto_play_mode: { type: "string", value: "BEST_MATCH" } } },
};

describe("profile document routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.profileDocument.findUnique.mockResolvedValue(null);
    prismaMock.profileAddon.findMany.mockResolvedValue([
      {
        addonId: "com.cinemeta",
        name: "Cinemeta",
        manifest: {
          id: "com.cinemeta",
          name: "Cinemeta",
          version: "1",
          resources: ["catalog"],
          catalogs: [{ type: "movie", id: "top", name: "Popular" }],
        },
      },
    ]);
    prismaMock.list.findMany.mockResolvedValue([{ id: LIST_ID, name: "Watchlist", kind: "watchlist" }]);
    prismaMock.profileCollections.findUnique.mockResolvedValue({ collections: [{ id: "c1", title: "Friday", folders: [] }] });
  });

  describe("TV settings", () => {
    it("reads version 0 and an empty document when nothing is stored", async () => {
      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: `/profiles/${PROFILE_ID}/settings/tv` });
      expect(response.statusCode).toBe(200);
      expect(response.headers.etag).toBe('"0"');
      expect(response.json()).toEqual({ settings: {}, version: 0, updatedAt: null });
    });

    it("stores settings it doesn't know, nested ones included, exactly as sent", async () => {
      const withUnknown = {
        ...SETTINGS,
        features: { ...SETTINGS.features, from_a_newer_tv: { nested: { deeper: { type: "string", value: "kept" } } } },
        topLevelExtra: [1, 2, 3],
      };
      prismaMock.profileDocument.create.mockImplementation(async ({ data }: { data: { document: unknown } }) => ({
        document: data.document,
        version: 1,
        updatedAt: UPDATED_AT,
      }));
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/settings/tv`,
        headers: { "if-match": '"0"' },
        payload: { settings: withUnknown },
      });
      expect(response.statusCode).toBe(200);
      expect(prismaMock.profileDocument.create.mock.calls[0]![0].data.document).toEqual(withUnknown);
      expect(response.json().settings).toEqual(withUnknown);
    });

    it("requires If-Match", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/settings/tv`,
        payload: { settings: SETTINGS },
      });
      expect(response.statusCode).toBe(428);
      expect(response.json().code).toBe("if_match_required");
    });

    it("creates the first version with If-Match 0", async () => {
      prismaMock.profileDocument.create.mockResolvedValue({ document: SETTINGS, version: 1, updatedAt: UPDATED_AT });
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/settings/tv`,
        headers: { "if-match": '"0"' },
        payload: { settings: SETTINGS },
      });
      expect(response.statusCode).toBe(200);
      expect(response.headers.etag).toBe('"1"');
      expect(prismaMock.profileDocument.create).toHaveBeenCalledWith(
        expect.objectContaining({ data: expect.objectContaining({ profileId: PROFILE_ID, kind: "tv-settings", version: 1 }) })
      );
    });

    it("answers a stale write with 412 and the current version", async () => {
      prismaMock.profileDocument.updateMany.mockResolvedValue({ count: 0 });
      prismaMock.profileDocument.findUnique.mockResolvedValue({ document: SETTINGS, version: 4, updatedAt: UPDATED_AT });
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/settings/tv`,
        headers: { "if-match": '"3"' },
        payload: { settings: SETTINGS },
      });
      expect(response.statusCode).toBe(412);
      expect(response.json()).toMatchObject({ code: "version_conflict", version: 4 });
      expect(prismaMock.profileDocument.updateMany).toHaveBeenCalledWith({
        where: { profileId: PROFILE_ID, kind: "tv-settings", version: 3 },
        data: expect.objectContaining({ version: 4 }),
      });
    });

    it("refuses a document that isn't an object", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/settings/tv`,
        headers: { "if-match": '"0"' },
        payload: { settings: [1, 2] },
      });
      expect(response.statusCode).toBe(400);
    });
  });

  describe("Home layout", () => {
    it("lists every row the profile's addons and lists provide when nothing is arranged", async () => {
      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: `/profiles/${PROFILE_ID}/home-layout` });
      expect(response.statusCode).toBe(200);
      expect(response.json().rows.map((row: { key: string }) => row.key)).toEqual([
        "builtin:continue_watching",
        "addon:com.cinemeta:movie:top",
        `list:${LIST_ID}`,
        "collection:c1",
      ]);
    });

    it("stores the order sent, minus rows whose source is gone", async () => {
      prismaMock.profileDocument.updateMany.mockResolvedValue({ count: 1 });
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/home-layout`,
        headers: { "if-match": '"2"' },
        payload: {
          rows: [
            { key: `list:${LIST_ID}`, hidden: false },
            { key: "addon:com.removed:movie:x", hidden: false },
            { key: "addon:com.cinemeta:movie:top", hidden: true },
          ],
        },
      });
      expect(response.statusCode).toBe(200);
      expect(prismaMock.profileDocument.updateMany).toHaveBeenCalledWith({
        where: { profileId: PROFILE_ID, kind: "home-layout", version: 2 },
        data: expect.objectContaining({
          version: 3,
          document: {
            rows: [
              { key: `list:${LIST_ID}`, hidden: false },
              { key: "addon:com.cinemeta:movie:top", hidden: true },
            ],
          },
        }),
      });
      expect(response.json().rows.map((row: { key: string; hidden: boolean }) => [row.key, row.hidden])).toEqual([
        [`list:${LIST_ID}`, false],
        ["addon:com.cinemeta:movie:top", true],
        ["builtin:continue_watching", false],
        ["collection:c1", false],
      ]);
    });

    it("rejects a row key with no source", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/home-layout`,
        headers: { "if-match": '"0"' },
        payload: { rows: [{ key: "nonsense" }] },
      });
      expect(response.statusCode).toBe(400);
    });
  });
});
