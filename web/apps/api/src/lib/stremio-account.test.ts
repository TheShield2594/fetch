import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";
import {
  decodeWatchedVideoIds,
  parseStremioLibrary,
  planStremioImport,
  pollStremioLink,
  resetStremioLinks,
  startStremioLink,
  StremioError,
  type StremioImportDeps,
  type StremioLibraryItem,
  type StremioVideo,
} from "./stremio-account.js";

// Fixtures from stremio-core's own watched_bitfield tests.
const FIRST_FIVE_OF_NINE = "tt2934286:1:5:5:eJyTZwAAAEAAIA==";
const ALL_TWENTY_FOUR = "tt7767422:3:8:24:eJz7//8/AAX9Av4=";

const episode = (series: string, season: number, number: number, released: number | null = null): StremioVideo => ({
  id: `${series}:${season}:${number}`,
  season,
  episode: number,
  released,
});

const item = (overrides: Partial<StremioLibraryItem>): StremioLibraryItem => ({
  id: "tt0000001",
  type: "movie",
  name: "A title",
  removed: false,
  temp: false,
  lastWatched: null,
  timeOffsetMs: 0,
  durationMs: 0,
  timesWatched: 0,
  flaggedWatched: 0,
  videoId: null,
  watched: null,
  ...overrides,
});

describe("decodeWatchedVideoIds", () => {
  it("decodes Stremio's own bitfield", () => {
    const videos = Array.from({ length: 9 }, (_, i) => episode("tt2934286", 1, i + 1));
    expect([...decodeWatchedVideoIds(FIRST_FIVE_OF_NINE, videos)!]).toEqual(
      [1, 2, 3, 4, 5].map((n) => `tt2934286:1:${n}`)
    );
  });

  it("decodes every episode across seasons", () => {
    const videos = [1, 2, 3].flatMap((s) => Array.from({ length: 8 }, (_, i) => episode("tt7767422", s, i + 1)));
    expect(decodeWatchedVideoIds(ALL_TWENTY_FOUR, videos)!.size).toBe(24);
  });

  it("realigns on the anchor when episodes were added before it, whatever the list order", () => {
    const videos = [episode("tt2934286", 0, 1), ...Array.from({ length: 9 }, (_, i) => episode("tt2934286", 1, i + 1))].reverse();
    expect([...decodeWatchedVideoIds(FIRST_FIVE_OF_NINE, videos)!].sort()).toEqual(
      [1, 2, 3, 4, 5].map((n) => `tt2934286:1:${n}`)
    );
  });

  it("can't place bits without the anchor", () => {
    expect(decodeWatchedVideoIds(FIRST_FIVE_OF_NINE, [episode("tt2934286", 1, 9)])).toBeNull();
    expect(decodeWatchedVideoIds("undefined:1:eJwDAAAAAAE=", [])).toBeNull();
    expect(decodeWatchedVideoIds("junk", [])).toBeNull();
  });
});

describe("parseStremioLibrary", () => {
  it("reads items and drops ones without an id", () => {
    const items = parseStremioLibrary({
      result: [
        {
          _id: "tt0111161",
          type: "movie",
          name: "A Film",
          removed: false,
          temp: false,
          state: { lastWatched: "2024-05-01T20:00:00.000Z", timesWatched: 1, timeOffset: 0, duration: 0, video_id: "tt0111161" },
        },
        { _id: "", type: "movie", state: {} },
      ],
    });
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ id: "tt0111161", timesWatched: 1, videoId: "tt0111161" });
    expect(items[0]!.lastWatched?.toISOString()).toBe("2024-05-01T20:00:00.000Z");
  });

  it("throws on Stremio's error object", () => {
    expect(() => parseStremioLibrary({ error: { code: 1, message: "Session does not exist" } })).toThrow(StremioError);
  });
});

describe("planStremioImport", () => {
  const now = new Date("2026-01-01T00:00:00.000Z");
  const nineEpisodes = Array.from({ length: 9 }, (_, i) => episode("tt2934286", 1, i + 1));

  it("dates the last video played and leaves the rest undated", async () => {
    const plan = await planStremioImport(
      [
        item({ id: "tt1", timesWatched: 1, lastWatched: new Date("2024-01-01"), videoId: "tt1" }),
        item({ id: "tt2934286", type: "series", watched: FIRST_FIVE_OF_NINE, videoId: "tt2934286:1:5", lastWatched: new Date("2024-02-01") }),
      ],
      async () => nineEpisodes,
      now
    );
    expect(plan.watches[0]).toMatchObject({ type: "movie", imdbId: "tt1", watchedAt: new Date("2024-01-01") });
    expect(plan.watches[0]).not.toHaveProperty("undated");
    const episodes = plan.watches.filter((w) => w.type === "episode");
    expect(episodes).toHaveLength(5);
    expect(episodes.find((w) => w.type === "episode" && w.episode === 5)).not.toHaveProperty("undated");
    expect(episodes.filter((w) => w.undated)).toHaveLength(4);
  });

  it("keeps the resume point of something being watched again, its earlier finish undated", async () => {
    const plan = await planStremioImport(
      [item({ id: "tt5", timesWatched: 1, lastWatched: new Date("2024-03-01"), timeOffsetMs: 3_000_000, durationMs: 6_000_000, videoId: "tt5" })],
      async () => null,
      now
    );
    expect(plan.watches[0]).toMatchObject({ undated: true });
    expect(plan.playback).toEqual([
      {
        target: { type: "movie", imdbId: "tt5" },
        progress: 50,
        positionSeconds: 3000,
        durationSeconds: 6000,
        pausedAt: new Date("2024-03-01"),
      },
    ]);
  });

  it("files a series resume point under its episode", async () => {
    const plan = await planStremioImport(
      [item({ id: "tt2934286", type: "series", videoId: "tt2934286:1:6", timeOffsetMs: 600_000, durationMs: 2_400_000 })],
      async () => nineEpisodes,
      now
    );
    expect(plan.playback[0]!.target).toEqual({ type: "episode", imdbId: "tt2934286", seriesImdbId: "tt2934286", season: 1, episode: 6 });
  });

  it("skips titles Fetch can't hold and resume points Stremio no longer shows", async () => {
    const plan = await planStremioImport(
      [
        item({ id: "kitsu:1", type: "series", watched: "kitsu:1:3:3:eJyTZwAAAEAAIA==" }),
        item({ id: "tt9", removed: true, timeOffsetMs: 1000, durationMs: 10_000 }),
        item({ id: "yt_id", type: "channel", timesWatched: 3 }),
      ],
      async () => null,
      now
    );
    expect(plan.skipped).toBe(1);
    expect(plan.playback).toHaveLength(0);
  });

  it("without an episode list, imports the anchor episode and counts the series", async () => {
    const plan = await planStremioImport(
      [item({ id: "tt2934286", type: "series", watched: FIRST_FIVE_OF_NINE, videoId: "tt2934286:1:5", lastWatched: new Date("2024-02-01") })],
      async () => null,
      now
    );
    expect(plan.watches).toEqual([
      expect.objectContaining({ type: "episode", seriesImdbId: "tt2934286", season: 1, episode: 5, watchedAt: new Date("2024-02-01") }),
    ]);
    expect(plan.unreadable).toBe(1);
  });
});

describe("the link flow", () => {
  const log = { warn: vi.fn() } as unknown as FastifyBaseLogger;
  let clock: number;
  let approved: boolean;
  let deps: StremioImportDeps;

  beforeEach(() => {
    resetStremioLinks();
    clock = 1_000_000;
    approved = false;
    deps = {
      createLinkCode: vi.fn(async () => ({ code: "ABCD", link: "https://link.stremio.com/ABCD" })),
      readLinkCode: vi.fn(async () => (approved ? "auth-key" : null)),
      fetchLibrary: vi.fn(async () => [item({ id: "tt1", timesWatched: 1 })]),
      logout: vi.fn(async () => undefined),
      videosFor: vi.fn(async () => null),
      write: vi.fn(async (_profile, plan) => ({ movies: plan.watches.length, episodes: 0, resumePoints: plan.playback.length })),
      now: () => clock,
    };
  });

  it("waits for approval, imports into the profile that started it, and signs out", async () => {
    expect(await startStremioLink("profile-a", deps)).toMatchObject({ state: "waiting", code: "ABCD" });
    expect(await pollStremioLink("profile-a", deps, log)).toMatchObject({ state: "waiting" });

    approved = true;
    expect(await pollStremioLink("profile-a", deps, log)).toEqual({ state: "importing" });
    await vi.waitFor(async () => expect((await pollStremioLink("profile-a", deps, log)).state).toBe("done"));

    expect(await pollStremioLink("profile-a", deps, log)).toEqual({
      state: "done",
      summary: { movies: 1, episodes: 0, resumePoints: 0, skipped: 0, unreadable: 0 },
    });
    expect(deps.write).toHaveBeenCalledWith("profile-a", expect.anything(), log);
    expect(deps.logout).toHaveBeenCalledWith("auth-key");
    // Another profile has nothing pending.
    expect(await pollStremioLink("profile-b", deps, log)).toEqual({ state: "idle" });
  });

  it("gives up on a code nobody approved", async () => {
    await startStremioLink("profile-a", deps);
    clock += 11 * 60_000;
    expect(await pollStremioLink("profile-a", deps, log)).toEqual({ state: "expired" });
    expect(deps.readLinkCode).not.toHaveBeenCalled();
  });

  it("reports a failed import and still signs out", async () => {
    deps.fetchLibrary = vi.fn(async () => {
      throw new StremioError("Session does not exist");
    });
    approved = true;
    await startStremioLink("profile-a", deps);
    await pollStremioLink("profile-a", deps, log);
    await vi.waitFor(async () => expect((await pollStremioLink("profile-a", deps, log)).state).toBe("failed"));
    expect(deps.logout).toHaveBeenCalledWith("auth-key");
  });
});
