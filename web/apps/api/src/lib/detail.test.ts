import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = { metadata: { findUnique: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const fetchMetadata = vi.fn();
vi.mock("./metadata.js", () => ({ fetchMetadata: (...a: unknown[]) => fetchMetadata(...a) }));
vi.mock("./tmdb-client.js", () => ({ getTmdb: vi.fn() }));
vi.mock("./rpdb.js", () => ({
  getRpdbApiKey: async () => null,
  withRpdbPoster: (_id: string, poster: string | null) => poster,
}));
vi.mock("./settings.js", () => ({ getRegionSetting: async () => "US" }));

const { loadMeta } = await import("./detail.js");
const { detailRefetchCooldownCache } = await import("./cache.js");

const row = (over: Record<string, unknown> = {}) => ({
  imdbId: "tt1",
  type: "movie",
  name: "A Film",
  poster: null,
  runtime: 100,
  releaseDate: "2020-01-01",
  network: null,
  imdbRating: 7.1,
  rtScore: null,
  mcScore: null,
  ...over,
});

describe("loadMeta", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    detailRefetchCooldownCache.clear();
  });

  it("serves a complete row without asking TMDB", async () => {
    prismaMock.metadata.findUnique.mockResolvedValue(row());

    expect(await loadMeta("movie", "tt1")).toMatchObject({ name: "A Film" });
    expect(fetchMetadata).not.toHaveBeenCalled();
  });

  it("refetches a row with no scores once, then serves it from the row", async () => {
    // What every title looks like on an install with no OMDb key saved.
    prismaMock.metadata.findUnique.mockResolvedValue(row({ imdbRating: null }));
    fetchMetadata.mockResolvedValue({ name: "A Film", poster: null });

    await loadMeta("movie", "tt1");
    await loadMeta("movie", "tt1");
    await loadMeta("movie", "tt1");

    expect(fetchMetadata).toHaveBeenCalledTimes(1);
  });

  it("serves the row it has when TMDB fails, and asks again next time", async () => {
    prismaMock.metadata.findUnique.mockResolvedValue(row({ runtime: null }));
    fetchMetadata.mockRejectedValue(new Error("tmdb down"));

    expect(await loadMeta("movie", "tt1")).toMatchObject({ name: "A Film" });
    await loadMeta("movie", "tt1");

    expect(fetchMetadata).toHaveBeenCalledTimes(2);
  });

  it("still fails for a title it has nothing to show for", async () => {
    prismaMock.metadata.findUnique.mockResolvedValue(null);
    fetchMetadata.mockRejectedValue(new Error("tmdb down"));

    await expect(loadMeta("movie", "tt1")).rejects.toThrow("tmdb down");
  });

  it("returns null for a title TMDB doesn't know and no row holds", async () => {
    prismaMock.metadata.findUnique.mockResolvedValue(null);
    fetchMetadata.mockResolvedValue(null);

    expect(await loadMeta("movie", "tt1")).toBeNull();
  });
});
