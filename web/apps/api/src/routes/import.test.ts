import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";

// These cases are all rejected on the row count before anything is written, so
// nothing here needs a query to answer — but `./import.js` pulls in `./export.js`,
// which imports the real client, and that now refuses to load without a
// DATABASE_URL rather than quietly building a pool aimed at a local socket.
vi.mock("../lib/prisma.js", () => ({ prisma: {} }));

vi.mock("../lib/profile.js", () => ({
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));

const tmdbMock = { search: vi.fn() };
vi.mock("../lib/tmdb-client.js", () => ({ getTmdb: async () => tmdbMock }));

const batchMocks = {
  batchUpsertWatchEvents: vi.fn(),
  batchUpsertRatings: vi.fn(),
};
vi.mock("../lib/batch-watch-events.js", () => ({
  batchUpsertWatchEvents: (...args: unknown[]) => batchMocks.batchUpsertWatchEvents(...args),
}));
vi.mock("../lib/batch-ratings.js", () => ({
  batchUpsertRatings: (...args: unknown[]) => batchMocks.batchUpsertRatings(...args),
}));

const trackerMocks = {
  importTrackerItems: vi.fn(),
  addTitlesToNamedList: vi.fn(),
};
vi.mock("../lib/tracker-import.js", () => ({
  importTrackerItems: (...args: unknown[]) => trackerMocks.importTrackerItems(...args),
}));
vi.mock("../lib/trakt-import.js", () => ({
  addTitlesToNamedList: (...args: unknown[]) => trackerMocks.addTitlesToNamedList(...args),
}));

/** Big enough to hold the row-cap payloads; production sets this from MAX_BODY_SIZE_MB. */
const buildApp = (): Promise<FastifyInstance> =>
  buildRouteApp(() => import("./import.js"), { bodyLimit: 256 * 1024 * 1024 });

const csvWithRows = (header: string, row: string, count: number) =>
  [header, ...Array.from({ length: count }, () => row)].join("\n");

describe("POST /import/external — row caps", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    tmdbMock.search.mockResolvedValue([]);
    batchMocks.batchUpsertWatchEvents.mockResolvedValue(0);
    batchMocks.batchUpsertRatings.mockResolvedValue(0);
  });

  it("rejects a Letterboxd export past the TMDB lookup cap without searching TMDB", async () => {
    const app = await buildApp();
    const csv = csvWithRows("Name,Year,Rating,Watched Date", "A Film,2024,4,2024-01-01", 10_001);

    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "letterboxd-diary", csv },
    });

    expect(response.statusCode).toBe(413);
    expect(response.json().code).toBe("too_many_rows");
    expect(response.json().error).toContain("10,000");
    expect(tmdbMock.search).not.toHaveBeenCalled();
  });

  it("accepts an id-carrying format at a row count the Letterboxd cap would reject", async () => {
    const app = await buildApp();
    const csv = csvWithRows("Const,Your Rating,Date Rated,Title Type", "tt1,8,2024-01-01,movie", 10_001);

    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "imdb-ratings", csv },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json().summary.ratingsImported).toBe(10_001);
  });

  it("still rejects an id-carrying format past the global import cap", async () => {
    const app = await buildApp();
    const csv = csvWithRows("Const,Your Rating,Date Rated,Title Type", "tt1,8,2024-01-01,movie", 200_001);

    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "imdb-ratings", csv },
    });

    expect(response.statusCode).toBe(413);
    expect(response.json().error).toContain("200,000");
    expect(batchMocks.batchUpsertRatings).not.toHaveBeenCalled();
  });
});

describe("POST /import/external — lists and bad files", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    trackerMocks.importTrackerItems.mockImplementation(async (_p: string, watches: unknown[], watchlist: unknown[]) => ({
      movies: watches.filter((w) => (w as { type: string }).type === "movie").length,
      episodes: watches.filter((w) => (w as { type: string }).type === "episode").length,
      watchlist: watchlist.length,
      skipped: 0,
    }));
    trackerMocks.addTitlesToNamedList.mockImplementation(async (_p: string, _n: string, entries: unknown[]) => entries.length);
  });

  it("answers a file that isn't the named export with a 400, not a 500", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "letterboxd-watchlist", csv: "Const,Title\ntt1,A" },
    });
    expect(response.statusCode).toBe(400);
    expect(response.json().code).toBe("bad_format");
  });

  it("needs a list name for an IMDb list, and fills that list", async () => {
    const app = await buildApp();
    const csv = "Position,Const,Title,Title Type\n1,tt0111161,A Film,Movie\n2,tt0903747,A Show,TV Series";

    const missing = await app.inject({ method: "POST", url: "/import/external", payload: { format: "imdb-list", csv } });
    expect(missing.statusCode).toBe(400);

    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "imdb-list", csv, listName: " Favourites " },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().summary.listed).toBe(2);
    expect(trackerMocks.addTitlesToNamedList).toHaveBeenCalledWith(PROFILE_ID, "Favourites", [
      { type: "movie", imdbId: "tt0111161" },
      { type: "series", imdbId: "tt0903747" },
    ]);
  });

  it("puts an IMDb watchlist on the watchlist", async () => {
    const app = await buildApp();
    const response = await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "imdb-watchlist", csv: "Position,Const,Title Type\n1,tt0111161,Movie" },
    });
    expect(response.json().summary.listed).toBe(1);
    expect(trackerMocks.importTrackerItems.mock.calls[0]![2]).toEqual([{ type: "movie", imdbId: "tt0111161" }]);
  });

  it("adds Letterboxd watched films only where there is no play yet", async () => {
    tmdbMock.search.mockResolvedValue([{ imdbId: "tt0087884", year: 1984 }]);
    const app = await buildApp();
    await app.inject({
      method: "POST",
      url: "/import/external",
      payload: { format: "letterboxd-watched", csv: "Date,Name,Year,Letterboxd URI\n2023-04-01,Paris Texas,1984,x" },
    });
    const [watch] = trackerMocks.importTrackerItems.mock.calls[0]![1] as { undated?: boolean; imdbId: string }[];
    expect(watch).toMatchObject({ imdbId: "tt0087884", undated: true });
  });
});
