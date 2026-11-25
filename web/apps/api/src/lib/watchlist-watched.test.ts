import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = {
  list: { findFirst: vi.fn() },
  listItem: { findMany: vi.fn(), deleteMany: vi.fn() },
  metadata: { findUnique: vi.fn() },
  watchEvent: { findMany: vi.fn() },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const pushTraktWatchlistChange = vi.fn();
vi.mock("./trakt-client.js", () => ({
  pushTraktWatchlistChange: (...a: unknown[]) => pushTraktWatchlistChange(...a),
}));

const { removeWatchedFromWatchlist } = await import("./watchlist-watched.js");

const log = { info: vi.fn(), warn: vi.fn() } as never;
const episodes = (count: number) => Array.from({ length: count }, (_, i) => ({ season: 1, episode: i + 1 }));

describe("removeWatchedFromWatchlist", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.list.findFirst.mockResolvedValue({ id: "watchlist-1" });
    prismaMock.listItem.deleteMany.mockResolvedValue({ count: 1 });
  });

  afterEach(() => {
    delete process.env.WATCHLIST_REMOVE_WATCHED;
  });

  it("removes a watched movie and tells Trakt", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt1" }]);

    const removed = await removeWatchedFromWatchlist("p1", [{ type: "movie", imdbId: "tt1" }], log);

    expect(removed).toBe(1);
    expect(prismaMock.listItem.deleteMany).toHaveBeenCalledWith({
      where: { listId: "watchlist-1", type: "movie", imdbId: "tt1" },
    });
    expect(pushTraktWatchlistChange).toHaveBeenCalledWith("remove", { type: "movie", imdbId: "tt1" }, log, "p1");
  });

  it("does nothing more than the membership check for a title that isn't listed", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([]);

    expect(await removeWatchedFromWatchlist("p1", [{ type: "series", imdbId: "tt2" }], log)).toBe(0);

    expect(prismaMock.metadata.findUnique).not.toHaveBeenCalled();
    expect(prismaMock.listItem.deleteMany).not.toHaveBeenCalled();
  });

  it("leaves a series listed under the same id as a watched movie", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "tt1" }]);

    expect(await removeWatchedFromWatchlist("p1", [{ type: "movie", imdbId: "tt1" }], log)).toBe(0);
    expect(prismaMock.listItem.deleteMany).not.toHaveBeenCalled();
  });

  it("keeps a show that still has episodes left", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "tt2" }]);
    prismaMock.metadata.findUnique.mockResolvedValue({ totalEpisodes: 10 });
    prismaMock.watchEvent.findMany.mockResolvedValue(episodes(9));

    expect(await removeWatchedFromWatchlist("p1", [{ type: "series", imdbId: "tt2" }], log)).toBe(0);
    expect(prismaMock.listItem.deleteMany).not.toHaveBeenCalled();
  });

  it("removes a show once every episode has been watched", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "tt2" }]);
    prismaMock.metadata.findUnique.mockResolvedValue({ totalEpisodes: 10 });
    prismaMock.watchEvent.findMany.mockResolvedValue(episodes(10));

    expect(await removeWatchedFromWatchlist("p1", [{ type: "series", imdbId: "tt2" }], log)).toBe(1);
    expect(pushTraktWatchlistChange).toHaveBeenCalledWith("remove", { type: "series", imdbId: "tt2" }, log, "p1");
  });

  it("never calls a show finished when its episode count is unknown", async () => {
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "tt2" }]);
    prismaMock.metadata.findUnique.mockResolvedValue({ totalEpisodes: null });

    expect(await removeWatchedFromWatchlist("p1", [{ type: "series", imdbId: "tt2" }], log)).toBe(0);
    expect(prismaMock.listItem.deleteMany).not.toHaveBeenCalled();
  });

  it("does nothing when switched off", async () => {
    process.env.WATCHLIST_REMOVE_WATCHED = "false";

    expect(await removeWatchedFromWatchlist("p1", [{ type: "movie", imdbId: "tt1" }], log)).toBe(0);
    expect(prismaMock.list.findFirst).not.toHaveBeenCalled();
  });

  it("swallows a failure rather than failing the watch that triggered it", async () => {
    prismaMock.list.findFirst.mockRejectedValue(new Error("db down"));

    await expect(removeWatchedFromWatchlist("p1", [{ type: "movie", imdbId: "tt1" }], log)).resolves.toBe(0);
  });
});
