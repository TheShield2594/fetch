import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const LIST_ID = "22222222-2222-4222-8222-222222222222";

const prismaMock = {
  list: { findFirst: vi.fn(), findUnique: vi.fn(), update: vi.fn() },
  listItem: { findMany: vi.fn() },
  metadata: { findMany: vi.fn() },
};
vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));
vi.mock("../lib/profile.js", () => ({
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));

const buildApp = () => buildRouteApp(() => import("./shared-lists.js"));
const keyFor = async (generation: number) => (await import("../lib/share-keys.js")).deriveShareKey("list", LIST_ID, generation)!;

describe("shared lists", () => {
  const original = process.env.API_TOKEN;
  beforeEach(() => {
    vi.clearAllMocks();
    process.env.API_TOKEN = "a-test-token";
    prismaMock.list.update.mockResolvedValue({});
  });
  afterEach(() => {
    process.env.API_TOKEN = original;
  });

  it("shares a list of the calling profile and answers with its link", async () => {
    prismaMock.list.findFirst.mockResolvedValue({ id: LIST_ID, shareGeneration: null });

    const response = await (await buildApp()).inject({ method: "POST", url: `/lists/${LIST_ID}/share` });

    expect(response.json()).toEqual({ sharePath: `/shared/lists/${LIST_ID}/${await keyFor(1)}` });
    expect(prismaMock.list.findFirst).toHaveBeenCalledWith(
      expect.objectContaining({ where: { id: LIST_ID, profileId: PROFILE_ID } })
    );
    expect(prismaMock.list.update).toHaveBeenCalledWith({ where: { id: LIST_ID }, data: { shareGeneration: 1 } });
  });

  it("gives a re-shared list a new link rather than its old one", async () => {
    prismaMock.list.findFirst.mockResolvedValue({ id: LIST_ID, shareGeneration: -1 });

    const response = await (await buildApp()).inject({ method: "POST", url: `/lists/${LIST_ID}/share` });

    expect(response.json().sharePath).toBe(`/shared/lists/${LIST_ID}/${await keyFor(2)}`);
  });

  it("unshares by retiring the link, and won't touch another profile's list", async () => {
    prismaMock.list.findFirst.mockResolvedValueOnce({ id: LIST_ID, shareGeneration: 3 }).mockResolvedValueOnce(null);
    const app = await buildApp();

    expect((await app.inject({ method: "DELETE", url: `/lists/${LIST_ID}/share` })).statusCode).toBe(204);
    expect(prismaMock.list.update).toHaveBeenCalledWith({ where: { id: LIST_ID }, data: { shareGeneration: -3 } });
    expect((await app.inject({ method: "DELETE", url: `/lists/${LIST_ID}/share` })).statusCode).toBe(404);
  });

  describe("the public page's data", () => {
    const shared = (shareGeneration: number | null) =>
      prismaMock.list.findUnique.mockResolvedValue({
        id: LIST_ID,
        name: "Films to watch together",
        description: null,
        shareGeneration,
        _count: { items: 2 },
      });

    it("returns the list's name and titles, and nothing about whose it is", async () => {
      shared(1);
      prismaMock.listItem.findMany.mockResolvedValue([
        { type: "movie", imdbId: "tt1" },
        { type: "series", imdbId: "tt2" },
      ]);
      prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
        where.type === "movie"
          ? [{ imdbId: "tt1", type: "movie", name: "Dune", poster: "https://image.tmdb.org/p.jpg", year: 2021, genres: ["Sci-Fi"], rating: 8 }]
          : []
      );

      const response = await (await buildApp()).inject({ method: "GET", url: `/shared/lists/${LIST_ID}/${await keyFor(1)}` });

      expect(response.statusCode).toBe(200);
      const body = response.json();
      expect(body.list).toEqual({ name: "Films to watch together", description: null, itemCount: 2 });
      expect(body.items).toEqual([
        { type: "movie", imdbId: "tt1", name: "Dune", year: 2021, poster: "https://image.tmdb.org/p.jpg", genres: ["Sci-Fi"], rating: 8 },
        { type: "series", imdbId: "tt2", name: "tt2", year: null, poster: null, genres: [], rating: null },
      ]);
      expect(JSON.stringify(body)).not.toContain(PROFILE_ID);
    });

    it.each([
      ["a wrong key", 1, "x".repeat(43)],
      ["a replaced link", 2, null],
      ["an unshared list", -1, null],
      ["a list never shared", null, null],
    ])("404s for %s", async (_label, generation, key) => {
      shared(generation);

      const response = await (await buildApp()).inject({
        method: "GET",
        url: `/shared/lists/${LIST_ID}/${key ?? (await keyFor(1))}`,
      });

      expect(response.statusCode).toBe(404);
      expect(prismaMock.listItem.findMany).not.toHaveBeenCalled();
    });

    it("404s for a list that doesn't exist, the same way", async () => {
      prismaMock.list.findUnique.mockResolvedValue(null);

      const response = await (await buildApp()).inject({ method: "GET", url: `/shared/lists/${LIST_ID}/${await keyFor(1)}` });

      expect(response.statusCode).toBe(404);
    });
  });
});
