import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

const kv = new Map<string, string>();
const prismaMock = {
  kV: {
    findUnique: vi.fn(async ({ where }: { where: { key: string } }) =>
      kv.has(where.key) ? { key: where.key, value: kv.get(where.key)! } : null
    ),
    upsert: vi.fn(async ({ where, create }: { where: { key: string }; create: { value: string } }) => {
      kv.set(where.key, create.value);
      return {};
    }),
    deleteMany: vi.fn(async ({ where }: { where: { key: string | { in: string[] } } }) => {
      const keys = typeof where.key === "string" ? [where.key] : where.key.in;
      for (const key of keys) kv.delete(key);
      return { count: keys.length };
    }),
  },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const connections = {
  getConnection: vi.fn(),
  saveConnection: vi.fn(),
  deleteConnection: vi.fn(),
  listConnections: vi.fn(),
};
vi.mock("./connections.js", () => connections);

const importTrackerItems = vi.fn(async () => ({ movies: 0, episodes: 0, watchlist: 0, skipped: 0 }));
vi.mock("./tracker-import.js", () => ({ importTrackerItems: (...a: unknown[]) => importTrackerItems(...(a as [])) }));

const logger = { warn: vi.fn(), info: vi.fn(), error: vi.fn(), debug: vi.fn() } as unknown as FastifyBaseLogger;
const PROFILE = "11111111-1111-4111-8111-111111111111";

const fetchMock = vi.fn();
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const sent = (index = 0) => {
  const [url, init] = fetchMock.mock.calls[index] as [URL, RequestInit];
  return { url: new URL(url), init, body: init.body ? JSON.parse(init.body as string) : undefined };
};

const simkl = await import("./simkl.js");

beforeEach(() => {
  vi.clearAllMocks();
  kv.clear();
  process.env.SIMKL_CLIENT_ID = "simkl-client";
  vi.stubGlobal("fetch", fetchMock);
  connections.getConnection.mockResolvedValue({ credentials: { accessToken: "tok" }, details: { username: "sam" } });
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.SIMKL_CLIENT_ID;
});

describe("signing in with a PIN", () => {
  it("asks Simkl for a code, carrying the app's id the way the TV does", async () => {
    fetchMock.mockResolvedValueOnce(
      json({ result: "OK", user_code: "AB12CD", verification_url: "https://simkl.com/pin", expires_in: 900, interval: 5 })
    );

    const pin = await simkl.startSimklPin(PROFILE);

    expect(pin).toMatchObject({ userCode: "AB12CD", verificationUrl: "https://simkl.com/pin", interval: 5 });
    const { url } = sent();
    expect(url.pathname).toBe("/oauth/pin");
    expect(url.searchParams.get("client_id")).toBe("simkl-client");
    expect(url.searchParams.get("app-name")).toBe("fetch");
  });

  it("refuses a code that isn't the PIN shape, so it can't steer the poll URL", async () => {
    fetchMock.mockResolvedValueOnce(json({ result: "OK", user_code: "../x", verification_url: "u", expires_in: 900 }));
    await expect(simkl.startSimklPin(PROFILE)).rejects.toThrow(/unexpected/);
  });

  it("stays pending until the code is entered, then connects the profile with its name", async () => {
    fetchMock.mockResolvedValueOnce(json({ result: "OK", user_code: "AB12CD", verification_url: "u", expires_in: 900 }));
    await simkl.startSimklPin(PROFILE);

    fetchMock.mockResolvedValueOnce(json({ result: "KO", message: "Authorization pending" }));
    expect(await simkl.pollSimklPin(PROFILE)).toBe("pending");
    expect(connections.saveConnection).not.toHaveBeenCalled();

    fetchMock
      .mockResolvedValueOnce(json({ result: "OK", access_token: "new-token" }))
      .mockResolvedValueOnce(json({ user: { name: "Sam" }, account: { id: 42 } }));
    expect(await simkl.pollSimklPin(PROFILE)).toBe("connected");
    expect(connections.saveConnection).toHaveBeenCalledWith({
      profileId: PROFILE,
      provider: "simkl",
      credentials: { accessToken: "new-token" },
      details: { username: "Sam", accountId: 42 },
    });
    expect(sent(2).url.pathname).toBe("/oauth/pin/AB12CD");
    // The code is spent.
    expect(await simkl.pollSimklPin(PROFILE)).toBe("none");
  });

  it("reports an expired code without asking Simkl", async () => {
    fetchMock.mockResolvedValueOnce(json({ result: "OK", user_code: "AB12CD", verification_url: "u", expires_in: 1 }));
    await simkl.startSimklPin(PROFILE);
    vi.setSystemTime(Date.now() + 5_000);
    fetchMock.mockClear();

    expect(await simkl.pollSimklPin(PROFILE)).toBe("expired");
    expect(fetchMock).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
});

describe("projectSimklLibrary", () => {
  it("files completed films as plays, every watched episode, and plan-to-watch as the watchlist", () => {
    const { watches, watchlist } = simkl.projectSimklLibrary({
      movies: [
        { status: "completed", last_watched_at: "2026-01-02T20:00:00Z", movie: { title: "Heat", ids: { imdb: "tt0113277" } } },
        { status: "plantowatch", movie: { title: "Ran", ids: { imdb: "tt0089881" } } },
        { status: "dropped", last_watched_at: "2026-01-01T00:00:00Z", movie: { ids: { imdb: "tt1" } } },
      ],
      shows: [
        {
          status: "watching",
          show: { title: "Dark", ids: { imdb: "tt5753856" } },
          seasons: [{ number: 1, episodes: [{ number: 1, watched_at: "2026-01-03T20:00:00Z" }, { number: 2 }] }],
        },
        { status: "plantowatch", show: { ids: { imdb: "tt0903747" } } },
      ],
      anime: [{ anime_type: "movie", status: "completed", last_watched_at: "2026-01-04T00:00:00Z", show: { ids: { imdb: "tt0245429" } } }],
    });

    expect(watches).toEqual([
      { type: "movie", imdbId: "tt0113277", watchedAt: new Date("2026-01-02T20:00:00Z"), title: "Heat" },
      { type: "episode", seriesImdbId: "tt5753856", season: 1, episode: 1, watchedAt: new Date("2026-01-03T20:00:00Z"), title: "Dark" },
      { type: "movie", imdbId: "tt0245429", watchedAt: new Date("2026-01-04T00:00:00Z"), title: undefined },
    ]);
    expect(watchlist).toEqual([
      { type: "movie", imdbId: "tt0089881", title: "Ran" },
      { type: "series", imdbId: "tt0903747", title: undefined },
    ]);
  });

  it("copes with an empty answer", () => {
    expect(simkl.projectSimklLibrary(null)).toEqual({ watches: [], watchlist: [] });
  });
});

describe("syncSimkl", () => {
  it("reads everything the first time, then only what changed since", async () => {
    fetchMock.mockImplementation(async () => json({}));

    const first = await simkl.syncSimkl(PROFILE, logger);
    expect(first.full).toBe(true);
    expect(sent(0).url.searchParams.get("date_from")).toBeNull();
    expect(sent(0).init.headers).toMatchObject({ Authorization: "Bearer tok" });

    const second = await simkl.syncSimkl(PROFILE, logger);
    expect(second.full).toBe(false);
    expect(sent(1).url.searchParams.get("date_from")).toMatch(/^\d{4}-\d\d-\d\dT/);
    expect(importTrackerItems).toHaveBeenCalledWith(PROFILE, [], [], logger);
  });

  it("does nothing for a profile without Simkl", async () => {
    connections.getConnection.mockResolvedValue(null);
    expect(await simkl.syncSimkl(PROFILE, logger)).toMatchObject({ movies: 0, full: false });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("sending to Simkl", () => {
  beforeEach(() => fetchMock.mockResolvedValue(json({})));

  it("adds a watched episode to history under its show and season", async () => {
    await simkl.pushSimklWatched(
      PROFILE,
      { type: "episode", seriesImdbId: "tt5753856", season: 1, episode: 2, watchedAt: new Date("2026-01-03T20:00:00Z") },
      logger
    );
    expect(sent().url.pathname).toBe("/sync/history");
    expect(sent().body).toEqual({
      shows: [
        { ids: { imdb: "tt5753856" }, seasons: [{ number: 1, episodes: [{ number: 2, watched_at: "2026-01-03T20:00:00.000Z" }] }] },
      ],
    });
  });

  it("puts a watchlist add on plan-to-watch", async () => {
    await simkl.pushSimklWatchlistAdd(PROFILE, { type: "series", imdbId: "tt0903747" }, logger);
    expect(sent().url.pathname).toBe("/sync/add-to-list");
    expect(sent().body).toEqual({ shows: [{ to: "plantowatch", ids: { imdb: "tt0903747" } }] });
  });

  it("scrobbles an episode by show and episode", async () => {
    await simkl.pushSimklScrobble(
      "pause",
      { profileId: PROFILE, type: "episode", imdbId: "tt5753856", season: 1, episode: 2, progress: 41.234 },
      logger
    );
    expect(sent().url.pathname).toBe("/scrobble/pause");
    expect(sent().body).toEqual({ progress: 41.23, show: { ids: { imdb: "tt5753856" } }, episode: { season: 1, number: 2 } });
  });

  it("never throws when Simkl is down, and sends nothing without a connection", async () => {
    fetchMock.mockResolvedValue(json({ error: "down" }, 503));
    await expect(simkl.pushSimklWatched(PROFILE, { type: "movie", imdbId: "tt1" }, logger)).resolves.toBeUndefined();
    expect(logger.warn).toHaveBeenCalled();

    fetchMock.mockClear();
    connections.getConnection.mockResolvedValue(null);
    await simkl.pushSimklWatched(PROFILE, { type: "movie", imdbId: "tt1" }, logger);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
