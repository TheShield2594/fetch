import { beforeAll, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { ListKind } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { buildIntApp, createProfile } from "../lib/test-fixtures/int-db.js";

// A list's covers and its manual order are both raw SQL — a window function
// over the list's items joined to their metadata, and one UPDATE over a VALUES
// table — which a mocked Prisma only checks the *shape* of. These run them
// against Postgres, and ask what actually comes back.

let profileId = "";

vi.mock("../lib/profile.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/profile.js")>()),
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = profileId;
  },
}));

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildIntApp((await import("./lists.js")).default);
});

const poster = (imdbId: string) => `https://image.tmdb.org/t/p/w500/${imdbId}.jpg`;

/**
 * A custom list holding `ids` (added oldest first, a minute apart), with a
 * poster for every title except those in `noPoster`.
 */
const seedList = async (ids: string[], noPoster: string[] = []) => {
  const profile = await createProfile();
  profileId = profile.id;
  const list = await prisma.list.create({ data: { name: "Picks", kind: ListKind.custom, profileId } });
  const start = Date.parse("2026-01-01T00:00:00Z");
  for (const [index, imdbId] of ids.entries()) {
    await prisma.listItem.create({
      data: { listId: list.id, type: "movie", imdbId, addedAt: new Date(start + index * 60_000) },
    });
    await prisma.metadata.upsert({
      where: { imdbId_type: { imdbId, type: "movie" } },
      create: { imdbId, type: "movie", name: imdbId, poster: noPoster.includes(imdbId) ? null : poster(imdbId) },
      update: { poster: noPoster.includes(imdbId) ? null : poster(imdbId) },
    });
  }
  return list;
};

const coversOf = async (listId: string): Promise<string[]> => {
  const response = await app.inject({ method: "GET", url: "/lists" });
  const lists = response.json<{ lists: { id: string; covers: string[] }[] }>().lists;
  return lists.find((l) => l.id === listId)!.covers;
};

const ref = (imdbId: string) => ({ type: "movie", imdbId });

describe("list covers", () => {
  it("are the four newest posters while the list has never been arranged", async () => {
    const list = await seedList(["tt1", "tt2", "tt3", "tt4", "tt5"]);

    expect(await coversOf(list.id)).toEqual(["tt5", "tt4", "tt3", "tt2"].map(poster));
  });

  it("skip a title with no poster rather than leaving a hole", async () => {
    const list = await seedList(["tt1", "tt2", "tt3"], ["tt3"]);

    expect(await coversOf(list.id)).toEqual(["tt2", "tt1"].map(poster));
  });

  it("follow the list's own order once it has one", async () => {
    const list = await seedList(["tt1", "tt2", "tt3", "tt4", "tt5"]);

    const saved = await app.inject({
      method: "PUT",
      url: `/lists/${list.id}/order`,
      payload: { items: ["tt1", "tt3", "tt5", "tt2", "tt4"].map(ref) },
    });

    expect(saved.statusCode).toBe(204);
    expect(await coversOf(list.id)).toEqual(["tt1", "tt3", "tt5", "tt2"].map(poster));
  });
});

describe("arranging a list", () => {
  it("writes each named item's position and clears the rest", async () => {
    const list = await seedList(["tt1", "tt2", "tt3"]);
    await app.inject({ method: "PUT", url: `/lists/${list.id}/order`, payload: { items: ["tt1", "tt2", "tt3"].map(ref) } });

    await app.inject({ method: "PUT", url: `/lists/${list.id}/order`, payload: { items: ["tt3", "tt1"].map(ref) } });

    const rows = await prisma.listItem.findMany({ where: { listId: list.id }, orderBy: { imdbId: "asc" } });
    expect(rows.map((row) => [row.imdbId, row.position])).toEqual([["tt1", 1], ["tt2", null], ["tt3", 0]]);
  });

  it("changes nothing when an item isn't in the list", async () => {
    const list = await seedList(["tt1", "tt2"]);

    const response = await app.inject({
      method: "PUT",
      url: `/lists/${list.id}/order`,
      payload: { items: ["tt2", "tt9"].map(ref) },
    });

    expect(response.statusCode).toBe(400);
    const rows = await prisma.listItem.findMany({ where: { listId: list.id } });
    expect(rows.every((row) => row.position === null)).toBe(true);
  });
});

describe("removing several titles at once", () => {
  it("removes exactly the named ones and reports them", async () => {
    const list = await seedList(["tt1", "tt2", "tt3"]);

    const response = await app.inject({
      method: "POST",
      url: `/lists/${list.id}/items/remove`,
      payload: { items: ["tt1", "tt3", "tt9"].map(ref) },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json().removed).toEqual(expect.arrayContaining(["tt1", "tt3"].map(ref)));
    expect(response.json().removed).toHaveLength(2);
    const left = await prisma.listItem.findMany({ where: { listId: list.id } });
    expect(left.map((row) => row.imdbId)).toEqual(["tt2"]);
  });

  it("won't touch another profile's list", async () => {
    const list = await seedList(["tt1"]);
    profileId = (await createProfile("Someone else")).id;

    const response = await app.inject({
      method: "POST",
      url: `/lists/${list.id}/items/remove`,
      payload: { items: [ref("tt1")] },
    });

    expect(response.statusCode).toBe(404);
    expect(await prisma.listItem.count({ where: { listId: list.id } })).toBe(1);
  });
});
