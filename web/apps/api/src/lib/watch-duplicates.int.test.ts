import { describe, expect, it } from "vitest";
import { prisma } from "./prisma.js";
import { createProfile, silentLogger } from "./test-fixtures/int-db.js";
import { recordWatchEvent } from "./watch-event.js";
import {
  DuplicateMergeError,
  listDuplicateGroups,
  MAX_DUPLICATE_GROUP_SIZE,
  mergeDuplicateGroups,
} from "./watch-duplicates.js";

const SHAWSHANK = "tt0111161";
const SOPRANOS = "tt0141842";

const movie = (profileId: string, watchedAt: string, extra: Record<string, unknown> = {}) =>
  prisma.watchEvent.create({ data: { profileId, type: "movie", imdbId: SHAWSHANK, watchedAt: new Date(watchedAt), ...extra } });

const episode = (profileId: string, watchedAt: string, number: number) =>
  prisma.watchEvent.create({
    data: {
      profileId,
      type: "episode",
      imdbId: SOPRANOS,
      seriesImdbId: SOPRANOS,
      season: 1,
      episode: number,
      watchedAt: new Date(watchedAt),
    },
  });

describe("watch duplicates", () => {
  it("lists a cross-day copy with its title and merges it into one watch", async () => {
    const profile = await createProfile();
    await prisma.item.create({ data: { type: "movie", imdbId: SHAWSHANK, title: "The Shawshank Redemption" } });
    const diary = await movie(profile.id, "2026-02-28T00:00:00Z", { note: "again" });
    const webhook = await movie(profile.id, "2026-03-01T05:10:00Z", { plays: 2, traktHistoryId: 99n });

    const { groups } = await listDuplicateGroups(profile.id);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ title: "The Shawshank Redemption", keepId: webhook.id });

    expect(await mergeDuplicateGroups(profile.id, [[diary.id, webhook.id]])).toEqual({ merged: 1, removed: 1 });
    const rows = await prisma.watchEvent.findMany({ where: { profileId: profile.id } });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ id: webhook.id, plays: 2, note: "again", traktHistoryId: 99n });
    expect((await listDuplicateGroups(profile.id)).groups).toEqual([]);
  });

  it("moves a Trakt id from the removed row onto the one kept", async () => {
    const profile = await createProfile();
    const dated = await movie(profile.id, "2026-01-01T20:00:00Z");
    const undated = await movie(profile.id, "2026-03-01T10:00:00Z", { dateUnknown: true, traktHistoryId: 5n });

    await mergeDuplicateGroups(profile.id, [[dated.id, undated.id]]);
    const kept = await prisma.watchEvent.findUniqueOrThrow({ where: { id: dated.id } });
    expect(kept.traktHistoryId).toBe(5n);
  });

  it("points series progress at the latest episode left", async () => {
    const profile = await createProfile();
    const early = await episode(profile.id, "2026-01-01T20:00:00Z", 3);
    const copy = await episode(profile.id, "2026-01-02T20:00:00Z", 3);
    await prisma.seriesProgress.create({
      data: {
        profileId: profile.id,
        seriesImdbId: SOPRANOS,
        lastSeason: 1,
        lastEpisode: 3,
        lastWatchedAt: copy.watchedAt,
        updatedAt: copy.watchedAt,
      },
    });

    await mergeDuplicateGroups(profile.id, [[early.id, copy.id]]);
    const progress = await prisma.seriesProgress.findUniqueOrThrow({
      where: { profileId_seriesImdbId: { profileId: profile.id, seriesImdbId: SOPRANOS } },
    });
    expect(progress.lastWatchedAt.toISOString()).toBe(early.watchedAt.toISOString());
  });

  it("refuses ids from another profile, and merges nothing", async () => {
    const mine = await createProfile("Mine");
    const theirs = await createProfile("Theirs");
    const a = await movie(mine.id, "2026-01-01T20:00:00Z");
    const b = await movie(theirs.id, "2026-01-02T20:00:00Z");

    await expect(mergeDuplicateGroups(mine.id, [[a.id, b.id]])).rejects.toBeInstanceOf(DuplicateMergeError);
    expect(await prisma.watchEvent.count()).toBe(2);
  });

  it("refuses a group spanning two titles", async () => {
    const profile = await createProfile();
    const a = await movie(profile.id, "2026-01-01T20:00:00Z");
    const b = await episode(profile.id, "2026-01-01T21:00:00Z", 1);
    await expect(mergeDuplicateGroups(profile.id, [[a.id, b.id]])).rejects.toBeInstanceOf(DuplicateMergeError);
  });

  it("says there is more to find when a title has more copies than one group holds", async () => {
    const profile = await createProfile();
    await movie(profile.id, "2025-01-01T20:00:00Z");
    for (let i = 0; i < MAX_DUPLICATE_GROUP_SIZE + 2; i += 1) {
      await movie(profile.id, `2026-0${1 + (i % 9)}-${String(1 + Math.floor(i / 9)).padStart(2, "0")}T10:00:00Z`, {
        dateUnknown: true,
      });
    }
    const { groups, truncated } = await listDuplicateGroups(profile.id);
    expect(groups[0]?.events).toHaveLength(MAX_DUPLICATE_GROUP_SIZE);
    expect(truncated).toBe(true);
  });

  it("doesn't say there is more when a full group's title has a watch in another group", async () => {
    const profile = await createProfile();
    await movie(profile.id, "2025-01-01T20:00:00Z");
    for (let i = 0; i < MAX_DUPLICATE_GROUP_SIZE - 1; i += 1) {
      await movie(profile.id, `2026-0${1 + (i % 9)}-${String(1 + Math.floor(i / 9)).padStart(2, "0")}T10:00:00Z`, {
        dateUnknown: true,
      });
    }
    // A dated watch far from the first: its own group of one, so not offered.
    await movie(profile.id, "2024-01-01T20:00:00Z");
    const { groups, truncated } = await listDuplicateGroups(profile.id);
    expect(groups[0]?.events).toHaveLength(MAX_DUPLICATE_GROUP_SIZE);
    expect(truncated).toBe(false);
  });

  it("keeps a play recorded on a row a merge removes while the play waits on its lock", async () => {
    const profile = await createProfile();
    const doomed = await movie(profile.id, "2026-05-02T20:00:00Z");

    // What a merge does to a row it folds away: lock it, then delete it. The
    // transaction is held open so the play below reads the row, then blocks on it.
    let release = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    const merge = prisma.$transaction(async (tx) => {
      await tx.$executeRaw`SELECT id FROM "WatchEvent" WHERE id = ${doomed.id}::uuid FOR UPDATE`;
      await tx.watchEvent.delete({ where: { id: doomed.id } });
      await held;
    });
    await new Promise((resolve) => setTimeout(resolve, 100));

    const play = recordWatchEvent({
      type: "movie",
      imdbId: SHAWSHANK,
      watchedAt: new Date("2026-05-02T22:00:00Z"),
      source: "test",
      profileId: profile.id,
      log: silentLogger,
    });
    await new Promise((resolve) => setTimeout(resolve, 300));
    release();
    await merge;
    await play;

    const rows = await prisma.watchEvent.findMany({ where: { profileId: profile.id } });
    expect(rows).toHaveLength(1);
    expect(rows[0]?.plays).toBe(1);
  });
});
