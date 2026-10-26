import { describe, expect, it } from "vitest";
import { prisma } from "./prisma.js";
import { createProfile } from "./test-fixtures/int-db.js";
import { importTrackerItems } from "./tracker-import.js";

describe("importTrackerItems", () => {
  it("writes plays and watchlist entries once, however often a sync brings them", async () => {
    const profile = await createProfile("Sam");
    const watches = [
      { type: "movie" as const, imdbId: "tt0113277", watchedAt: new Date("2026-01-02T20:00:00Z"), title: "Heat" },
      { type: "episode" as const, seriesImdbId: "tt5753856", season: 1, episode: 1, watchedAt: new Date("2026-01-03T20:00:00Z") },
      { type: "episode" as const, seriesImdbId: "tt5753856", season: 1, episode: 2, watchedAt: new Date("2026-01-04T20:00:00Z") },
    ];
    const watchlist = [{ type: "series" as const, imdbId: "tt0903747" }];

    const first = await importTrackerItems(profile.id, watches, watchlist);
    const again = await importTrackerItems(profile.id, watches, watchlist);

    expect(first).toEqual({ movies: 1, episodes: 2, watchlist: 1, skipped: 0 });
    expect(again).toEqual({ movies: 0, episodes: 0, watchlist: 0, skipped: 0 });
    expect(await prisma.watchEvent.count({ where: { profileId: profile.id } })).toBe(3);
    const progress = await prisma.seriesProgress.findFirstOrThrow({ where: { profileId: profile.id, seriesImdbId: "tt5753856" } });
    expect(progress).toMatchObject({ lastSeason: 1, lastEpisode: 2 });
  });

  it("joins a play another source already recorded that day rather than adding one", async () => {
    const profile = await createProfile("Sam");
    await prisma.watchEvent.create({
      data: { type: "movie", imdbId: "tt0113277", watchedAt: new Date("2026-01-02T08:00:00Z"), profileId: profile.id },
    });

    const summary = await importTrackerItems(profile.id, [
      { type: "movie", imdbId: "tt0113277", watchedAt: new Date("2026-01-02T20:00:00Z") },
    ], []);

    expect(summary.movies).toBe(0);
    expect(await prisma.watchEvent.count({ where: { profileId: profile.id } })).toBe(1);
  });

  it("skips ids that aren't IMDb ids", async () => {
    const profile = await createProfile("Sam");
    const summary = await importTrackerItems(profile.id, [{ type: "movie", imdbId: "simkl:123", watchedAt: new Date() }], [
      { type: "movie", imdbId: "nope" },
    ]);
    expect(summary).toMatchObject({ movies: 0, watchlist: 0, skipped: 2 });
  });
});
