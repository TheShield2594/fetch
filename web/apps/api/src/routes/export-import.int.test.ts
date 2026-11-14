import { beforeAll, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { prisma } from "../lib/prisma.js";
import { buildIntApp, createProfile } from "../lib/test-fixtures/int-db.js";

// The backup path, end to end: what `GET /export` produces, `POST /import` has
// to be able to read back into an empty database and arrive at the same library.
//
// This is the test that cannot be written against a mock at all. A round trip is
// a claim about what the database ends up holding — the batch upserts, the
// `skipDuplicates` on list items, the "only if newer" rule on series progress,
// the dedup index folding two rows into one — and every one of those is Postgres
// deciding, not the code.

let profileId = "";

vi.mock("../lib/profile.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/profile.js")>()),
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = profileId;
  },
}));

const SHAWSHANK = "tt0111161";
const GODFATHER = "tt0068646";
const SOPRANOS = "tt0141842";

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildIntApp((await import("./export.js")).default);
});

type ExportPayload = {
  version: number;
  profile: { name: string };
  lists: { id?: string; name: string; kind: string; items: { type: string; imdbId: string; addedAt: string }[] }[];
  watchEvents: {
    type: string;
    imdbId: string;
    seriesImdbId: string | null;
    season: number | null;
    episode: number | null;
    watchedAt: string;
    plays: number;
    note?: string | null;
  }[];
  seriesProgress: { seriesImdbId: string; lastSeason: number; lastEpisode: number; lastWatchedAt: string }[];
  ratings: { imdbId: string; type: string; season?: number; episode?: number; rating: number; ratedAt: string }[];
  playback: {
    type: string;
    imdbId: string;
    season: number | null;
    episode: number | null;
    progress: number;
    positionSeconds: number | null;
    durationSeconds: number | null;
    pausedAt: string;
  }[];
};

const exportFor = async (id: string): Promise<ExportPayload> => {
  profileId = id;
  const response = await app.inject({ method: "GET", url: "/export" });
  expect(response.statusCode).toBe(200);
  return response.json();
};

const importInto = async (id: string, payload: unknown) => {
  profileId = id;
  const response = await app.inject({ method: "POST", url: "/import", payload: payload as object });
  expect(response.statusCode).toBe(200);
  return response.json();
};

/** A library with one of everything an export carries. */
const seedLibrary = async (name: string) => {
  const profile = await createProfile(name);

  await prisma.list.create({
    data: {
      profileId: profile.id,
      kind: "watchlist",
      name: "Watchlist",
      items: {
        create: [
          { type: "movie", imdbId: GODFATHER, addedAt: new Date("2026-04-01T10:00:00Z") },
          { type: "series", imdbId: SOPRANOS, addedAt: new Date("2026-04-02T10:00:00Z") },
        ],
      },
    },
  });
  await prisma.list.create({
    data: {
      profileId: profile.id,
      kind: "custom",
      name: "Comfort films",
      items: { create: [{ type: "movie", imdbId: SHAWSHANK, addedAt: new Date("2026-04-03T10:00:00Z") }] },
    },
  });

  await prisma.watchEvent.create({
    data: {
      profileId: profile.id,
      type: "movie",
      imdbId: SHAWSHANK,
      watchedAt: new Date("2026-05-01T20:00:00Z"),
      plays: 3,
      note: "still holds up",
    },
  });
  await prisma.watchEvent.create({
    data: {
      profileId: profile.id,
      type: "episode",
      imdbId: SOPRANOS,
      seriesImdbId: SOPRANOS,
      season: 2,
      episode: 7,
      watchedAt: new Date("2026-05-02T20:00:00Z"),
    },
  });

  await prisma.seriesProgress.create({
    data: {
      profileId: profile.id,
      seriesImdbId: SOPRANOS,
      lastSeason: 2,
      lastEpisode: 7,
      lastWatchedAt: new Date("2026-05-02T20:00:00Z"),
      updatedAt: new Date("2026-05-02T20:00:00Z"),
    },
  });

  await prisma.rating.create({
    data: {
      profileId: profile.id,
      imdbId: SHAWSHANK,
      type: "movie",
      rating: 9.5,
      ratedAt: new Date("2026-05-01T21:00:00Z"),
      note: "yes",
    },
  });
  await prisma.rating.create({
    data: {
      profileId: profile.id,
      imdbId: SOPRANOS,
      type: "episode",
      season: 2,
      episode: 7,
      rating: 8,
      ratedAt: new Date("2026-05-02T21:00:00Z"),
    },
  });

  // Paused after the last watch of each title, so the import keeps both.
  await prisma.playbackProgress.create({
    data: {
      profileId: profile.id,
      key: `movie:${GODFATHER}`,
      type: "movie",
      imdbId: GODFATHER,
      progress: 35,
      positionSeconds: 3_660,
      durationSeconds: 10_500,
      pausedAt: new Date("2026-05-03T20:00:00Z"),
    },
  });
  await prisma.playbackProgress.create({
    data: {
      profileId: profile.id,
      key: `episode:${SOPRANOS}:2:8`,
      type: "episode",
      imdbId: SOPRANOS,
      season: 2,
      episode: 8,
      progress: 50,
      pausedAt: new Date("2026-05-04T20:00:00Z"),
    },
  });

  return profile;
};

/**
 * Compares two exports on everything but the moment they were taken and the
 * lists' ids, which a restore mints afresh.
 */
const comparable = (payload: ExportPayload) => ({
  version: payload.version,
  lists: [...payload.lists]
    .map((list) => ({
      name: list.name,
      kind: list.kind,
      items: [...list.items].sort((a, b) => a.imdbId.localeCompare(b.imdbId)),
    }))
    .sort((a, b) => a.name.localeCompare(b.name)),
  watchEvents: [...payload.watchEvents].sort((a, b) => a.watchedAt.localeCompare(b.watchedAt)),
  seriesProgress: [...payload.seriesProgress].sort((a, b) => a.seriesImdbId.localeCompare(b.seriesImdbId)),
  ratings: [...payload.ratings].sort((a, b) => `${a.imdbId}${a.type}`.localeCompare(`${b.imdbId}${b.type}`)),
  playback: [...payload.playback].sort((a, b) => a.pausedAt.localeCompare(b.pausedAt)),
});

describe("GET /export then POST /import", () => {
  it("restores the same library into an empty profile", async () => {
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    const summary = await importInto(restored.id, exported);

    expect(summary).toMatchObject({
      status: "imported",
      summary: { lists: 1, listItems: 3, watchEvents: 2, seriesProgress: 1, ratings: 2, playback: 2 },
    });
    expect(comparable(await exportFor(restored.id))).toEqual(comparable(exported));
  });

  it("carries every field of a watch event, not just its identity", async () => {
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);

    const movie = await prisma.watchEvent.findFirst({
      where: { profileId: restored.id, type: "movie", imdbId: SHAWSHANK },
    });
    expect(movie).toMatchObject({ plays: 3, note: "still holds up" });
    expect(movie?.watchedAt.toISOString()).toBe("2026-05-01T20:00:00.000Z");
  });

  it("puts the watchlist back into the profile's own watchlist, not a second one", async () => {
    // Watchlist rows import into `getDefaultWatchlist`, which the restored
    // profile may already have; a custom list is matched by name. Either way
    // there is exactly one watchlist afterwards — the singleton index would
    // reject a second, so this is also a check that nothing tries.
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);

    const lists = await prisma.list.findMany({ where: { profileId: restored.id } });
    expect(lists.filter((list) => list.kind === "watchlist")).toHaveLength(1);
    expect(lists.map((list) => list.name).sort()).toEqual(["Comfort films", "Watchlist"]);
  });

  it("creates no duplicate rows when the same file is imported twice", async () => {
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);
    await importInto(restored.id, exported);

    // The dedup key and `skipDuplicates` are what make the second pass fold into
    // the first rather than doubling the library.
    expect(await prisma.watchEvent.count({ where: { profileId: restored.id } })).toBe(2);
    expect(await prisma.listItem.count({ where: { list: { profileId: restored.id } } })).toBe(3);
    expect(await prisma.list.count({ where: { profileId: restored.id } })).toBe(2);
    expect(await prisma.rating.count({ where: { profileId: restored.id } })).toBe(2);
    expect(await prisma.seriesProgress.count({ where: { profileId: restored.id } })).toBe(1);
    expect(await prisma.playbackProgress.count({ where: { profileId: restored.id } })).toBe(2);
  });

  it("leaves the play counts alone on a second import of the same file", async () => {
    // A row the profile already has is the same watch arriving again, so a
    // repeated import keeps the larger count rather than adding the file's on
    // top: three plays stay three, not six.
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);
    await importInto(restored.id, exported);

    const movie = await prisma.watchEvent.findFirst({
      where: { profileId: restored.id, type: "movie", imdbId: SHAWSHANK },
    });
    expect(movie?.plays).toBe(3);
  });

  it("raises a stored play count when the file carries more plays for that day", async () => {
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await prisma.watchEvent.create({
      data: {
        profileId: restored.id,
        type: "movie",
        imdbId: SHAWSHANK,
        watchedAt: new Date("2026-05-01T08:00:00.000Z"),
        plays: 1,
      },
    });
    await importInto(restored.id, exported);

    const movies = await prisma.watchEvent.findMany({
      where: { profileId: restored.id, type: "movie", imdbId: SHAWSHANK },
    });
    expect(movies).toHaveLength(1);
    expect(movies[0]?.plays).toBe(3);
  });

  it("compares a day's plays with every stored row of that day, Trakt rows included", async () => {
    // Two Trakt history rows on one day sit outside the dedup index. Re-importing
    // an export that carries the day as two plays must not raise either row.
    const restored = await createProfile("Restored");
    for (const [hour, id] of [["18", 1n], ["21", 2n]] as const) {
      await prisma.watchEvent.create({
        data: {
          profileId: restored.id,
          type: "movie",
          imdbId: GODFATHER,
          watchedAt: new Date(`2026-06-01T${hour}:00:00.000Z`),
          plays: 1,
          traktHistoryId: id,
        },
      });
    }
    const exported = await exportFor(restored.id);
    await importInto(restored.id, exported);

    const rows = await prisma.watchEvent.findMany({ where: { profileId: restored.id, imdbId: GODFATHER } });
    expect(rows.reduce((total, row) => total + row.plays, 0)).toBe(2);
  });

  it("imports into the requesting profile and leaves the source alone", async () => {
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);

    expect(comparable(await exportFor(source.id))).toEqual(comparable(exported));
    expect(await prisma.watchEvent.count({ where: { profileId: source.id } })).toBe(2);
  });

  it("exports an empty profile as empty collections, and imports that as a no-op", async () => {
    const empty = await createProfile("Empty");
    const exported = await exportFor(empty.id);

    expect(exported).toMatchObject({ watchEvents: [], seriesProgress: [], ratings: [], playback: [] });

    const target = await createProfile("Target");
    await importInto(target.id, exported);

    expect(await prisma.watchEvent.count({ where: { profileId: target.id } })).toBe(0);
  });

  it("refuses a payload from a version it does not understand", async () => {
    const profile = await createProfile();
    profileId = profile.id;

    const response = await app.inject({
      method: "POST",
      url: "/import",
      payload: { version: 99, lists: [], watchEvents: [], seriesProgress: [], ratings: [] },
    });

    expect(response.statusCode).toBe(400);
    expect(await prisma.watchEvent.count()).toBe(0);
  });

  it("keeps series progress that is already ahead of the file", async () => {
    // Import moves progress forward only. Restoring an old backup over a live
    // profile must not rewind what has been watched since.
    const source = await seedLibrary("Ada");
    const exported = await exportFor(source.id);

    const target = await createProfile("Target");
    await prisma.seriesProgress.create({
      data: {
        profileId: target.id,
        seriesImdbId: SOPRANOS,
        lastSeason: 4,
        lastEpisode: 1,
        lastWatchedAt: new Date("2026-06-01T20:00:00Z"),
        updatedAt: new Date("2026-06-01T20:00:00Z"),
      },
    });

    await importInto(target.id, exported);

    const progress = await prisma.seriesProgress.findUnique({
      where: { profileId_seriesImdbId: { profileId: target.id, seriesImdbId: SOPRANOS } },
    });
    expect(progress).toMatchObject({ lastSeason: 4, lastEpisode: 1 });
  });

  it("round-trips a season rating's season and an episode rating's episode", async () => {
    // Season and episode are how a rating is located; losing one files it against
    // the wrong thing rather than losing it visibly.
    const source = await createProfile("Ada");
    await prisma.rating.create({
      data: {
        profileId: source.id,
        imdbId: SOPRANOS,
        type: "season",
        season: 3,
        rating: 9,
        ratedAt: new Date("2026-05-02T21:00:00Z"),
      },
    });

    const exported = await exportFor(source.id);
    expect(exported.ratings[0]).toMatchObject({ type: "season", season: 3 });

    const restored = await createProfile("Restored");
    await importInto(restored.id, exported);

    const rating = await prisma.rating.findFirst({ where: { profileId: restored.id } });
    expect(rating).toMatchObject({ type: "season", season: 3, episode: 0, rating: 9 });
  });
});

describe("collections and profile settings in a backup", () => {
  it("come back on another profile, with website-list sources pointing at the restored list", async () => {
    const source = await createProfile();
    const target = await createProfile();
    await prisma.profile.update({
      where: { id: source.id },
      data: { avatarId: "fetch:fox", isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false },
    });
    const list = await prisma.list.create({ data: { profileId: source.id, kind: "custom", name: "Family night" } });
    await prisma.profileCollections.create({
      data: {
        profileId: source.id,
        updatedAt: new Date(),
        collections: [
          {
            id: "c1",
            title: "Family",
            folders: [{ id: "f1", title: "Films", sources: [{ provider: "fetch", listId: list.id, title: "Family night" }] }],
          },
        ],
      },
    });

    const response = await importInto(target.id, await exportFor(source.id));
    expect(response.summary).toMatchObject({ collections: 1, profileSettings: true });

    const restoredList = await prisma.list.findFirstOrThrow({ where: { profileId: target.id, name: "Family night" } });
    const restored = await prisma.profileCollections.findUniqueOrThrow({ where: { profileId: target.id } });
    const sources = (restored.collections as { folders: { sources: { listId: string }[] }[] }[])[0]!.folders[0]!.sources;
    expect(sources[0]!.listId).toBe(restoredList.id);

    const profile = await prisma.profile.findUniqueOrThrow({ where: { id: target.id } });
    expect(profile).toMatchObject({ avatarId: "fetch:fox", isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false });
  });
});
