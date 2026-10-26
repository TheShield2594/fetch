import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const LIST_ID = "44444444-4444-4444-8444-444444444444";
const headers = { "x-profile-id": PROFILE_ID };

const prismaMock = {
  profile: { findUnique: vi.fn(), findMany: vi.fn() },
  profileCollections: { findUnique: vi.fn(), upsert: vi.fn() },
  list: { findFirst: vi.fn() },
};
vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

const listMetas = vi.fn();
const tmdbPage = vi.fn();
vi.mock("../lib/stremio-helpers.js", () => ({
  getCustomListMetas: listMetas,
  buildMetasFromIds: vi.fn(),
}));
vi.mock("../lib/tmdb-client.js", () => ({ getTmdb: async () => ({ collectionSourcePage: tmdbPage }) }));
vi.mock("../lib/trakt-client.js", () => ({ isTraktConnected: async () => false, getTraktClient: vi.fn() }));

const buildApp = (): Promise<FastifyInstance> => buildRouteApp(() => import("./collections.js"));

const collection = {
  id: "c1",
  title: "Family",
  folders: [{ id: "f1", title: "Movie night", sources: [{ provider: "fetch", listId: LIST_ID, mediaType: "MOVIE" }] }],
};

describe("collections routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.profile.findUnique.mockResolvedValue({ id: PROFILE_ID, pinHash: null });
  });

  it("answers an empty list for a profile that has none", async () => {
    prismaMock.profileCollections.findUnique.mockResolvedValue(null);
    const app = await buildApp();

    const response = await app.inject({ method: "GET", url: "/collections", headers });

    expect(response.json()).toEqual({ collections: [], updatedAt: null });
  });

  it("replaces the whole list and stores it as sent", async () => {
    prismaMock.profileCollections.upsert.mockResolvedValue({});
    const app = await buildApp();

    const response = await app.inject({ method: "PUT", url: "/collections", headers, payload: { collections: [collection] } });

    expect(response.statusCode).toBe(200);
    expect(response.json().collections).toEqual([collection]);
    expect(prismaMock.profileCollections.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { profileId: PROFILE_ID },
        update: { collections: [collection], updatedAt: expect.any(Date) },
      })
    );
  });

  it("refuses to overwrite a newer save when the client says what it last read", async () => {
    prismaMock.profileCollections.findUnique.mockResolvedValue({
      collections: [collection],
      updatedAt: new Date("2026-10-26T10:00:00Z"),
    });
    const app = await buildApp();

    const response = await app.inject({
      method: "PUT",
      url: "/collections",
      headers,
      payload: { collections: [], baseUpdatedAt: "2026-10-25T10:00:00.000Z" },
    });

    expect(response.statusCode).toBe(409);
    expect(response.json()).toMatchObject({ code: "collections_conflict", updatedAt: "2026-10-26T10:00:00.000Z" });
    expect(prismaMock.profileCollections.upsert).not.toHaveBeenCalled();
  });

  it("rejects an invalid document", async () => {
    const app = await buildApp();
    const response = await app.inject({ method: "PUT", url: "/collections", headers, payload: { collections: [{ id: "x" }] } });
    expect(response.statusCode).toBe(400);
  });

  describe("POST /collections/preview", () => {
    it("previews one of the profile's own lists", async () => {
      prismaMock.list.findFirst.mockResolvedValue({ id: LIST_ID });
      listMetas.mockResolvedValue([{ id: "tt1", type: "movie", name: "Up" }]);
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/collections/preview",
        headers,
        payload: { source: { provider: "fetch", listId: LIST_ID, mediaType: "MOVIE" } },
      });

      expect(response.json()).toEqual({ metas: [{ id: "tt1", type: "movie", name: "Up" }] });
      expect(prismaMock.list.findFirst).toHaveBeenCalledWith({ where: { id: LIST_ID, profileId: PROFILE_ID }, select: { id: true } });
      expect(listMetas).toHaveBeenCalledWith(LIST_ID, "movie", 20);
    });

    it("won't preview another profile's list", async () => {
      prismaMock.list.findFirst.mockResolvedValue(null);
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/collections/preview",
        headers,
        payload: { source: { provider: "fetch", listId: LIST_ID } },
      });

      expect(response.statusCode).toBe(404);
    });

    it("previews a TMDB discover source", async () => {
      tmdbPage.mockResolvedValue([
        { imdbId: "tt2", type: "movie", name: "Heat", poster: null, year: 1995, description: null, genres: [], rating: 8 },
      ]);
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/collections/preview",
        headers,
        payload: { source: { provider: "tmdb", tmdbSourceType: "DISCOVER", mediaType: "MOVIE", filters: { year: 1995 } } },
      });

      expect(response.json().metas).toEqual([{ id: "tt2", type: "movie", name: "Heat", year: 1995, genres: [], rating: 8 }]);
      expect(tmdbPage).toHaveBeenCalledWith(expect.objectContaining({ sourceType: "DISCOVER", filters: { year: 1995 } }));
    });

    it("says when Trakt isn't connected", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: "/collections/preview",
        headers,
        payload: { source: { provider: "trakt", traktListId: 9 } },
      });
      expect(response.statusCode).toBe(409);
    });

    it("leaves addon catalogs to the browser", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: "/collections/preview",
        headers,
        payload: { source: { provider: "addon", addonId: "a", type: "movie", catalogId: "top" } },
      });
      expect(response.statusCode).toBe(422);
    });
  });
});
