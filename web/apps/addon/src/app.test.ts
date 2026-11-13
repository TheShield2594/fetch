import { createHmac } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { first } from "./test-fixtures/present.js";

const API_TOKEN = "test-api-token";
const API_BASE = "http://api.test";
const ADDON_BASE = "http://addon.test";

// The oldest profile — what the API's getDefaultProfileId picks, and therefore
// what an addon URL with no /p/<uuid> prefix must resolve to.
const DEFAULT_PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const OTHER_PROFILE_ID = "22222222-2222-4222-8222-222222222222";

const MUTATION_TOKEN = createHmac("sha256", API_TOKEN).update("fetch-addon-mutation").digest("hex");
/** A profile's Stremio key, as Settings hands it out: the same HMAC the API's own addon uses. */
const profileKey = (profileId: string, generation = 0) =>
  createHmac("sha256", API_TOKEN)
    .update(generation > 0 ? `fetch-stremio-addon:${profileId}:${generation}` : `fetch-stremio-addon:${profileId}`)
    .digest("hex");
const signedPrefix = (profileId: string) => `/u/${profileId}/${profileKey(profileId)}`;

process.env.FETCH_API_TOKEN = API_TOKEN;
process.env.FETCH_API_BASE = API_BASE;
process.env.ADDON_PUBLIC_BASE = ADDON_BASE;
process.env.LOG_LEVEL = "silent";

type Call = { url: string; method: string; headers: Record<string, string>; body: unknown };

const WATCHLIST_ID = "list-1";
const CUSTOM_LIST_ID = "aaaaaaaa-1111-4111-8111-111111111111";

type AddonConfigStub = { enabledCatalogs: string[]; aiConfigured: boolean };

let calls: Call[] = [];
let addonConfig: AddonConfigStub;
let addonConfigStatus = 200;
let lists: { id: string; name: string; kind: string }[];
let genresBody: unknown;
/** What `GET /auth/status` says to the API token: a status, or the field's value. */
let multipleAccounts: boolean | "error" = false;
/** Each profile's Stremio key generation, as the API reports it; unknown ids 404. */
let keyGenerations: Record<string, number>;

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
  const url = String(input);
  const headers = (init?.headers ?? {}) as Record<string, string>;
  calls.push({
    url,
    method: init?.method ?? "GET",
    headers,
    body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
  });

  const { pathname, searchParams } = new URL(url);

  if (pathname === "/auth/status") {
    if (multipleAccounts === "error") return jsonResponse({ error: "boom" }, 500);
    return jsonResponse({ setupRequired: false, multipleAccounts });
  }
  const generationMatch = /^\/profiles\/([^/]+)\/addon-url\/generation$/.exec(pathname);
  if (generationMatch) {
    const generation = keyGenerations[generationMatch[1]!];
    return generation === undefined ? jsonResponse({ error: "Not found" }, 404) : jsonResponse({ generation });
  }
  if (pathname === "/profiles") {
    return jsonResponse({ profiles: [{ id: DEFAULT_PROFILE_ID }, { id: OTHER_PROFILE_ID }] });
  }
  if (pathname.startsWith("/scrobble/")) return jsonResponse({ ok: true, action: pathname.slice(10) });
  if (pathname === "/lists") return jsonResponse({ lists });
  if (pathname === "/genres") return jsonResponse(genresBody);
  if (pathname === "/addon/config") {
    if (addonConfigStatus !== 200) return jsonResponse({ error: "boom" }, addonConfigStatus);
    return jsonResponse({
      config: { enabledCatalogs: addonConfig.enabledCatalogs },
      aiConfigured: addonConfig.aiConfigured,
    });
  }
  if (pathname === "/rpdb/config") return jsonResponse({ enabled: false, apiKey: null });
  if (pathname === "/settings/preferences") return jsonResponse({ spoilerProtection: false });
  if (pathname.startsWith("/lists/") && pathname.endsWith("/items")) {
    // Mirrors the real route: the list holds one of each type, and ?type=
    // narrows it server-side rather than shipping both and discarding half.
    const items = [
      { imdbId: "tt0111161", type: "movie", title: "Shawshank", metadata: null },
      { imdbId: "tt0903747", type: "series", title: "Breaking Bad", metadata: null },
    ];
    const type = searchParams.get("type");
    return jsonResponse({ items: type ? items.filter((item) => item.type === type) : items });
  }
  if (
    pathname === "/trending" ||
    pathname === "/popular" ||
    pathname === "/anime" ||
    pathname === "/streaming" ||
    pathname === "/recommendations/personal" ||
    pathname === "/recommendations/ai"
  ) {
    return jsonResponse({ metas: [{ id: `tt-${searchParams.get("type")}`, type: searchParams.get("type"), name: "X" }] });
  }

  return jsonResponse({ error: `unstubbed ${pathname}` }, 404);
});

const callsTo = (pathname: string) => calls.filter((call) => new URL(call.url).pathname === pathname);

let app: FastifyInstance;

const buildApp = async (): Promise<FastifyInstance> => {
  vi.resetModules();
  const module = await import("./app.js");
  await module.app.ready();
  return module.app;
};

beforeEach(async () => {
  calls = [];
  addonConfig = { enabledCatalogs: [], aiConfigured: false };
  addonConfigStatus = 200;
  lists = [{ id: WATCHLIST_ID, name: "Watchlist", kind: "watchlist" }];
  genresBody = { genres: ["Drama"] };
  multipleAccounts = false;
  keyGenerations = { [DEFAULT_PROFILE_ID]: 0, [OTHER_PROFILE_ID]: 0 };
  fetchMock.mockClear();
  vi.stubGlobal("fetch", fetchMock);
  app = await buildApp();
});

afterEach(async () => {
  await app.close();
  vi.unstubAllGlobals();
});

// The API and this service used to keep separate copies of the catalog list and
// separate ideas of what the shared config meant, so a catalog could be offered
// in Settings, honoured by one manifest and silently dropped by the other. Both
// now derive from the registry in @fetch/shared.
describe("manifest catalogs follow the profile's configuration", () => {
  type ManifestCatalog = { id: string; type: string; name: string };

  const manifestCatalogs = async (): Promise<ManifestCatalog[]> => {
    // Rebuilt per case: the manifest and the config it reads are both cached
    // for a minute, and the stubs change between cases.
    const fresh = await buildApp();
    try {
      const response = await fresh.inject({ method: "GET", url: "/manifest.json" });
      return (response.json() as { catalogs: ManifestCatalog[] }).catalogs;
    } finally {
      await fresh.close();
    }
  };

  const idsOf = async () => (await manifestCatalogs()).map((c) => c.id);

  it("advertises the AI Picks catalogs Settings offers, once an AI provider exists", async () => {
    addonConfig = { enabledCatalogs: ["fetch-ai-movie", "fetch-ai-series"], aiConfigured: true };

    const catalogs = await manifestCatalogs();

    expect(catalogs.map((c) => c.id)).toEqual(
      expect.arrayContaining(["fetch-ai-movie", "fetch-ai-series"])
    );
    // The row title comes from the shared registry, so Stremio and Settings
    // can't name the same catalog differently.
    expect(catalogs.find((c) => c.id === "fetch-ai-movie")?.name).toBe("AI Picks — Movies");
  });

  it("drops the AI catalogs when no AI provider is configured", async () => {
    addonConfig = { enabledCatalogs: ["fetch-ai-movie", "fetch-trending-movie"], aiConfigured: false };

    const ids = await idsOf();

    expect(ids).not.toContain("fetch-ai-movie");
    expect(ids).toContain("fetch-trending-movie");
  });

  it("advertises every catalog family the picker offers, not just trending and popular", async () => {
    addonConfig = {
      enabledCatalogs: ["fetch-anime-series", "fetch-netflix-movie", "fetch-recommended-movie"],
      aiConfigured: false,
    };

    expect(await idsOf()).toEqual(
      expect.arrayContaining(["fetch-anime-series", "fetch-netflix-movie", "fetch-recommended-movie"])
    );
  });

  it("leaves a custom list out until the profile enables it", async () => {
    lists = [
      { id: WATCHLIST_ID, name: "Watchlist", kind: "watchlist" },
      { id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" },
    ];
    addonConfig = { enabledCatalogs: [], aiConfigured: false };

    const ids = await idsOf();

    expect(ids).not.toContain(`fetch-${CUSTOM_LIST_ID}-movie`);
    // The watchlist has no checkbox in Settings, so it is never gated on one.
    expect(ids).toContain(`fetch-${WATCHLIST_ID}-movie`);
  });

  it("adds the custom list back once it is ticked", async () => {
    lists = [
      { id: WATCHLIST_ID, name: "Watchlist", kind: "watchlist" },
      { id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" },
    ];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };

    expect(await idsOf()).toEqual(
      expect.arrayContaining([`fetch-${CUSTOM_LIST_ID}-movie`, `fetch-${CUSTOM_LIST_ID}-series`])
    );
  });

  it("keeps a Stremio home screen populated when the config can't be read", async () => {
    // Nothing cached and no config to read: advertising a superset beats
    // emptying every row the user installed the addon for.
    addonConfigStatus = 500;

    const ids = await idsOf();

    expect(ids).toContain("fetch-trending-movie");
    expect(ids).not.toContain("fetch-ai-movie");
  });
});

describe("catalog routes serve only what the manifest advertised", () => {
  it("fetches AI catalog rows from the API's AI recommendations endpoint", async () => {
    addonConfig = { enabledCatalogs: ["fetch-ai-movie"], aiConfigured: true };
    const fresh = await buildApp();

    try {
      const response = await fresh.inject({ method: "GET", url: "/catalog/movie/fetch-ai-movie.json" });

      expect(response.json().metas).toHaveLength(1);
      expect(callsTo("/recommendations/ai")).toHaveLength(1);
    } finally {
      await fresh.close();
    }
  });

  it("returns nothing for a discovery catalog the profile disabled", async () => {
    addonConfig = { enabledCatalogs: ["fetch-popular-movie"], aiConfigured: false };
    const fresh = await buildApp();

    try {
      const response = await fresh.inject({ method: "GET", url: "/catalog/movie/fetch-trending-movie.json" });

      expect(response.json()).toEqual({ metas: [] });
      expect(callsTo("/trending")).toHaveLength(0);
    } finally {
      await fresh.close();
    }
  });

  it("returns nothing for a custom list the profile unticked", async () => {
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [], aiConfigured: false };
    const fresh = await buildApp();

    try {
      const response = await fresh.inject({
        method: "GET",
        url: `/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });

      expect(response.json()).toEqual({ metas: [] });
      expect(callsTo(`/lists/${CUSTOM_LIST_ID}/items`)).toHaveLength(0);
    } finally {
      await fresh.close();
    }
  });

  it("still serves an enabled custom list", async () => {
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
    const fresh = await buildApp();

    try {
      const response = await fresh.inject({
        method: "GET",
        url: `/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });

      expect(response.json().metas).toEqual([
        expect.objectContaining({ id: "tt0111161", name: "Shawshank" }),
      ]);
    } finally {
      await fresh.close();
    }
  });

  it("asks the API for only the type the catalog is for", async () => {
    // Stremio requests one catalog per (list, type) pair, so an untyped fetch
    // ships both types and throws half of each response away.
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
    const fresh = await buildApp();

    try {
      await fresh.inject({ method: "GET", url: `/catalog/series/fetch-${CUSTOM_LIST_ID}-series.json` });

      const call = first(callsTo(`/lists/${CUSTOM_LIST_ID}/items`), "request to the list's items");
      expect(new URL(call.url).searchParams.get("type")).toBe("series");
    } finally {
      await fresh.close();
    }
  });

  it("serves a repeat catalog request from cache instead of refetching the list", async () => {
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
    const fresh = await buildApp();

    try {
      const url = `/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`;
      const first = await fresh.inject({ method: "GET", url });
      const second = await fresh.inject({ method: "GET", url });

      expect(second.json()).toEqual(first.json());
      expect(callsTo(`/lists/${CUSTOM_LIST_ID}/items`)).toHaveLength(1);
    } finally {
      await fresh.close();
    }
  });

  it("dates the list-items cache from when the answer arrived, not from when it was asked for", async () => {
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
    const fresh = await buildApp();
    const stubbedFetch = fetchMock.getMockImplementation()!;

    try {
      // Only Date is faked; the real timers still run, so `app.inject` and the
      // awaits inside it behave normally.
      vi.useFakeTimers({ toFake: ["Date"] });

      // An API call slower than the cache's own TTL. Stamping the entry from
      // before the call would file it already expired — so the catalog request
      // that follows refetches immediately, and a struggling API gets hammered
      // by the cache that exists to shield it.
      fetchMock.mockImplementation(async (input: string | URL, init?: RequestInit) => {
        const response = await stubbedFetch(input, init);
        if (new URL(String(input)).pathname.endsWith("/items")) {
          vi.setSystemTime(Date.now() + 45_000);
        }
        return response;
      });

      const url = `/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`;
      await fresh.inject({ method: "GET", url });
      await fresh.inject({ method: "GET", url });

      expect(callsTo(`/lists/${CUSTOM_LIST_ID}/items`)).toHaveLength(1);
    } finally {
      vi.useRealTimers();
      fetchMock.mockImplementation(stubbedFetch);
      await fresh.close();
    }
  });

  it("caches the two types of one list apart from each other", async () => {
    // One cache entry per list would serve the movie payload to the series
    // catalog, emptying it.
    lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
    addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
    const fresh = await buildApp();

    try {
      const movies = await fresh.inject({
        method: "GET",
        url: `/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });
      const series = await fresh.inject({
        method: "GET",
        url: `/catalog/series/fetch-${CUSTOM_LIST_ID}-series.json`,
      });

      expect(movies.json().metas).toEqual([expect.objectContaining({ id: "tt0111161", type: "movie" })]);
      expect(series.json().metas).toEqual([expect.objectContaining({ id: "tt0903747", type: "series" })]);
      expect(callsTo(`/lists/${CUSTOM_LIST_ID}/items`)).toHaveLength(2);
    } finally {
      await fresh.close();
    }
  });
});

describe("API response contracts", () => {
  it("treats a response of the wrong shape as a failure rather than serving undefined", async () => {
    // `response.json() as T` used to make this a manifest full of `undefined`
    // genre options; now the parser rejects it and the cached-value fallback
    // that every other API failure already uses takes over.
    genresBody = { genres: "Drama" };
    const fresh = await buildApp();

    try {
      const response = await fresh.inject({ method: "GET", url: "/manifest.json" });

      expect(response.statusCode).toBe(200);
      const genreExtra = (response.json() as {
        catalogs: { extra: { name: string; options?: string[] }[] }[];
      }).catalogs[0]?.extra.find((e) => e.name === "genre");
      expect(genreExtra?.options).toEqual(["A-Z", "Z-A"]);
    } finally {
      await fresh.close();
    }
  });
});

describe("profile scoping", () => {
  it("falls back to the oldest profile for an addon URL installed without a profile", async () => {
    await app.inject({
      method: "POST",
      url: "/scrobble/start",
      headers: { authorization: `Bearer ${MUTATION_TOKEN}` },
      payload: { type: "movie", imdbId: "tt0111161", progress: 12 },
    });

    const scrobbleCall = first(callsTo("/scrobble/start"), "request to /scrobble/start");
    expect(scrobbleCall.headers["x-profile-id"]).toBe(DEFAULT_PROFILE_ID);
  });

  it("offers the app icon as the addon's logo once the web UI has a public address", async () => {
    const before = await app.inject({ method: "GET", url: "/manifest.json" });
    // Stremio fetches the logo itself, so it stays absent rather than relative.
    expect(JSON.parse(before.body).logo).toBeUndefined();

    process.env.FETCH_WEB_PUBLIC = "https://fetch.example/";
    try {
      const configured = await buildApp();
      const response = await configured.inject({ method: "GET", url: "/manifest.json" });
      expect(JSON.parse(response.body).logo).toBe("https://fetch.example/icons/icon-192.png");
    } finally {
      delete process.env.FETCH_WEB_PUBLIC;
    }
  });

  it("sends Stremio's Configure button to the addon's own section of Settings", async () => {
    process.env.FETCH_WEB_PUBLIC = "https://fetch.example/";
    try {
      const configured = await buildApp();
      const response = await configured.inject({ method: "GET", url: "/configure" });
      expect(response.statusCode).toBe(302);
      expect(response.headers.location).toBe("https://fetch.example/settings?tab=integrations&section=addon");
    } finally {
      delete process.env.FETCH_WEB_PUBLIC;
    }
  });

  it("answers a meta request the API couldn't serve with a 502, not a 500", async () => {
    const stub = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      if (new URL(String(input)).pathname.startsWith("/meta/")) {
        throw new Error("Request to the API timed out after 10000ms");
      }
      return stub(input, init);
    });
    try {
      const response = await app.inject({ method: "GET", url: "/meta/movie/tt0111161.json" });
      expect(response.statusCode).toBe(502);
      expect(response.json()).toEqual({ meta: null });
    } finally {
      fetchMock.mockImplementation(stub);
    }
  });

  it("lists a series' episodes as videos so a client has something to play", async () => {
    const stub = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const { pathname } = new URL(String(input));
      if (pathname === "/meta/series/tt0903747") return jsonResponse({ name: "Breaking Bad", year: 2008 });
      if (pathname === "/meta/series/tt0903747/seasons") {
        return jsonResponse({
          seasons: [
            { seasonNumber: 0, name: "Specials", episodeCount: 3, airYear: null, poster: null },
            { seasonNumber: 2, name: "Season 2", episodeCount: 2, airYear: 2009, poster: null },
            { seasonNumber: 1, name: "Season 1", episodeCount: 2, airYear: 2008, poster: null },
          ],
        });
      }
      if (pathname === "/meta/series/tt0903747/season/1/episodes") {
        return jsonResponse({
          episodes: [
            { episodeNumber: 2, name: "Cat's in the Bag...", airDate: "2008-01-27", still: null, runtime: 48 },
            { episodeNumber: 1, name: "Pilot", airDate: "2008-01-20", still: "https://img.test/1.jpg", runtime: 58 },
          ],
        });
      }
      // Season 2's episodes can't be read: they are still listed, numbered from its count.
      if (pathname === "/meta/series/tt0903747/season/2/episodes") return jsonResponse({ error: "boom" }, 500);
      return stub(input, init);
    });
    try {
      const response = await app.inject({ method: "GET", url: "/meta/series/tt0903747.json" });
      expect(response.statusCode).toBe(200);
      const { meta } = response.json() as { meta: { videos: Record<string, unknown>[] } };
      expect(meta.videos).toEqual([
        {
          id: "tt0903747:1:1",
          title: "Pilot",
          season: 1,
          episode: 1,
          released: "2008-01-20T00:00:00.000Z",
          thumbnail: "https://img.test/1.jpg",
        },
        { id: "tt0903747:1:2", title: "Cat's in the Bag...", season: 1, episode: 2, released: "2008-01-27T00:00:00.000Z" },
        { id: "tt0903747:2:1", title: "Episode 1", season: 2, episode: 1 },
        { id: "tt0903747:2:2", title: "Episode 2", season: 2, episode: 2 },
      ]);
      expect(callsTo("/meta/series/tt0903747/season/0/episodes")).toHaveLength(0);
    } finally {
      fetchMock.mockImplementation(stub);
    }
  });

  it("numbers a season's episodes when none of those it got has a usable number", async () => {
    const stub = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      const { pathname } = new URL(String(input));
      if (pathname === "/meta/series/tt1") return jsonResponse({ name: "Show" });
      if (pathname === "/meta/series/tt1/seasons") {
        return jsonResponse({ seasons: [{ seasonNumber: 1, name: "Season 1", episodeCount: 2, airYear: null, poster: null }] });
      }
      if (pathname === "/meta/series/tt1/season/1/episodes") {
        return jsonResponse({ episodes: [{ name: "No number", airDate: null, still: null, runtime: null }] });
      }
      return stub(input, init);
    });
    try {
      const response = await app.inject({ method: "GET", url: "/meta/series/tt1.json" });
      const { meta } = response.json() as { meta: { videos: { id: string }[] } };
      expect(meta.videos.map((video) => video.id)).toEqual(["tt1:1:1", "tt1:1:2"]);
    } finally {
      fetchMock.mockImplementation(stub);
    }
  });

  it("leaves videos off a movie meta", async () => {
    const stub = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) => {
      if (new URL(String(input)).pathname === "/meta/movie/tt0111161") return jsonResponse({ name: "Shawshank" });
      return stub(input, init);
    });
    try {
      const response = await app.inject({ method: "GET", url: "/meta/movie/tt0111161.json" });
      expect(response.statusCode).toBe(200);
      expect((response.json() as { meta: Record<string, unknown> }).meta.videos).toBeUndefined();
      expect(calls.some((call) => new URL(call.url).pathname.includes("/seasons"))).toBe(false);
    } finally {
      fetchMock.mockImplementation(stub);
    }
  });

  it("scopes catalog reads to the profile in the addon URL", async () => {
    await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/manifest.json` });

    const listsCall = first(callsTo("/lists"), "request to /lists");
    expect(listsCall.headers["x-profile-id"]).toBe(OTHER_PROFILE_ID);
  });

  it("forwards the profile on scrobble events", async () => {
    const response = await app.inject({
      method: "POST",
      url: `/p/${OTHER_PROFILE_ID}/scrobble/start`,
      headers: { authorization: `Bearer ${MUTATION_TOKEN}` },
      payload: { type: "movie", imdbId: "tt0111161", progress: 12 },
    });

    expect(response.statusCode).toBe(200);
    const scrobbleCall = first(callsTo("/scrobble/start"), "request to /scrobble/start");
    expect(scrobbleCall.headers["x-profile-id"]).toBe(OTHER_PROFILE_ID);
  });

  it("refuses a malformed profile id rather than writing to the default profile", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/p/not-a-uuid/scrobble/start",
      headers: { authorization: `Bearer ${MUTATION_TOKEN}` },
      payload: { type: "movie", imdbId: "tt0111161", progress: 12 },
    });

    expect(response.statusCode).toBe(404);
    expect(callsTo("/scrobble/start")).toHaveLength(0);
  });
});

describe("manifest resources", () => {
  // Fetch serves catalogs and metadata only. It provides no streams or
  // subtitles, and watches are tracked by Fetch itself rather than inferred
  // from what a client asks the addon for.
  it("declares catalogs and metadata, and nothing else", async () => {
    const response = await app.inject({ method: "GET", url: "/manifest.json" });
    expect(JSON.parse(response.body).resources).toEqual(["catalog", "meta"]);
  });
});

describe("signed addon URLs", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("serves a profile at its signed URL", async () => {
    const response = await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` });

    expect(response.statusCode).toBe(200);
  });

  it("answers a wrong key, or another profile's, as an unknown profile", async () => {
    const wrong = await app.inject({ method: "GET", url: `/u/${OTHER_PROFILE_ID}/${"0".repeat(64)}/manifest.json` });
    const borrowed = await app.inject({
      method: "GET",
      url: `/u/${OTHER_PROFILE_ID}/${profileKey(DEFAULT_PROFILE_ID)}/manifest.json`,
    });

    expect(wrong.statusCode).toBe(404);
    expect(borrowed.statusCode).toBe(404);
  });

  it("turns a replaced URL away at once and serves the new one", async () => {
    const oldUrl = `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json`;
    expect((await app.inject({ method: "GET", url: oldUrl })).statusCode).toBe(200);

    // Settings → "Change this profile's addon URL" bumps the generation.
    keyGenerations[OTHER_PROFILE_ID] = 1;

    expect((await app.inject({ method: "GET", url: oldUrl })).statusCode).toBe(404);
    const fresh = await app.inject({
      method: "GET",
      url: `/u/${OTHER_PROFILE_ID}/${profileKey(OTHER_PROFILE_ID, 1)}/manifest.json`,
    });
    expect(fresh.statusCode).toBe(200);
  });

  it("answers a profile the API doesn't know as unknown", async () => {
    delete keyGenerations[OTHER_PROFILE_ID];
    const response = await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` });
    expect(response.statusCode).toBe(404);
  });

  describe("on a PIN-protected profile", () => {
    const KEY_HEADER = "x-fetch-stremio-profile-key";

    // What the API does for a profile with a PIN: anything that acts as it
    // answers 401 unless the request carries a profile token, or — from this
    // service — the profile's signed-URL key.
    beforeEach(() => {
      lists = [{ id: CUSTOM_LIST_ID, name: "Weekend", kind: "custom" }];
      addonConfig = { enabledCatalogs: [`list:${CUSTOM_LIST_ID}`], aiConfigured: false };
      const stubbedFetch = fetchMock.getMockImplementation()!;
      fetchMock.mockImplementation(async (input: string | URL, init?: RequestInit) => {
        const headers = (init?.headers ?? {}) as Record<string, string>;
        if (headers["x-profile-id"] === OTHER_PROFILE_ID && headers[KEY_HEADER] !== profileKey(OTHER_PROFILE_ID)) {
          calls.push({ url: String(input), method: init?.method ?? "GET", headers, body: undefined });
          return jsonResponse({ error: "Profile PIN verification required", code: "profile_verification_required" }, 401);
        }
        return stubbedFetch(input, init);
      });
    });

    it("forwards the signed URL's key, so the install works", async () => {
      const manifest = await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` });
      expect(manifest.statusCode).toBe(200);
      expect(first(callsTo("/lists"), "request to /lists").headers[KEY_HEADER]).toBe(profileKey(OTHER_PROFILE_ID));

      const catalog = await app.inject({
        method: "GET",
        url: `${signedPrefix(OTHER_PROFILE_ID)}/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });
      expect(catalog.json<{ metas: unknown[] }>().metas).not.toHaveLength(0);

      const scrobble = await app.inject({
        method: "POST",
        url: `${signedPrefix(OTHER_PROFILE_ID)}/scrobble/start`,
        headers: { authorization: `Bearer ${MUTATION_TOKEN}` },
        payload: { type: "movie", imdbId: "tt0111161", progress: 12 },
      });
      expect(scrobble.statusCode).toBe(200);
      expect(first(callsTo("/scrobble/start"), "request to /scrobble/start").headers[KEY_HEADER]).toBe(profileKey(OTHER_PROFILE_ID));
    });

    it("sends no key for the unsigned /p/ URL, which stays locked out", async () => {
      const manifest = await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/manifest.json` });
      expect(manifest.statusCode).toBe(502);
      expect(calls.every((call) => call.headers[KEY_HEADER] === undefined)).toBe(true);
      const listsCall = first(callsTo("/lists"), "request to /lists");
      expect(listsCall.headers["x-profile-id"]).toBe(OTHER_PROFILE_ID);
      expect(listsCall.headers[KEY_HEADER]).toBeUndefined();

      await app.inject({
        method: "POST",
        url: `/p/${OTHER_PROFILE_ID}/scrobble/start`,
        headers: { authorization: `Bearer ${MUTATION_TOKEN}` },
        payload: { type: "movie", imdbId: "tt0111161", progress: 12 },
      });
      const scrobbleCall = first(callsTo("/scrobble/start"), "request to /scrobble/start");
      expect(scrobbleCall.headers["x-profile-id"]).toBe(OTHER_PROFILE_ID);
      expect(scrobbleCall.headers[KEY_HEADER]).toBeUndefined();
    });

    it("never serves the unsigned URL what a signed request cached", async () => {
      expect((await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` })).statusCode).toBe(200);
      const signedCatalog = await app.inject({
        method: "GET",
        url: `${signedPrefix(OTHER_PROFILE_ID)}/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });
      expect(signedCatalog.json<{ metas: unknown[] }>().metas).not.toHaveLength(0);

      expect((await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/manifest.json` })).statusCode).toBe(502);
      const unsignedCatalog = await app.inject({
        method: "GET",
        url: `/p/${OTHER_PROFILE_ID}/catalog/movie/fetch-${CUSTOM_LIST_ID}-movie.json`,
      });
      expect(unsignedCatalog.json()).toEqual({ metas: [] });
    });
  });

  describe("by default, once a second account exists", () => {
    beforeEach(async () => {
      multipleAccounts = true;
      app = await buildApp();
    });

    it("turns away the URLs that carry no key", async () => {
      expect((await app.inject({ method: "GET", url: "/manifest.json" })).statusCode).toBe(404);
      expect((await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/manifest.json` })).statusCode).toBe(404);
    });

    it("still serves the signed URL", async () => {
      expect((await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` })).statusCode).toBe(200);
    });
  });

  describe("by default, when the account check fails", () => {
    beforeEach(async () => {
      multipleAccounts = "error";
      app = await buildApp();
    });

    it("refuses the keyless URLs rather than guessing", async () => {
      expect((await app.inject({ method: "GET", url: "/manifest.json" })).statusCode).toBe(404);
    });
  });

  describe("with ADDON_REQUIRE_SIGNED_URLS=false", () => {
    beforeEach(async () => {
      vi.stubEnv("ADDON_REQUIRE_SIGNED_URLS", "false");
      multipleAccounts = true;
      app = await buildApp();
    });
    afterEach(() => {
      vi.unstubAllEnvs();
    });

    it("keeps serving the keyless URLs, as it was told to", async () => {
      expect((await app.inject({ method: "GET", url: "/manifest.json" })).statusCode).toBe(200);
      expect(callsTo("/auth/status")).toHaveLength(0);
    });
  });

  describe("with ADDON_REQUIRE_SIGNED_URLS", () => {
    beforeEach(async () => {
      vi.stubEnv("ADDON_REQUIRE_SIGNED_URLS", "true");
      app = await buildApp();
    });

    it("turns away the URLs that carry no key", async () => {
      expect((await app.inject({ method: "GET", url: "/manifest.json" })).statusCode).toBe(404);
      expect((await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/manifest.json` })).statusCode).toBe(404);
      const catalog = await app.inject({ method: "GET", url: `/p/${OTHER_PROFILE_ID}/catalog/movie/fetch-${WATCHLIST_ID}-movie.json` });
      expect(catalog.json()).toEqual({ metas: [] });
    });

    it("still serves the signed URL", async () => {
      expect((await app.inject({ method: "GET", url: `${signedPrefix(OTHER_PROFILE_ID)}/manifest.json` })).statusCode).toBe(200);
    });
  });
});

describe("rate limiting", () => {
  it("gives each signed install its own budget, apart from the address's", async () => {
    // Spend the address's whole budget on the keyless URL…
    for (let i = 0; i < 300; i += 1) await app.inject({ method: "GET", url: "/health" });
    expect((await app.inject({ method: "GET", url: "/manifest.json" })).statusCode).toBe(429);

    // …and a signed install from the same address is still served.
    expect((await app.inject({ method: "GET", url: `${signedPrefix(DEFAULT_PROFILE_ID)}/manifest.json` })).statusCode).toBe(200);
  });

  it("counts a wrong key against the address, so ids can't be invented for fresh buckets", async () => {
    for (let i = 0; i < 300; i += 1) await app.inject({ method: "GET", url: "/health" });
    const wrongKey = `/u/${DEFAULT_PROFILE_ID}/${"0".repeat(64)}/manifest.json`;
    expect((await app.inject({ method: "GET", url: wrongKey })).statusCode).toBe(429);
  });
});

describe("manifest search", () => {
  it("declares search on list catalogs only", async () => {
    // An unreadable config advertises every non-AI discovery row, so there are some to check.
    addonConfigStatus = 500;
    const manifest = (await app.inject({ method: "GET", url: "/manifest.json" })).json<{
      catalogs: { id: string; extra: { name: string }[] }[];
    }>();
    const searchable = manifest.catalogs.filter((c) => c.extra.some((e) => e.name === "search")).map((c) => c.id);
    expect(searchable.length).toBeGreaterThan(0);
    expect(searchable.every((id) => id.startsWith(`fetch-${WATCHLIST_ID}-`))).toBe(true);
    expect(manifest.catalogs.length).toBeGreaterThan(searchable.length);
  });
});
