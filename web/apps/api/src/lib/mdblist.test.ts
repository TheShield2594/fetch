import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

const kv = new Map<string, string>();
const prismaMock = {
  kV: {
    findUnique: vi.fn(async ({ where }: { where: { key: string } }) =>
      kv.has(where.key) ? { key: where.key, value: kv.get(where.key)!, updatedAt: new Date() } : null
    ),
    upsert: vi.fn(async ({ where, create }: { where: { key: string }; create: { value: string } }) => {
      kv.set(where.key, create.value);
      return {};
    }),
    deleteMany: vi.fn(async () => ({ count: 1 })),
  },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const secrets = new Map<string, string>();
vi.mock("./secret-store.js", () => ({
  readSecretKv: async (key: string) => secrets.get(key) ?? null,
  writeSecretKv: async (key: string, value: string) => void secrets.set(key, value),
  deleteSecretKv: async (key: string) => void secrets.delete(key),
}));

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
const LATER = () => new Date(Date.now() + 3_600_000).toISOString();

const fetchMock = vi.fn();
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const call = (index = 0) => {
  const [url, init] = fetchMock.mock.calls[index] as [URL, RequestInit];
  const raw = init.body as string | undefined;
  return {
    url: new URL(url),
    init,
    form: raw && (init.headers as Record<string, string>)["Content-Type"]?.includes("form") ? Object.fromEntries(new URLSearchParams(raw)) : undefined,
    body: raw && (init.headers as Record<string, string>)["Content-Type"] === "application/json" ? JSON.parse(raw) : undefined,
  };
};

const mdb = await import("./mdblist.js");

const DEVICE = {
  device_code: "dev-123",
  user_code: "ABCD-EFGH",
  verification_uri: "https://mdblist.com/device",
  expires_in: 600,
  interval: 5,
};

beforeEach(() => {
  vi.clearAllMocks();
  kv.clear();
  secrets.clear();
  process.env.MDBLIST_CLIENT_ID = "mdb-client";
  vi.stubGlobal("fetch", fetchMock);
  connections.getConnection.mockResolvedValue({
    credentials: { accessToken: "at", refreshToken: "rt", expiresAt: LATER() },
    details: { username: "sam" },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.MDBLIST_CLIENT_ID;
});

describe("the device sign-in", () => {
  it("asks for a write-scoped code and keeps the device code out of what the page sees", async () => {
    fetchMock.mockResolvedValueOnce(json(DEVICE));

    const pending = await mdb.startMdbListDevice(PROFILE);

    expect(call().url.pathname).toBe("/oauth/device-authorization/");
    expect(call().form).toEqual({ client_id: "mdb-client", scope: "write" });
    expect(pending).toMatchObject({
      userCode: "ABCD-EFGH",
      verificationUrl: "https://mdblist.com/device",
      verificationUrlComplete: "https://mdblist.com/device?user_code=ABCD-EFGH",
    });
    expect(pending).not.toHaveProperty("deviceCode");
  });

  it("refuses a sign-in page that isn't mdblist.com over https", async () => {
    fetchMock.mockResolvedValueOnce(json({ ...DEVICE, verification_uri: "https://evil.example/device" }));
    await expect(mdb.startMdbListDevice(PROFILE)).rejects.toThrow(/sign-in page/);
  });

  it("waits while pending, then stores the tokens and the user's name", async () => {
    fetchMock.mockResolvedValueOnce(json(DEVICE));
    await mdb.startMdbListDevice(PROFILE);

    fetchMock.mockResolvedValueOnce(json({ error: "authorization_pending" }, 400));
    expect(await mdb.pollMdbListDevice(PROFILE)).toBe("pending");

    fetchMock
      .mockResolvedValueOnce(json({ access_token: "a1", refresh_token: "r1", token_type: "Bearer", expires_in: 3600, scope: "write" }))
      .mockResolvedValueOnce(json({ username: "Sam" }));
    expect(await mdb.pollMdbListDevice(PROFILE)).toBe("connected");

    expect(call(2).form).toMatchObject({ grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code: "dev-123" });
    expect(connections.saveConnection).toHaveBeenCalledWith(
      expect.objectContaining({
        profileId: PROFILE,
        provider: "mdblist",
        credentials: expect.objectContaining({ accessToken: "a1", refreshToken: "r1" }),
        details: { username: "Sam" },
      })
    );
    expect(await mdb.pollMdbListDevice(PROFILE)).toBe("none");
  });

  it("stops on a denial", async () => {
    fetchMock.mockResolvedValueOnce(json(DEVICE));
    await mdb.startMdbListDevice(PROFILE);
    fetchMock.mockResolvedValueOnce(json({ error: "access_denied" }, 400));
    expect(await mdb.pollMdbListDevice(PROFILE)).toBe("denied");
  });

  it("refuses tokens without write access", async () => {
    fetchMock.mockResolvedValueOnce(json(DEVICE));
    await mdb.startMdbListDevice(PROFILE);
    fetchMock.mockResolvedValueOnce(json({ access_token: "a", refresh_token: "r", token_type: "Bearer", expires_in: 60, scope: "read" }));
    await expect(mdb.pollMdbListDevice(PROFILE)).rejects.toThrow(/write/);
  });
});

describe("tokens", () => {
  it("refreshes one about to run out before using it", async () => {
    connections.getConnection.mockResolvedValue({
      credentials: { accessToken: "old", refreshToken: "rt", expiresAt: new Date(Date.now() + 10_000).toISOString() },
      details: {},
    });
    fetchMock
      .mockResolvedValueOnce(json({ access_token: "new", token_type: "Bearer", expires_in: 3600 }))
      .mockResolvedValueOnce(json({}));

    await mdb.pushMdbListWatched(PROFILE, { type: "movie", imdbId: "tt1" }, logger);

    expect(call(0).form).toMatchObject({ grant_type: "refresh_token", refresh_token: "rt" });
    // A refresh without a new refresh token keeps the old one.
    expect(connections.saveConnection).toHaveBeenCalledWith(
      expect.objectContaining({ credentials: expect.objectContaining({ accessToken: "new", refreshToken: "rt" }) })
    );
    expect((call(1).init.headers as Record<string, string>).Authorization).toBe("Bearer new");
  });

  it("drops a connection MDBList has revoked", async () => {
    connections.getConnection.mockResolvedValue({
      credentials: { accessToken: "old", refreshToken: "rt", expiresAt: new Date(0).toISOString() },
      details: {},
    });
    fetchMock.mockResolvedValueOnce(json({ error: "invalid_grant" }, 400));

    await mdb.pushMdbListWatched(PROFILE, { type: "movie", imdbId: "tt1" }, logger);

    expect(connections.deleteConnection).toHaveBeenCalledWith(PROFILE, "mdblist");
    expect(logger.warn).toHaveBeenCalled();
  });
});

describe("reading the account", () => {
  it("turns watched movies, nested show episodes and flat episodes into plays", () => {
    const watches = mdb.projectMdbListWatched([
      {
        movies: [{ movie: { title: "Heat", ids: { imdb: "tt0113277" } }, last_watched_at: "2026-01-02T20:00:00Z" }],
        shows: [
          {
            show: { title: "Dark", ids: { imdb: "tt5753856" } },
            seasons: [{ number: 1, episodes: [{ number: 3, last_watched_at: "2026-01-03T20:00:00Z" }] }],
          },
        ],
        episodes: [{ show: { ids: { imdb: "tt0903747" } }, episode: { season: 2, number: 4 }, watched_at: "2026-01-05T00:00:00Z" }],
      },
    ]);
    expect(watches).toEqual([
      { type: "movie", imdbId: "tt0113277", watchedAt: new Date("2026-01-02T20:00:00Z"), title: "Heat" },
      { type: "episode", seriesImdbId: "tt5753856", season: 1, episode: 3, watchedAt: new Date("2026-01-03T20:00:00Z"), title: "Dark" },
      { type: "episode", seriesImdbId: "tt0903747", season: 2, episode: 4, watchedAt: new Date("2026-01-05T00:00:00Z"), title: undefined },
    ]);
  });

  it("reads watchlist rows in either shape", () => {
    expect(
      mdb.projectMdbListWatchlist([
        { items: [{ mediatype: "movie", imdb_id: "tt1", title: "A" }, { mediatype: "show", ids: { imdb: "tt2" } }] },
        { movies: [{ imdb_id: "tt3" }], shows: [{ ids: { imdb: "tt4" } }] },
      ])
    ).toEqual([
      { type: "movie", imdbId: "tt1", title: "A" },
      { type: "series", imdbId: "tt2", title: undefined },
      { type: "movie", imdbId: "tt3", title: undefined },
      { type: "series", imdbId: "tt4", title: undefined },
    ]);
  });

  it("skips a sync when MDBList's last activities haven't moved", async () => {
    fetchMock.mockImplementation(async (url: URL) =>
      json(new URL(url).pathname === "/sync/last_activities" ? { all: "2026-01-01T00:00:00Z" } : {})
    );

    const first = await mdb.syncMdbList(PROFILE, logger);
    const second = await mdb.syncMdbList(PROFILE, logger);

    expect(first).toMatchObject({ unchanged: false, full: true });
    expect(second).toMatchObject({ unchanged: true });
    expect(importTrackerItems).toHaveBeenCalledTimes(1);
  });
});

describe("sending to MDBList", () => {
  beforeEach(() => fetchMock.mockResolvedValue(json({})));

  it("marks an episode watched under its show", async () => {
    await mdb.pushMdbListWatched(PROFILE, { type: "episode", seriesImdbId: "tt5753856", season: 1, episode: 2 }, logger);
    expect(call().url.pathname).toBe("/sync/watched");
    expect(call().body).toEqual({ shows: [{ ids: { imdb: "tt5753856" }, seasons: [{ number: 1, episodes: [{ number: 2 }] }] }] });
  });

  it("adds to the watchlist", async () => {
    await mdb.pushMdbListWatchlistAdd(PROFILE, { type: "movie", imdbId: "tt1" }, logger);
    expect(call().url.pathname).toBe("/watchlist/items/add");
    expect(call().body).toEqual({ movies: [{ ids: { imdb: "tt1" } }] });
  });

  it("scrobbles with the episode on the show, as the TV sends it", async () => {
    await mdb.pushMdbListScrobble(
      "start",
      { profileId: PROFILE, type: "episode", imdbId: "tt5753856", season: 1, episode: 2, progress: 12.349 },
      logger
    );
    expect(call().url.pathname).toBe("/scrobble/start");
    expect(call().body).toEqual({ show: { ids: { imdb: "tt5753856" }, season: 1, episode: 2 }, progress: 12.34 });
  });
});

describe("personal lists", () => {
  it("offers only the account's static lists", () => {
    expect(
      mdb.projectMdbListUserLists([
        { id: 1, name: "Weekend", type: "static", items: 3, private: true },
        { id: 2, name: "Top rated", dynamic: true },
        { id: 3, name: "Filtered", type: "dynamic" },
        { id: 0, name: "Broken" },
        null,
      ])
    ).toEqual([{ id: "1", name: "Weekend", itemCount: 3, private: true }]);
  });

  it("reads a list's items, both kinds", async () => {
    fetchMock.mockResolvedValueOnce(
      json({ items: [{ mediatype: "movie", imdb_id: "tt1" }, { mediatype: "show", imdb_id: "tt2" }] })
    );
    expect(await mdb.fetchMdbListListItems(PROFILE, "7")).toEqual([
      { type: "movie", imdbId: "tt1" },
      { type: "series", imdbId: "tt2" },
    ]);
    expect(call().url.pathname).toBe("/lists/7/items");
    expect(call().url.searchParams.get("unified")).toBe("true");
  });

  it("adds and removes with bare ids, as a personal list takes them", async () => {
    fetchMock.mockResolvedValue(json({}));
    await mdb.changeMdbListListItems(PROFILE, "7", "remove", [
      { type: "movie", imdbId: "tt1" },
      { type: "series", imdbId: "tt2" },
    ]);
    expect(call().url.pathname).toBe("/lists/7/items/remove");
    expect(call().body).toEqual({ movies: [{ imdb: "tt1" }], shows: [{ imdb: "tt2" }] });
  });

  it("keeps a list's privacy when renaming it", async () => {
    fetchMock
      .mockResolvedValueOnce(json([{ id: 7, name: "Old", type: "static", private: false }]))
      .mockResolvedValueOnce(json({ success: true, id: 7 }));
    await mdb.renameMdbListList(PROFILE, "7", "New");
    expect(call(1).init.method).toBe("PUT");
    expect(call(1).url.pathname).toBe("/lists/7");
    expect(call(1).body).toEqual({ name: "New", private: false });
  });

  it("throws when the profile isn't connected, so the sync can say so", async () => {
    connections.getConnection.mockResolvedValue(null);
    await expect(mdb.fetchMdbListUserLists(PROFILE)).rejects.toThrow(/not connected/);
  });
});
