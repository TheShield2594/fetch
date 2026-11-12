import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = {
  listItem: { findMany: vi.fn() },
  metadata: { findMany: vi.fn() },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const getUpcomingEpisodes = vi.fn();
const getNextEpisodes = vi.fn();
vi.mock("./upcoming-episodes.js", () => ({
  getUpcomingEpisodes: (...a: unknown[]) => getUpcomingEpisodes(...a),
  getNextEpisodes: (...a: unknown[]) => getNextEpisodes(...a),
}));

const { getCalendar } = await import("./calendar.js");

const PROFILE = "11111111-1111-4111-8111-111111111111";

const daysFromToday = (days: number): string => {
  const date = new Date();
  date.setUTCHours(0, 0, 0, 0);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
};

const episode = (imdbId: string, name: string, number: number, airDate: string) => ({
  kind: number === 1 ? "premiere" : "episode",
  seriesImdbId: imdbId,
  seriesName: name,
  poster: null,
  still: "https://image.tmdb.org/t/p/w500/still.jpg",
  backdrop: null,
  season: 2,
  episode: number,
  episodeName: `Episode ${number}`,
  airDate,
  overview: null,
});

beforeEach(() => {
  vi.clearAllMocks();
  getUpcomingEpisodes.mockResolvedValue([]);
  getNextEpisodes.mockResolvedValue([]);
  prismaMock.listItem.findMany.mockResolvedValue([]);
  prismaMock.metadata.findMany.mockResolvedValue([]);
});

describe("getCalendar", () => {
  it("puts a watchlisted film on its release date", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt1" }]);
    prismaMock.metadata.findMany.mockResolvedValue([
      {
        imdbId: "tt1",
        name: "Arrival",
        poster: "/p.jpg",
        background: "/b.jpg",
        description: "Shapes.",
        releaseDate: daysFromToday(5),
      },
    ]);

    const [film] = await getCalendar(PROFILE, 30);

    expect(film).toEqual({
      kind: "movie",
      seriesImdbId: "tt1",
      seriesName: "Arrival",
      poster: "/p.jpg",
      still: null,
      backdrop: "/b.jpg",
      season: null,
      episode: null,
      episodeName: null,
      airDate: daysFromToday(5),
      overview: "Shapes.",
    });
    // The window is asked of the database, not filtered afterwards.
    expect(prismaMock.metadata.findMany.mock.calls[0]?.[0]).toMatchObject({
      where: { releaseDate: { gte: daysFromToday(0), lte: daysFromToday(30) } },
    });
  });

  it("drops a release date that is not a plain YYYY-MM-DD day", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt1" }]);
    prismaMock.metadata.findMany.mockResolvedValue([
      { imdbId: "tt1", name: "Odd", poster: null, background: null, description: null, releaseDate: `${daysFromToday(2)}T00:00` },
    ]);

    expect(await getCalendar(PROFILE, 30)).toEqual([]);
  });

  it("adds the premiere of a watchlisted series nobody has started, and nothing mid-season", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([
      { type: "series", imdbId: "ttNew" },
      { type: "series", imdbId: "ttMid" },
    ]);
    getNextEpisodes.mockResolvedValue([
      episode("ttNew", "New Show", 1, daysFromToday(3)),
      episode("ttMid", "Mid Show", 6, daysFromToday(4)),
    ]);

    const entries = await getCalendar(PROFILE, 30);

    expect(entries.map((e) => [e.seriesName, e.kind])).toEqual([["New Show", "premiere"]]);
  });

  it("does not ask twice about a watchlisted series that is already in progress", async () => {
    getUpcomingEpisodes.mockResolvedValue([episode("ttA", "Andor", 4, daysFromToday(1))]);
    prismaMock.listItem.findMany.mockResolvedValue([
      { type: "series", imdbId: "ttA" },
      { type: "series", imdbId: "ttB" },
    ]);

    await getCalendar(PROFILE, 30, true);

    expect(getNextEpisodes).toHaveBeenCalledWith(["ttB"], 30, true);
  });

  it("orders by day, then premieres, episodes and films within a day", async () => {
    const day = daysFromToday(2);
    getUpcomingEpisodes.mockResolvedValue([
      episode("ttE", "Episode Show", 3, day),
      episode("ttP", "Premiere Show", 1, day),
      episode("ttL", "Later Show", 2, daysFromToday(9)),
    ]);
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "ttM" }]);
    prismaMock.metadata.findMany.mockResolvedValue([
      { imdbId: "ttM", name: "A Film", poster: null, background: null, description: null, releaseDate: day },
    ]);

    const entries = await getCalendar(PROFILE, 30);

    expect(entries.map((e) => e.seriesName)).toEqual(["Premiere Show", "Episode Show", "A Film", "Later Show"]);
  });
});
