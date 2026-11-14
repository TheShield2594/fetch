import { describe, expect, it, vi } from "vitest";

import { findDuplicateGroups, pickSurvivor, type DuplicateCandidate } from "./watch-duplicates.js";

// The grouping rule is pure; the module only needs a client to load.
vi.mock("./prisma.js", () => ({ prisma: {} }));


let next = 0;
const row = (overrides: Partial<Omit<DuplicateCandidate, "watchedAt">> & { watchedAt: string }): DuplicateCandidate => ({
  id: `id-${(next += 1)}`,
  type: "movie",
  imdbId: "tt0111161",
  seriesImdbId: null,
  season: null,
  episode: null,
  dateUnknown: false,
  plays: 1,
  note: null,
  traktHistoryId: null,
  ...overrides,
  watchedAt: new Date(overrides.watchedAt),
});

const ids = (groups: DuplicateCandidate[][]) => groups.map((group) => group.map((r) => r.id).sort());

describe("findDuplicateGroups", () => {
  it("groups the same evening recorded on two UTC days", () => {
    // Letterboxd's date-only diary entry against a webhook's real timestamp.
    const diary = row({ watchedAt: "2026-02-28T00:00:00Z" });
    const webhook = row({ watchedAt: "2026-03-01T05:10:00Z" });
    expect(ids(findDuplicateGroups([diary, webhook]))).toEqual([[diary.id, webhook.id].sort()]);
  });

  it("leaves plays further apart than the window alone", () => {
    const groups = findDuplicateGroups([row({ watchedAt: "2026-01-01T20:00:00Z" }), row({ watchedAt: "2026-01-05T20:00:00Z" })]);
    expect(groups).toEqual([]);
  });

  it("measures from a group's first play, so nightly watches don't chain", () => {
    const nights = ["2026-01-01T20:00:00Z", "2026-01-02T20:00:00Z", "2026-01-03T20:00:00Z", "2026-01-04T20:00:00Z"].map(
      (watchedAt) => row({ watchedAt })
    );
    const groups = findDuplicateGroups(nights);
    expect(groups.map((group) => group.length)).toEqual([2, 2]);
  });

  it("never groups different titles or different episodes", () => {
    const groups = findDuplicateGroups([
      row({ watchedAt: "2026-01-01T20:00:00Z" }),
      row({ watchedAt: "2026-01-01T21:00:00Z", imdbId: "tt0068646" }),
      row({ watchedAt: "2026-01-01T20:00:00Z", type: "episode", imdbId: "tt1", seriesImdbId: "tt1", season: 1, episode: 1 }),
      row({ watchedAt: "2026-01-01T21:00:00Z", type: "episode", imdbId: "tt1", seriesImdbId: "tt1", season: 1, episode: 2 }),
    ]);
    expect(groups).toEqual([]);
  });

  it("puts an undated mark with the title's dated play", () => {
    const dated = row({ watchedAt: "2025-06-01T20:00:00Z" });
    const undated = row({ watchedAt: "2026-03-01T10:00:00Z", dateUnknown: true });
    const groups = findDuplicateGroups([dated, undated]);
    expect(ids(groups)).toEqual([[dated.id, undated.id].sort()]);
    expect(pickSurvivor(groups[0]!).id).toBe(dated.id);
  });

  it("keeps two different Trakt plays apart", () => {
    const groups = findDuplicateGroups([
      row({ watchedAt: "2026-01-01T20:00:00Z", traktHistoryId: 1n }),
      row({ watchedAt: "2026-01-01T22:00:00Z", traktHistoryId: 2n }),
    ]);
    expect(groups).toEqual([]);
  });

  it("groups a Trakt play with another writer's copy and keeps the Trakt row", () => {
    const local = row({ watchedAt: "2026-01-01T19:00:00Z" });
    const trakt = row({ watchedAt: "2026-01-01T20:00:00Z", traktHistoryId: 7n });
    const groups = findDuplicateGroups([local, trakt]);
    expect(groups).toHaveLength(1);
    expect(pickSurvivor(groups[0]!).id).toBe(trakt.id);
  });
});
