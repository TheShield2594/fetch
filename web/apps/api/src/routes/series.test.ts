import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";

const prismaMock = {
  kV: { findUnique: vi.fn(), findMany: vi.fn(), upsert: vi.fn(), deleteMany: vi.fn() },
  seriesProgress: { findMany: vi.fn(), findUnique: vi.fn(), upsert: vi.fn(), deleteMany: vi.fn() },
  metadata: { findMany: vi.fn(), findUnique: vi.fn() },
  watchEvent: { groupBy: vi.fn(), findMany: vi.fn(), create: vi.fn(), deleteMany: vi.fn(), findFirst: vi.fn() },
  $transaction: vi.fn(),
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

vi.mock("../lib/profile.js", () => ({
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));

const nextEpisodeMock = { computeNextEpisode: vi.fn() };
vi.mock("../lib/next-episode.js", () => nextEpisodeMock);

const seasonsMock = { getSeasonsForImdbId: vi.fn() };
vi.mock("../lib/seasons.js", () => seasonsMock);

const tmdbClientMock = { getTmdb: vi.fn() };
vi.mock("../lib/tmdb-client.js", () => tmdbClientMock);

const metadataLibMock = { upsertMetadata: vi.fn() };
vi.mock("../lib/metadata.js", () => metadataLibMock);

const seriesProgressLibMock = { upsertSeriesProgressIfNewer: vi.fn() };
vi.mock("../lib/series-progress.js", () => seriesProgressLibMock);

const watchEventLibMock = { recordWatchEvent: vi.fn() };
vi.mock("../lib/watch-event.js", () => watchEventLibMock);

const resetMocks = () => {
  vi.clearAllMocks();
  prismaMock.kV.findMany.mockResolvedValue([]);
  seasonsMock.getSeasonsForImdbId.mockResolvedValue([]);
};

const buildApp = (): Promise<FastifyInstance> =>
  buildRouteApp(() => import("./series.js"));

describe("series routes — completion drops from Continue Watching", () => {
  beforeEach(() => {
    resetMocks();
  });

  describe("GET /series/progress", () => {
    it("drops a show once watched episodes reach the total, even if next-episode math disagrees", async () => {
      prismaMock.seriesProgress.findMany.mockResolvedValue([
        {
          seriesImdbId: "tt-office",
          lastSeason: 8,
          lastEpisode: 21,
          lastWatchedAt: new Date(),
          updatedAt: new Date(),
        },
      ]);
      prismaMock.metadata.findMany.mockResolvedValue([
        {
          imdbId: "tt-office",
          name: "The Office",
          poster: null,
          background: null,
          totalSeasons: 9,
          totalEpisodes: 186,
          tmdbId: 2316,
        },
      ]);
      // Simulate 186 distinct watched (season, episode) pairs.
      prismaMock.watchEvent.groupBy.mockResolvedValue(
        Array.from({ length: 186 }, (_, i) => ({
          seriesImdbId: "tt-office",
          season: 1,
          episode: i + 1,
          _count: { _all: 1 },
        }))
      );
      // Even though the season-based fallback thinks there's a next episode...
      nextEpisodeMock.computeNextEpisode.mockResolvedValue({ season: 8, episode: 22 });

      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: "/series/progress" });

      expect(response.statusCode).toBe(200);
      expect(response.json().progress).toEqual([]);
    });

    it("keeps a show with unwatched episodes remaining", async () => {
      prismaMock.seriesProgress.findMany.mockResolvedValue([
        {
          seriesImdbId: "tt-office",
          lastSeason: 8,
          lastEpisode: 10,
          lastWatchedAt: new Date(),
          updatedAt: new Date(),
        },
      ]);
      prismaMock.metadata.findMany.mockResolvedValue([
        {
          imdbId: "tt-office",
          name: "The Office",
          poster: null,
          background: null,
          totalSeasons: 9,
          totalEpisodes: 186,
          tmdbId: 2316,
        },
      ]);
      prismaMock.watchEvent.groupBy.mockResolvedValue(
        Array.from({ length: 100 }, (_, i) => ({
          seriesImdbId: "tt-office",
          season: 1,
          episode: i + 1,
          _count: { _all: 1 },
        }))
      );
      nextEpisodeMock.computeNextEpisode.mockResolvedValue({ season: 8, episode: 11 });

      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: "/series/progress" });

      expect(response.statusCode).toBe(200);
      const progress = response.json().progress;
      expect(progress).toHaveLength(1);
      expect(progress[0].nextSeason).toBe(8);
      expect(progress[0].nextEpisode).toBe(11);
    });
  });

  describe("POST /series/:imdbId/watch-next", () => {
    it("returns 409 once watched episodes reach the total instead of marking a phantom episode", async () => {
      prismaMock.seriesProgress.findUnique.mockResolvedValue({
        seriesImdbId: "tt-office",
        lastSeason: 8,
        lastEpisode: 21,
      });
      prismaMock.metadata.findUnique.mockResolvedValue({ tmdbId: 2316, totalEpisodes: 186 });
      prismaMock.watchEvent.findMany.mockResolvedValue(
        Array.from({ length: 186 }, (_, i) => ({ season: 1, episode: i + 1 }))
      );

      const app = await buildApp();
      const response = await app.inject({ method: "POST", url: "/series/tt-office/watch-next" });

      expect(response.statusCode).toBe(409);
      expect(prismaMock.watchEvent.create).not.toHaveBeenCalled();
    });

    it("answers with the episode it marked", async () => {
      prismaMock.seriesProgress.findUnique.mockResolvedValue({
        seriesImdbId: "tt-office",
        lastSeason: 2,
        lastEpisode: 4,
      });
      prismaMock.metadata.findUnique.mockResolvedValue({ tmdbId: 2316, totalEpisodes: null });
      nextEpisodeMock.computeNextEpisode.mockResolvedValue({ season: 2, episode: 5 });
      prismaMock.watchEvent.create.mockResolvedValue({});

      const app = await buildApp();
      const response = await app.inject({ method: "POST", url: "/series/tt-office/watch-next" });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({ season: 2, episode: 5 });
    });
  });
});

describe("GET /series/progress — season-level counts", () => {
  beforeEach(() => {
    resetMocks();
    prismaMock.seriesProgress.findMany.mockResolvedValue([
      {
        seriesImdbId: "tt-buffy",
        lastSeason: 2,
        lastEpisode: 3,
        lastWatchedAt: new Date(),
        updatedAt: new Date(),
      },
    ]);
    prismaMock.metadata.findMany.mockResolvedValue([
      {
        imdbId: "tt-buffy",
        name: "Buffy",
        poster: null,
        background: null,
        totalSeasons: 7,
        totalEpisodes: 144,
        tmdbId: 95,
      },
    ]);
    nextEpisodeMock.computeNextEpisode.mockResolvedValue({ season: 2, episode: 4 });
  });

  const watched = (episodes: { season: number; episode: number }[]) =>
    prismaMock.watchEvent.groupBy.mockResolvedValue(
      episodes.map((e) => ({ seriesImdbId: "tt-buffy", ...e, _count: { _all: 1 } }))
    );

  it("reports how much of the season in progress is watched, alongside the series total", async () => {
    watched([
      { season: 1, episode: 1 },
      { season: 1, episode: 2 },
      { season: 2, episode: 1 },
      { season: 2, episode: 2 },
      { season: 2, episode: 3 },
    ]);
    seasonsMock.getSeasonsForImdbId.mockResolvedValue([
      { seasonNumber: 1, name: "Season 1", episodeCount: 12, airYear: 1997, poster: null },
      { seasonNumber: 2, name: "Season 2", episodeCount: 22, airYear: 1998, poster: null },
    ]);

    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/series/progress" });

    const [progress] = response.json().progress;
    expect(progress.seasonWatchedEpisodes).toBe(3);
    expect(progress.seasonTotalEpisodes).toBe(22);
    // Series-wide numbers are untouched — the carousel card still shows them.
    expect(progress.watchedEpisodes).toBe(5);
    expect(progress.totalEpisodes).toBe(144);
  });

  it("looks the seasons up once per series and hands them to the next-episode math", async () => {
    // A failed TMDB lookup isn't cached, so a second call would be a second
    // failing request rather than a cache hit.
    watched([{ season: 2, episode: 3 }]);
    const seasons = [{ seasonNumber: 2, name: "Season 2", episodeCount: 22, airYear: 1998, poster: null }];
    seasonsMock.getSeasonsForImdbId.mockResolvedValue(seasons);

    const app = await buildApp();
    await app.inject({ method: "GET", url: "/series/progress" });

    expect(seasonsMock.getSeasonsForImdbId).toHaveBeenCalledTimes(1);
    expect(nextEpisodeMock.computeNextEpisode).toHaveBeenCalledWith("tt-buffy", 95, 2, 3, seasons);
  });

  it("ignores a row carrying no episode number, which is not one watched episode", async () => {
    watched([
      { season: 2, episode: 1 },
      { season: 2, episode: null as unknown as number },
    ]);
    seasonsMock.getSeasonsForImdbId.mockResolvedValue([
      { seasonNumber: 2, name: "Season 2", episodeCount: 22, airYear: 1998, poster: null },
    ]);

    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/series/progress" });

    expect(response.json().progress[0].seasonWatchedEpisodes).toBe(1);
  });

  it("leaves the season total null when TMDB has no season data", async () => {
    watched([{ season: 2, episode: 3 }]);
    seasonsMock.getSeasonsForImdbId.mockResolvedValue([]);

    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/series/progress" });

    const [progress] = response.json().progress;
    expect(progress.seasonTotalEpisodes).toBeNull();
  });

  it("counts an untouched season as zero rather than borrowing another season's count", async () => {
    // Every watched episode is in season 1; the viewer's marker sits in season 2.
    watched([
      { season: 1, episode: 1 },
      { season: 1, episode: 2 },
    ]);
    seasonsMock.getSeasonsForImdbId.mockResolvedValue([
      { seasonNumber: 2, name: "Season 2", episodeCount: 22, airYear: 1998, poster: null },
    ]);

    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: "/series/progress" });

    const [progress] = response.json().progress;
    expect(progress.seasonWatchedEpisodes).toBe(0);
  });
});

describe("manual marks carry when the watch happened", () => {
  beforeEach(() => {
    resetMocks();
    watchEventLibMock.recordWatchEvent.mockResolvedValue({ watchEvent: {}, wasCreated: true });
  });

  it("logs an episode at the time it was given", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/season/2/episode/5/watch",
      payload: { watchedAt: "2024-03-09T20:30:00.000Z" },
    });

    expect(response.statusCode).toBe(204);
    expect(watchEventLibMock.recordWatchEvent).toHaveBeenCalledWith(
      expect.objectContaining({ season: 2, episode: 5, watchedAt: new Date("2024-03-09T20:30:00.000Z"), dateUnknown: false })
    );
  });

  it("logs an episode with no body at now", async () => {
    const before = Date.now();
    const app = await buildApp();
    const response = await app.inject({ method: "POST", url: "/series/tt-office/season/2/episode/5/watch" });

    expect(response.statusCode).toBe(204);
    const call = watchEventLibMock.recordWatchEvent.mock.calls[0]![0] as { watchedAt: Date; dateUnknown: boolean };
    expect(call.watchedAt.getTime()).toBeGreaterThanOrEqual(before);
    expect(call.dateUnknown).toBe(false);
  });

  it("refuses a date it cannot read", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/season/2/episode/5/watch",
      payload: { watchedAt: "last tuesday" },
    });

    expect(response.statusCode).toBe(400);
    expect(watchEventLibMock.recordWatchEvent).not.toHaveBeenCalled();
  });

  it("marks the next episode at the time it was given", async () => {
    prismaMock.seriesProgress.findUnique.mockResolvedValue({ seriesImdbId: "tt-office", lastSeason: 2, lastEpisode: 4 });
    prismaMock.metadata.findUnique.mockResolvedValue({ tmdbId: 2316, totalEpisodes: null });
    nextEpisodeMock.computeNextEpisode.mockResolvedValue({ season: 2, episode: 5 });
    prismaMock.watchEvent.create.mockResolvedValue({});

    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/watch-next",
      payload: { watchedAt: "2022-08-01T19:00:00.000Z", dateUnknown: false },
    });

    expect(response.statusCode).toBe(200);
    expect(prismaMock.watchEvent.create).toHaveBeenCalledWith({
      data: expect.objectContaining({ season: 2, episode: 5, watchedAt: new Date("2022-08-01T19:00:00.000Z"), dateUnknown: false }),
    });
  });

  it("marks a season with each episode on its own day where given", async () => {
    prismaMock.watchEvent.findMany.mockResolvedValue([]);
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/season/1/watch-all",
      payload: {
        episodeNumbers: [1, 2],
        watchedAt: "2026-01-01T00:00:00.000Z",
        watchedAtByEpisode: { 1: "2005-03-24T12:00:00.000Z" },
      },
    });

    expect(response.statusCode).toBe(200);
    expect(watchEventLibMock.recordWatchEvent).toHaveBeenCalledWith(
      expect.objectContaining({ episode: 1, watchedAt: new Date("2005-03-24T12:00:00.000Z") })
    );
    expect(watchEventLibMock.recordWatchEvent).toHaveBeenCalledWith(
      expect.objectContaining({ episode: 2, watchedAt: new Date("2026-01-01T00:00:00.000Z") })
    );
  });

  it("refuses per-episode times it cannot read", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/season/1/watch-all",
      payload: { episodeNumbers: [1], watchedAtByEpisode: { 1: "soon" } },
    });
    expect(response.statusCode).toBe(400);
  });

  it("marks a season with an unknown date", async () => {
    prismaMock.watchEvent.findMany.mockResolvedValue([]);
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/series/tt-office/season/1/watch-all",
      payload: { episodeNumbers: [1, 2], watchedAt: "2020-01-01T12:00:00.000Z", dateUnknown: true },
    });

    expect(response.statusCode).toBe(200);
    expect(watchEventLibMock.recordWatchEvent).toHaveBeenCalledTimes(2);
    expect(watchEventLibMock.recordWatchEvent).toHaveBeenCalledWith(
      expect.objectContaining({ episode: 2, watchedAt: new Date("2020-01-01T12:00:00.000Z"), dateUnknown: true })
    );
  });
});
