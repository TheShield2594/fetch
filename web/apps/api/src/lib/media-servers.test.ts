import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

const kv = new Map<string, string>();
vi.mock("./prisma.js", () => ({
  prisma: {
    kV: {
      findUnique: vi.fn(async ({ where }: { where: { key: string } }) =>
        kv.has(where.key) ? { key: where.key, value: kv.get(where.key)! } : null
      ),
      upsert: vi.fn(async ({ where, create }: { where: { key: string }; create: { value: string } }) => {
        kv.set(where.key, create.value);
        return {};
      }),
      deleteMany: vi.fn(async ({ where }: { where: { key: string } }) => {
        kv.delete(where.key);
        return { count: 1 };
      }),
    },
  },
}));

const connections = {
  saveConnection: vi.fn(),
  listConnections: vi.fn<(provider: string) => Promise<unknown[]>>(async () => []),
  listProfileConnections: vi.fn<(profileId: string, provider: string) => Promise<unknown[]>>(async () => []),
  getProfileConnectionById: vi.fn(),
  deleteConnectionById: vi.fn(),
  updateConnectionDetails: vi.fn(),
  updateConnectionState: vi.fn(),
};
vi.mock("./connections.js", () => connections);

const scope = { allowPrivateNetwork: true };
vi.mock("./integration-owner.js", () => ({ outboundScopeForProfile: async () => scope }));

const importTrackerItems = vi.fn(async () => ({ movies: 1, episodes: 1, watchlist: 0, skipped: 0 }));
vi.mock("./tracker-import.js", () => ({ importTrackerItems: (...a: unknown[]) => importTrackerItems(...(a as [])) }));
const importPlaybackProgress = vi.fn(async () => 1);
vi.mock("./playback.js", () => ({ importPlaybackProgress: (...a: unknown[]) => importPlaybackProgress(...(a as [])) }));

const logger = { warn: vi.fn(), info: vi.fn(), error: vi.fn(), debug: vi.fn() } as unknown as FastifyBaseLogger;
const PROFILE = "11111111-1111-4111-8111-111111111111";
// IP literals, so the address check never needs DNS.
const LAN = "http://192.168.1.5:32400";
const PUBLIC = "http://93.184.215.14:32400";

const fetchMock = vi.fn();
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const urlOf = (index: number) => new URL((fetchMock.mock.calls[index] as [string])[0]);
const initOf = (index: number) => (fetchMock.mock.calls[index] as [string, RequestInit])[1];

const servers = await import("./media-servers.js");

beforeEach(() => {
  vi.clearAllMocks();
  kv.clear();
  scope.allowPrivateNetwork = true;
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => vi.unstubAllGlobals());

describe("Plex sign-in", () => {
  const RESOURCES = [
    {
      name: "Basement",
      provides: "server",
      clientIdentifier: "srv-1",
      accessToken: "server-token",
      productVersion: "1.40.0.1",
      connections: [
        { uri: PUBLIC, local: false },
        { uri: LAN, local: true },
      ],
    },
    { name: "Phone", provides: "player", clientIdentifier: "phone" },
  ];

  const route = (handlers: Record<string, () => Response>) =>
    fetchMock.mockImplementation(async (input: string) => {
      const url = new URL(input);
      const handler = Object.entries(handlers).find(([key]) => `${url.origin}${url.pathname}`.endsWith(key));
      if (!handler) throw new Error(`unexpected ${url}`);
      return handler[1]();
    });

  it("asks plex.tv for a link code with this profile's client identity", async () => {
    route({ "/api/v2/pins": () => json({ id: 7, code: "ABCD", expiresIn: 600 }) });

    const link = await servers.startPlexLink(PROFILE);

    expect(link).toMatchObject({ code: "ABCD", linkUrl: "https://plex.tv/link" });
    expect(initOf(0).headers).toMatchObject({ "X-Plex-Product": "Fetch", "X-Plex-Client-Identifier": expect.any(String) });
  });

  it("saves each reachable server, preferring its local address, once the code is entered", async () => {
    route({ "/api/v2/pins": () => json({ id: 7, code: "ABCD", expiresIn: 600 }) });
    await servers.startPlexLink(PROFILE);
    route({
      "/api/v2/pins/7": () => json({ authToken: "account-token" }),
      "/api/v2/user": () => json({ id: 99, username: "sam" }),
      "/api/v2/resources": () => json(RESOURCES),
      "/identity": () => json({ MediaContainer: { machineIdentifier: "srv-1" } }),
      "/library/sections": () =>
        json({
          MediaContainer: {
            Directory: [
              { key: "1", type: "movie", title: "Films" },
              { key: "2", type: "artist", title: "Music" },
              { key: "3", type: "show", title: "Kids" },
            ],
          },
        }),
    });

    const result = await servers.pollPlexLink(PROFILE);

    expect(result).toEqual({ result: "connected", servers: 1 });
    expect(connections.saveConnection).toHaveBeenCalledWith(
      expect.objectContaining({
        provider: "plex",
        externalKey: "srv-1|99",
        credentials: { token: "server-token" },
        details: expect.objectContaining({
          serverName: "Basement",
          address: LAN,
          userName: "sam",
          importWatchState: true,
          // Listed at sign-in, every one chosen.
          libraries: [
            { id: "1", name: "Films", kind: "movies", selected: true },
            { id: "3", name: "Kids", kind: "shows", selected: true },
          ],
        }),
      })
    );
  });

  it("uses only a public address for another account's profile", async () => {
    scope.allowPrivateNetwork = false;
    route({ "/api/v2/pins": () => json({ id: 7, code: "ABCD", expiresIn: 600 }) });
    await servers.startPlexLink(PROFILE);
    route({
      "/api/v2/pins/7": () => json({ authToken: "account-token" }),
      "/api/v2/user": () => json({ id: 99, username: "sam" }),
      "/api/v2/resources": () => json(RESOURCES),
      "/identity": () => json({ MediaContainer: { machineIdentifier: "srv-1" } }),
    });

    await servers.pollPlexLink(PROFILE);

    expect(connections.saveConnection).toHaveBeenCalledWith(
      expect.objectContaining({ details: expect.objectContaining({ address: PUBLIC }) })
    );
    // The LAN address was never asked.
    expect(fetchMock.mock.calls.map(([input]) => String(input))).not.toContain(`${LAN}/identity`);
  });

  it("is pending until the code is entered", async () => {
    route({ "/api/v2/pins": () => json({ id: 7, code: "ABCD", expiresIn: 600 }) });
    await servers.startPlexLink(PROFILE);
    route({ "/api/v2/pins/7": () => json({ authToken: null }) });

    expect(await servers.pollPlexLink(PROFILE)).toEqual({ result: "pending", servers: 0 });
  });
});

describe("Jellyfin and Emby sign-in", () => {
  it("checks the server, signs in, and stores the token but never the password", async () => {
    fetchMock
      .mockResolvedValueOnce(json({ ServerName: "Den", Version: "10.10.3", Id: "jf-1", ProductName: "Jellyfin Server" }))
      .mockResolvedValueOnce(json({ AccessToken: "jf-token", ServerId: "jf-1", User: { Id: "u-1", Name: "Sam" } }));

    const details = await servers.signInMediaBrowser(PROFILE, "jellyfin", {
      address: "http://93.184.215.14:8096/",
      username: "sam",
      password: "hunter2",
    });

    expect(details).toMatchObject({ serverName: "Den", address: "http://93.184.215.14:8096", userId: "u-1", userName: "Sam" });
    expect(urlOf(0).pathname).toBe("/System/Info/Public");
    expect(urlOf(1).pathname).toBe("/Users/AuthenticateByName");
    expect(initOf(1).headers).toMatchObject({ Authorization: expect.stringContaining('Client="Fetch"') });
    expect(JSON.parse(initOf(1).body as string)).toEqual({ Username: "sam", Pw: "hunter2" });
    const saved = connections.saveConnection.mock.calls[0]![0] as unknown;
    expect(JSON.stringify(saved)).not.toContain("hunter2");
  });

  it("talks to Emby under /emby with its own header, and refuses a Jellyfin server posing as one", async () => {
    fetchMock.mockResolvedValueOnce(json({ ServerName: "X", Version: "10.10.0", Id: "x", ProductName: "Jellyfin Server" }));

    await expect(
      servers.signInMediaBrowser(PROFILE, "emby", { address: "93.184.215.14:8096", username: "sam", password: "p" })
    ).rejects.toThrow(/Emby/);
    expect(urlOf(0).toString()).toBe("https://93.184.215.14:8096/emby/System/Info/Public");
  });

  it("keeps another account's profile off the home network", async () => {
    scope.allowPrivateNetwork = false;

    await expect(
      servers.signInMediaBrowser(PROFILE, "jellyfin", { address: "http://192.168.1.20:8096", username: "a", password: "b" })
    ).rejects.toMatchObject({ kind: "blocked" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("projectServerTitles", () => {
  const at = new Date("2026-02-01T00:00:00Z");

  it("turns played titles into plays, part-watched ones into resume points, and indexes them all", () => {
    const { watches, resume, state } = servers.projectServerTitles(
      {
        movies: [
          { itemId: "m1", imdbId: "tt1", played: true, lastPlayedAt: new Date("2026-01-01T00:00:00Z"), positionSeconds: null, durationSeconds: 6000 },
          { itemId: "m2", imdbId: "tt2", played: false, lastPlayedAt: new Date("2026-01-02T00:00:00Z"), positionSeconds: 1500, durationSeconds: 6000 },
          { itemId: "m3", imdbId: "tt3", played: true, lastPlayedAt: null, positionSeconds: null, durationSeconds: null },
        ],
        episodes: [
          { itemId: "e1", imdbId: "tt9", season: 1, episode: 2, played: true, lastPlayedAt: new Date("2026-01-03T00:00:00Z"), positionSeconds: null, durationSeconds: null },
        ],
      },
      at
    );

    expect(watches).toEqual([
      expect.objectContaining({ type: "movie", imdbId: "tt1", undated: false }),
      // Marked played with no date: dated to the sync, and flagged so a later sync doesn't add it again.
      expect.objectContaining({ type: "movie", imdbId: "tt3", watchedAt: at, undated: true }),
      expect.objectContaining({ type: "episode", seriesImdbId: "tt9", season: 1, episode: 2 }),
    ]);
    expect(resume).toEqual([
      expect.objectContaining({ target: { type: "movie", imdbId: "tt2" }, progress: 25, positionSeconds: 1500 }),
    ]);
    expect(state).toEqual({ movies: { tt1: "m1", tt2: "m2", tt3: "m3" }, episodes: { "tt9:1:2": "e1" } });
  });
});

describe("syncing a Jellyfin server", () => {
  const VIEWS = [
    { Id: "v-films", Name: "Films", CollectionType: "movies" },
    { Id: "v-tv", Name: "TV", CollectionType: "tvshows" },
    { Id: "v-music", Name: "Music", CollectionType: "music" },
    { Id: "v-kids", Name: "Kids" },
  ];
  const connection = {
    id: "c1",
    profileId: PROFILE,
    provider: "jellyfin",
    externalKey: "jf-1|u-1",
    credentials: { token: "jf-token" },
    details: { serverName: "Den", address: PUBLIC, serverId: "jf-1", userId: "u-1", userName: "Sam", importWatchState: true },
    state: {},
    createdAt: new Date(),
    updatedAt: new Date(),
  };

  it("reads movies, series and episodes, imports them and keeps the index", async () => {
    fetchMock.mockImplementation(async (input: string) => {
      if (new URL(input).pathname === "/UserViews") return json({ Items: VIEWS });
      const type = new URL(input).searchParams.get("includeItemTypes");
      if (type === "Movie") {
        return json({ Items: [{ Id: "m1", Name: "Heat", ProviderIds: { Imdb: "tt0113277" }, UserData: { Played: true, LastPlayedDate: "2026-01-01T00:00:00Z" } }], TotalRecordCount: 1 });
      }
      if (type === "Series") return json({ Items: [{ Id: "s1", ProviderIds: { Imdb: "tt5753856" } }], TotalRecordCount: 1 });
      return json({
        Items: [{ Id: "e1", SeriesId: "s1", ParentIndexNumber: 1, IndexNumber: 1, UserData: { Played: true, LastPlayedDate: "2026-01-02T00:00:00Z" } }],
        TotalRecordCount: 1,
      });
    });

    const summary = await servers.syncMediaServer(connection as never, logger);

    expect(summary).toEqual({ movies: 1, episodes: 1, resumePoints: 1, skipped: 0 });
    expect(urlOf(0).pathname).toBe("/UserViews");
    expect(urlOf(1).searchParams.get("userId")).toBe("u-1");
    // Every library chosen (the default): the whole server, no parentId.
    expect(urlOf(1).searchParams.has("parentId")).toBe(false);
    expect(connections.updateConnectionDetails).toHaveBeenCalledWith(
      "c1",
      expect.objectContaining({
        libraries: [
          { id: "v-films", name: "Films", kind: "movies", selected: true },
          { id: "v-tv", name: "TV", kind: "shows", selected: true },
          { id: "v-kids", name: "Kids", kind: "mixed", selected: true },
        ],
      })
    );
    expect(connections.updateConnectionState).toHaveBeenCalledWith("c1", {
      movies: { tt0113277: "m1" },
      episodes: { "tt5753856:1:1": "e1" },
    });
  });

  it("reads only the chosen libraries, by parentId, and indexes only them", async () => {
    fetchMock.mockImplementation(async (input: string) => {
      const url = new URL(input);
      if (url.pathname === "/UserViews") return json({ Items: VIEWS });
      const parent = url.searchParams.get("parentId");
      const type = url.searchParams.get("includeItemTypes");
      if (type === "Movie" && parent === "v-films") {
        return json({ Items: [{ Id: "m1", ProviderIds: { Imdb: "tt1" }, UserData: { Played: true, LastPlayedDate: "2026-01-01T00:00:00Z" } }], TotalRecordCount: 1 });
      }
      if (type === "Movie" && parent === "v-kids") {
        return json({ Items: [{ Id: "m2", ProviderIds: { Imdb: "tt2" }, UserData: { Played: true, LastPlayedDate: "2026-01-01T00:00:00Z" } }], TotalRecordCount: 1 });
      }
      return json({ Items: [], TotalRecordCount: 0 });
    });
    const chosen = {
      ...connection,
      details: {
        ...connection.details,
        libraries: [
          { id: "v-films", name: "Films", kind: "movies", selected: true },
          { id: "v-kids", name: "Kids", kind: "mixed", selected: false },
        ],
      },
    };

    await servers.syncMediaServer(chosen as never, logger);

    const parents = fetchMock.mock.calls
      .map(([input]) => new URL(input as string))
      .filter((url) => url.pathname === "/Items")
      .map((url) => url.searchParams.get("parentId"));
    // Films was chosen, Kids wasn't, and TV is new on the server so it's read.
    expect(new Set(parents)).toEqual(new Set(["v-films", "v-tv"]));
    expect(connections.updateConnectionState).toHaveBeenCalledWith("c1", { movies: { tt1: "m1" }, episodes: {} });
    expect(connections.updateConnectionDetails).toHaveBeenCalledWith(
      "c1",
      expect.objectContaining({
        libraries: [
          { id: "v-films", name: "Films", kind: "movies", selected: true },
          { id: "v-tv", name: "TV", kind: "shows", selected: true },
          { id: "v-kids", name: "Kids", kind: "mixed", selected: false },
        ],
      })
    );
  });

  it("after the first pass, only imports what the server dated since the last one", async () => {
    fetchMock.mockImplementation(async (input: string) => {
      if (new URL(input).pathname === "/UserViews") return json({ Items: VIEWS });
      const type = new URL(input).searchParams.get("includeItemTypes");
      if (type !== "Movie") return json({ Items: [], TotalRecordCount: 0 });
      return json({
        Items: [
          { Id: "old", ProviderIds: { Imdb: "tt1" }, UserData: { Played: true, LastPlayedDate: "2025-06-01T00:00:00Z" } },
          { Id: "new", ProviderIds: { Imdb: "tt2" }, UserData: { Played: true, LastPlayedDate: "2026-01-10T00:00:00Z" } },
          { Id: "undated", ProviderIds: { Imdb: "tt3" }, UserData: { Played: true } },
        ],
        TotalRecordCount: 3,
      });
    });

    await servers.syncMediaServer(
      { ...connection, details: { ...connection.details, lastSyncedAt: "2026-01-05T00:00:00Z" } } as never,
      logger
    );

    const [, watches] = importTrackerItems.mock.calls[0] as unknown as [string, { imdbId: string }[]];
    expect(watches.map((watch) => watch.imdbId)).toEqual(["tt2"]);
    // The index still covers everything, for marking plays.
    expect(connections.updateConnectionState).toHaveBeenCalledWith("c1", {
      movies: { tt1: "old", tt2: "new", tt3: "undated" },
      episodes: {},
    });
  });

  it("only indexes when importing watch progress is off", async () => {
    fetchMock.mockImplementation(async () => json({ Items: [], TotalRecordCount: 0 }));

    await servers.syncMediaServer({ ...connection, details: { ...connection.details, importWatchState: false } } as never, logger);

    expect(importTrackerItems).not.toHaveBeenCalled();
    expect(connections.updateConnectionState).toHaveBeenCalled();
  });
});

describe("syncing a Plex server", () => {
  it("skips the sections that weren't chosen", async () => {
    fetchMock.mockImplementation(async (input: string) => {
      const url = new URL(input);
      if (url.pathname === "/library/sections") {
        return json({
          MediaContainer: {
            Directory: [
              { key: "1", type: "movie", title: "Films" },
              { key: "3", type: "show", title: "Kids" },
            ],
          },
        });
      }
      if (url.pathname === "/library/sections/1/all") {
        return json({ MediaContainer: { Metadata: [{ ratingKey: "42", Guid: [{ id: "imdb://tt1" }], viewCount: 1, lastViewedAt: 1767225600 }] } });
      }
      throw new Error(`unexpected ${url}`);
    });
    const connection = {
      id: "p1",
      profileId: PROFILE,
      provider: "plex",
      credentials: { token: "t" },
      details: {
        serverName: "Basement",
        address: PUBLIC,
        serverId: "srv-1",
        userId: "99",
        userName: "sam",
        importWatchState: true,
        libraries: [
          { id: "1", name: "Films", kind: "movies", selected: true },
          { id: "3", name: "Kids", kind: "shows", selected: false },
        ],
      },
      state: {},
    };

    await servers.syncMediaServer(connection as never, logger);

    expect(fetchMock.mock.calls.map(([input]) => new URL(input as string).pathname)).not.toContain("/library/sections/3/all");
    expect(connections.updateConnectionState).toHaveBeenCalledWith("p1", { movies: { tt1: "42" }, episodes: {} });
  });
});

describe("mergeLibraries", () => {
  it("keeps the choices for libraries seen before, starts new ones on, and drops gone ones", () => {
    expect(
      servers.mergeLibraries(
        [
          { id: "a", name: "A", kind: "movies", selected: false },
          { id: "gone", name: "Gone", kind: "shows", selected: false },
        ],
        [
          { id: "a", name: "A renamed", kind: "movies" },
          { id: "b", name: "B", kind: "shows" },
        ]
      )
    ).toEqual([
      { id: "a", name: "A renamed", kind: "movies", selected: false },
      { id: "b", name: "B", kind: "shows", selected: true },
    ]);
  });
});

describe("marking plays on the servers", () => {
  it("marks a movie played on each server that has it, by the index", async () => {
    connections.listProfileConnections.mockImplementation(async (_profileId: string, provider: string) =>
      provider === "plex"
        ? [
            {
              id: "p1",
              profileId: PROFILE,
              provider: "plex",
              credentials: { token: "t" },
              details: { address: PUBLIC, userId: "99" },
              state: { movies: { tt1: "4242" } },
            },
          ]
        : []
    );
    fetchMock.mockResolvedValue(json({}));

    await servers.pushPlayedToMediaServers(PROFILE, { type: "movie", imdbId: "tt1" }, logger);
    await servers.pushPlayedToMediaServers(PROFILE, { type: "movie", imdbId: "tt-unknown" }, logger);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(urlOf(0).pathname).toBe("/:/scrobble");
    expect(urlOf(0).searchParams.get("key")).toBe("4242");
  });
});
