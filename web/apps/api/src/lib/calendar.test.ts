import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = {
  seriesProgress: { findMany: vi.fn() },
  listItem: { findMany: vi.fn() },
  metadata: { findMany: vi.fn() },
  watchEvent: { findMany: vi.fn() },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const getTmdb = vi.fn();
vi.mock("./tmdb-client.js", () => ({ getTmdb: () => getTmdb() }));

const getCachedShowDetails = vi.fn();
vi.mock("./upcoming-episodes.js", () => ({
  getCachedShowDetails: (...a: unknown[]) => getCachedShowDetails(...a),
}));

const loadEpisodes = vi.fn();
vi.mock("./detail.js", () => ({ loadEpisodes: (...a: unknown[]) => loadEpisodes(...a) }));

const isTraktConnected = vi.fn();
const fetchMyShowsCalendar = vi.fn();
vi.mock("./trakt-client.js", () => ({
  isTraktConnected: (...a: unknown[]) => isTraktConnected(...a),
  getTraktClient: async () => ({ fetchMyShowsCalendar: (...a: unknown[]) => fetchMyShowsCalendar(...a) }),
}));

const { getCalendar } = await import("./calendar.js");

// A fresh profile per test: the Trakt times are cached per profile and window.
let profile = 0;
const nextProfile = () => `profile-${++profile}`;

const utcDay = (days: number): Date => {
  const date = new Date();
  date.setUTCHours(0, 0, 0, 0);
  date.setUTCDate(date.getUTCDate() + days);
  return date;
};
const day = (days: number): string => utcDay(days).toISOString().slice(0, 10);
const window30 = () => ({ start: utcDay(0), days: 30 });

const series = (imdbId: string, name: string, status: string | null = "Returning Series") => ({
  imdbId,
  tmdbId: imdbId.length,
  name,
  poster: `/${imdbId}.jpg`,
  background: `/${imdbId}-bg.jpg`,
  status,
});

const tmdbEpisode = (episodeNumber: number, airDate: string | null) => ({
  episodeNumber,
  name: `Episode name ${episodeNumber}`,
  airDate,
  still: `https://img/${episodeNumber}.jpg`,
  runtime: 45,
  overview: `Synopsis ${episodeNumber}`,
});

beforeEach(() => {
  vi.clearAllMocks();
  getTmdb.mockResolvedValue({});
  prismaMock.seriesProgress.findMany.mockResolvedValue([]);
  prismaMock.listItem.findMany.mockResolvedValue([]);
  prismaMock.metadata.findMany.mockResolvedValue([]);
  prismaMock.watchEvent.findMany.mockResolvedValue([]);
  getCachedShowDetails.mockResolvedValue({
    nextEpisodeToAir: { season_number: 2, episode_number: 3 },
    lastEpisodeToAir: { season_number: 2, episode_number: 2 },
  });
  loadEpisodes.mockResolvedValue([]);
  isTraktConnected.mockResolvedValue(false);
  fetchMyShowsCalendar.mockResolvedValue([]);
});

/** One series in progress, with season 2 as given. */
function followed(episodes: ReturnType<typeof tmdbEpisode>[], status: string | null = "Returning Series") {
  prismaMock.seriesProgress.findMany.mockResolvedValue([{ seriesImdbId: "ttA" }]);
  prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
    where.type === "series" ? [series("ttA", "Andor", status)] : []
  );
  loadEpisodes.mockImplementation(async (_id: string, season: number) => (season === 2 ? episodes : []));
}

describe("getCalendar", () => {
  it("lists every episode of a series in progress inside the window, not only the next one", async () => {
    followed([tmdbEpisode(2, day(-6)), tmdbEpisode(3, day(1)), tmdbEpisode(4, day(8)), tmdbEpisode(5, day(40))]);

    const entries = await getCalendar(nextProfile(), window30());

    expect(entries.map((e) => [e.episode, e.airDate])).toEqual([
      [3, day(1)],
      [4, day(8)],
    ]);
    expect(entries[0]).toMatchObject({
      kind: "episode",
      seriesName: "Andor",
      still: "https://img/3.jpg",
      backdrop: "/ttA-bg.jpg",
      episodeName: "Episode name 3",
      airsAt: null,
      watched: false,
    });
  });

  it("reaches back into a past window, across into the season before when it has to", async () => {
    prismaMock.seriesProgress.findMany.mockResolvedValue([{ seriesImdbId: "ttA" }]);
    prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
      where.type === "series" ? [series("ttA", "Andor")] : []
    );
    loadEpisodes.mockImplementation(async (_id: string, season: number) =>
      season === 2 ? [tmdbEpisode(1, day(-20)), tmdbEpisode(2, day(-13))] : [tmdbEpisode(12, day(-50))]
    );

    const entries = await getCalendar(nextProfile(), { start: utcDay(-60), days: 45 });

    expect(entries.map((e) => [e.season, e.episode])).toEqual([
      [1, 12],
      [2, 1],
    ]);
    expect(loadEpisodes.mock.calls.map((c) => c[1])).toEqual([2, 1]);
  });

  it("says which have been watched, and spares only those from spoiler protection", async () => {
    followed([tmdbEpisode(2, day(-6)), tmdbEpisode(3, day(-1))]);
    prismaMock.watchEvent.findMany.mockResolvedValue([
      { type: "episode", imdbId: "ttA-2", seriesImdbId: "ttA", season: 2, episode: 2 },
    ]);

    const entries = await getCalendar(nextProfile(), { start: utcDay(-10), days: 20 }, true);

    expect(entries[0]).toMatchObject({ episode: 2, watched: true, episodeName: "Episode name 2", still: "https://img/2.jpg" });
    expect(entries[1]).toMatchObject({ episode: 3, watched: false, episodeName: "Episode 3", still: null, overview: null });
  });

  it("puts each episode's air time on it when the profile has Trakt", async () => {
    followed([tmdbEpisode(3, day(1))]);
    isTraktConnected.mockResolvedValue(true);
    const airsAt = `${day(2)}T01:00:00.000Z`;
    fetchMyShowsCalendar.mockResolvedValue([
      { first_aired: airsAt, episode: { season: 2, number: 3 }, show: { ids: { imdb: "ttA" } } },
    ]);

    const [entry] = await getCalendar(nextProfile(), window30());

    expect(entry?.airsAt).toBe(airsAt);
    // A day early, to catch the first night's late shows already in the next UTC day.
    expect(fetchMyShowsCalendar).toHaveBeenCalledWith(day(-1), 32, expect.anything());
  });

  it("still answers when Trakt fails, just without times", async () => {
    followed([tmdbEpisode(3, day(1))]);
    isTraktConnected.mockResolvedValue(true);
    fetchMyShowsCalendar.mockRejectedValue(new Error("Trakt down"));

    const entries = await getCalendar(nextProfile(), window30());

    expect(entries).toHaveLength(1);
    expect(entries[0]?.airsAt).toBeNull();
  });

  it("skips a series that has ended when the window is all future, and asks about it for one in the past", async () => {
    followed([tmdbEpisode(9, day(-5))], "Ended");

    expect(await getCalendar(nextProfile(), window30())).toEqual([]);
    expect(getCachedShowDetails).not.toHaveBeenCalled();

    const past = await getCalendar(nextProfile(), { start: utcDay(-10), days: 20 });
    expect(past.map((e) => e.episode)).toEqual([9]);
  });

  it("adds only the premiere of a watchlisted series nobody has started", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "ttN" }]);
    prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
      where.type === "series" ? [series("ttN", "New Show")] : []
    );
    loadEpisodes.mockImplementation(async (_id: string, season: number) =>
      season === 2 ? [tmdbEpisode(1, day(3)), tmdbEpisode(2, day(10))] : []
    );

    const entries = await getCalendar(nextProfile(), window30());

    expect(entries.map((e) => [e.seriesName, e.kind, e.episode])).toEqual([["New Show", "premiere", 1]]);
  });

  it("puts a watchlisted film on its release date, watched or not", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt1" }]);
    prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
      where.type === "movie"
        ? [{ imdbId: "tt1", name: "Arrival", poster: "/p.jpg", background: "/b.jpg", description: "Shapes.", releaseDate: day(5) }]
        : []
    );
    prismaMock.watchEvent.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt1", seriesImdbId: null, season: null, episode: null }]);

    const [film] = await getCalendar(nextProfile(), window30());

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
      airDate: day(5),
      airsAt: null,
      watched: true,
      overview: "Shapes.",
    });
    const movieQuery = prismaMock.metadata.findMany.mock.calls.find((c) => c[0].where.type === "movie")?.[0];
    expect(movieQuery).toMatchObject({ where: { releaseDate: { gte: day(0), lte: day(30) } } });
  });

  it("orders by day, then time, then premieres, episodes and films", async () => {
    prismaMock.seriesProgress.findMany.mockResolvedValue([{ seriesImdbId: "ttA" }, { seriesImdbId: "ttB" }]);
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "ttM" }]);
    prismaMock.metadata.findMany.mockImplementation(async ({ where }: { where: { type: string } }) =>
      where.type === "series"
        ? [series("ttA", "Andor"), series("ttB", "Bluey")]
        : [{ imdbId: "ttM", name: "A Film", poster: null, background: null, description: null, releaseDate: day(2) }]
    );
    loadEpisodes.mockImplementation(async (id: string, season: number) =>
      season !== 2 ? [] : id === "ttA" ? [tmdbEpisode(4, day(2))] : [tmdbEpisode(1, day(2))]
    );

    const entries = await getCalendar(nextProfile(), window30());

    expect(entries.map((e) => e.seriesName)).toEqual(["Bluey", "Andor", "A Film"]);
  });
});
