import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

const connections = {
  getConnection: vi.fn(),
  saveConnection: vi.fn(),
  deleteConnection: vi.fn(),
};
vi.mock("./connections.js", () => connections);

const getProfileDocument = vi.fn();
vi.mock("./profile-document.js", () => ({ getProfileDocument }));

const accessTokenFor = vi.fn();
vi.mock("./mdblist.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./mdblist.js")>()),
  accessTokenFor,
}));
vi.mock("./prisma.js", () => ({ prisma: {} }));

const ratings = await import("./mdblist-ratings.js");

const logger = { warn: vi.fn(), info: vi.fn(), error: vi.fn(), debug: vi.fn() } as unknown as FastifyBaseLogger;
const PROFILE = "11111111-1111-4111-8111-111111111111";

const fetchMock = vi.fn();
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const requested = (index = 0) => {
  const [url, init] = fetchMock.mock.calls[index] as [URL, RequestInit];
  return { url: new URL(url), headers: init.headers as Record<string, string> };
};

const MEDIA = {
  ratings: [
    { source: "imdb", value: 8.1 },
    { source: "tomatoes", value: 92 },
    { source: "popcorn", value: 85 },
    { source: "letterboxd", value: 4.1, score: 84 },
    { source: "trakt", value: 81 },
    { source: "metacritic", value: null },
    { source: "rogerebert", value: 3.5 },
  ],
  keywords: [{ name: "certified-fresh" }, "tag.certified-hot"],
};

/** A TV settings document with MDBList ratings turned on, and anything else given. */
const settingsDocument = (extra: Record<string, unknown> = {}) => ({
  document: {
    features: { mdblist_settings: { mdblist_enabled: { type: "boolean", value: true }, ...extra } },
  },
  version: 1,
  updatedAt: new Date(),
});

const withKey = (apiKey = "key-123") =>
  connections.getConnection.mockImplementation(async (_profile: string, provider: string) =>
    provider === "mdblist-key" ? { credentials: { apiKey }, details: {} } : null
  );

beforeEach(() => {
  vi.clearAllMocks();
  ratings.resetMdbListRatingsCacheForTests();
  vi.stubGlobal("fetch", fetchMock);
  connections.getConnection.mockResolvedValue(null);
  accessTokenFor.mockResolvedValue(null);
  getProfileDocument.mockResolvedValue(settingsDocument());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("projectMdbListRatings", () => {
  it("normalises sources and scales the way the TV does", () => {
    expect(ratings.projectMdbListRatings(MEDIA)).toEqual({
      imdb: 8.1,
      tomatoes: 92,
      audience: 85,
      // From the 0–100 score, not the rounded value.
      letterboxd: 4.2,
      trakt: 81,
      tomatoesCertified: true,
      audienceCertified: true,
    });
  });

  it("drops a value outside its source's scale", () => {
    expect(ratings.projectMdbListRatings({ ratings: [{ source: "imdb", value: 81 }] })).toEqual({});
  });
});

describe("getMdbListRatings", () => {
  it("reads with the profile's key and returns the shown sources in the profile's order", async () => {
    withKey();
    getProfileDocument.mockResolvedValue(
      settingsDocument({
        mdblist_show_trakt: { type: "boolean", value: false },
        mdblist_rating_order: { type: "string", value: "tomatoes,imdb,trakt,tmdb,audience,letterboxd,metacritic,mal" },
      })
    );
    fetchMock.mockResolvedValueOnce(json(MEDIA));

    const result = await ratings.getMdbListRatings(PROFILE, "series", "tt0903747", logger);

    expect(result.map((rating) => rating.source)).toEqual(["tomatoes", "imdb", "audience", "letterboxd"]);
    expect(result[0]).toEqual({ source: "tomatoes", value: 92, certified: true });
    const { url, headers } = requested();
    expect(url.pathname).toBe("/imdb/show/tt0903747");
    expect(url.searchParams.get("apikey")).toBe("key-123");
    expect(url.searchParams.get("append_to_response")).toBe("keyword");
    expect(headers.Authorization).toBeUndefined();
  });

  it("falls back to the profile's MDBList sign-in when it has no key", async () => {
    accessTokenFor.mockResolvedValue("access-token");
    fetchMock.mockResolvedValueOnce(json(MEDIA));

    await ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger);

    const { url, headers } = requested();
    expect(url.pathname).toBe("/imdb/movie/tt0133093/");
    expect(url.searchParams.get("apikey")).toBeNull();
    expect(headers.Authorization).toBe("Bearer access-token");
  });

  it("asks MDBList nothing while ratings are off for the profile, as they start on the TV", async () => {
    withKey();
    getProfileDocument.mockResolvedValue(null);

    expect(await ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger)).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("asks MDBList nothing without a key or a sign-in", async () => {
    expect(await ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger)).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("serves a title's ratings from the cache once read, and shares a request already out", async () => {
    withKey();
    fetchMock.mockResolvedValue(json(MEDIA));

    await Promise.all([
      ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger),
      ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger),
    ]);
    await ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("treats a title MDBList doesn't know as having no ratings", async () => {
    withKey();
    fetchMock.mockResolvedValueOnce(json({ error: "not found" }, 404));

    expect(await ratings.getMdbListRatings(PROFILE, "movie", "tt0000001", logger)).toEqual([]);
  });

  it("doesn't cache a failure", async () => {
    withKey();
    fetchMock.mockResolvedValueOnce(json({}, 503)).mockResolvedValueOnce(json(MEDIA));

    await expect(ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger)).rejects.toThrow();
    expect(await ratings.getMdbListRatings(PROFILE, "movie", "tt0133093", logger)).not.toEqual([]);
  });
});

describe("saveMdbListKey", () => {
  it("checks the key with /user before keeping it", async () => {
    fetchMock.mockResolvedValueOnce(json({ username: "sam" }));

    await ratings.saveMdbListKey(PROFILE, "  key-123  ");

    const { url } = requested();
    expect(url.pathname).toBe("/user");
    expect(url.searchParams.get("apikey")).toBe("key-123");
    expect(connections.saveConnection).toHaveBeenCalledWith({
      profileId: PROFILE,
      provider: "mdblist-key",
      credentials: { apiKey: "key-123" },
      details: {},
    });
  });

  it("keeps nothing when MDBList turns the key down", async () => {
    fetchMock.mockResolvedValueOnce(json({ error: "Invalid API key" }, 403));

    await expect(ratings.saveMdbListKey(PROFILE, "wrong")).rejects.toBeInstanceOf(ratings.MdbListKeyRejected);
    expect(connections.saveConnection).not.toHaveBeenCalled();
  });

  it("doesn't call a key wrong because MDBList is down", async () => {
    fetchMock.mockResolvedValueOnce(json({}, 502));

    const failure = ratings.saveMdbListKey(PROFILE, "key-123");
    await expect(failure).rejects.toThrow();
    await expect(failure).rejects.not.toBeInstanceOf(ratings.MdbListKeyRejected);
    expect(connections.saveConnection).not.toHaveBeenCalled();
  });
});
