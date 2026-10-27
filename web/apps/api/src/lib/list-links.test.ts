import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

const prismaMock = {
  list: { findFirst: vi.fn() },
  listItem: { findMany: vi.fn(), createMany: vi.fn(), deleteMany: vi.fn() },
  item: { createMany: vi.fn() },
  listLink: { update: vi.fn(), upsert: vi.fn(), findMany: vi.fn(), findUnique: vi.fn(), deleteMany: vi.fn() },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const traktClient = {
  fetchPersonalLists: vi.fn(),
  fetchPersonalListItems: vi.fn(),
  addPersonalListItems: vi.fn(),
  removePersonalListItems: vi.fn(),
  createPersonalList: vi.fn(),
  renamePersonalList: vi.fn(),
};
const isTraktConnected = vi.fn();
vi.mock("./trakt-client.js", () => ({
  getTraktClient: async () => traktClient,
  isTraktConnected: (...args: unknown[]) => isTraktConnected(...args),
}));

const mdblist = {
  getMdbListStatus: vi.fn(),
  fetchMdbListUserLists: vi.fn(),
  fetchMdbListListItems: vi.fn(),
  changeMdbListListItems: vi.fn(),
  createMdbListList: vi.fn(),
  renameMdbListList: vi.fn(),
};
vi.mock("./mdblist.js", () => mdblist);

const { planListLinkSync, syncListLink, linkList, pushListLinkChange, syncProfileListLinks } = await import(
  "./list-links.js"
);

const logger = { warn: vi.fn(), info: vi.fn(), error: vi.fn(), debug: vi.fn() } as unknown as FastifyBaseLogger;
const PROFILE = "11111111-1111-4111-8111-111111111111";
const LIST = "22222222-2222-4222-8222-222222222222";

const link = (over: Record<string, unknown> = {}) => ({
  id: "44444444-4444-4444-8444-444444444444",
  listId: LIST,
  profileId: PROFILE,
  provider: "trakt",
  remoteId: "123",
  remoteName: "Weekend",
  snapshot: null as unknown,
  lastSyncedAt: null,
  lastError: null,
  createdAt: new Date(),
  updatedAt: new Date(),
  ...over,
});

const set = (...keys: string[]) => new Set(keys);

describe("planListLinkSync", () => {
  it("merges both sides on the first sync, removing nothing", () => {
    const plan = planListLinkSync(set("movie:tt1", "movie:tt2"), set("movie:tt2", "series:tt3"), null);
    expect(plan.addLocal).toEqual(["movie:tt1"]);
    expect(plan.pushAdd).toEqual(["series:tt3"]);
    expect(plan.removeLocal).toEqual([]);
    expect(plan.pushRemove).toEqual([]);
  });

  it("reads a key gone from one side since the last sync as a removal there", () => {
    const snapshot = set("movie:tt1", "movie:tt2", "movie:tt3");
    // tt1 was removed on the service, tt2 in Fetch; tt4 is new on the service, tt5 new in Fetch.
    const plan = planListLinkSync(set("movie:tt2", "movie:tt3", "movie:tt4"), set("movie:tt1", "movie:tt3", "movie:tt5"), snapshot);
    expect(plan.removeLocal).toEqual(["movie:tt1"]);
    expect(plan.pushRemove).toEqual(["movie:tt2"]);
    expect(plan.addLocal).toEqual(["movie:tt4"]);
    expect(plan.pushAdd).toEqual(["movie:tt5"]);
  });

  it("treats a service list that went entirely empty as a fault, not a removal of everything", () => {
    const plan = planListLinkSync(set(), set("movie:tt1", "movie:tt2"), set("movie:tt1", "movie:tt2"));
    expect(plan.remoteWentEmpty).toBe(true);
    expect(plan.removeLocal).toEqual([]);
    expect(plan.kept).toEqual(["movie:tt1", "movie:tt2"]);
  });

  it("does the same the other way round", () => {
    const plan = planListLinkSync(set("movie:tt1"), set(), set("movie:tt1"));
    expect(plan.localWentEmpty).toBe(true);
    expect(plan.pushRemove).toEqual([]);
    expect(plan.kept).toEqual(["movie:tt1"]);
  });
});

describe("syncListLink", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isTraktConnected.mockResolvedValue(true);
    traktClient.fetchPersonalLists.mockResolvedValue([{ ids: { trakt: 123 }, name: "Weekend", item_count: 2 }]);
    prismaMock.listItem.createMany.mockImplementation(async ({ data }: { data: unknown[] }) => ({ count: data.length }));
    prismaMock.listItem.deleteMany.mockResolvedValue({ count: 1 });
    prismaMock.item.createMany.mockResolvedValue({ count: 0 });
    prismaMock.listLink.update.mockResolvedValue({});
  });

  it("pulls service changes in, pushes Fetch changes out, and records what both now hold", async () => {
    traktClient.fetchPersonalListItems.mockResolvedValue([
      { movie: { ids: { imdb: "tt2" } } },
      { show: { ids: { imdb: "tt4" } } },
      { movie: { ids: {} } },
    ]);
    prismaMock.listItem.findMany.mockResolvedValue([
      { type: "movie", imdbId: "tt1" },
      { type: "movie", imdbId: "tt5" },
    ]);

    const result = await syncListLink(link({ snapshot: ["movie:tt1", "movie:tt2"] }) as never, logger);

    expect(result).toMatchObject({ status: "ok", addedLocally: 1, removedLocally: 1, pushedAdds: 1, pushedRemovals: 1 });
    expect(prismaMock.listItem.createMany).toHaveBeenCalledWith({
      data: [{ listId: LIST, type: "series", imdbId: "tt4" }],
      skipDuplicates: true,
    });
    expect(prismaMock.listItem.deleteMany).toHaveBeenCalledWith({ where: { listId: LIST, type: "movie", imdbId: { in: ["tt1"] } } });
    expect(traktClient.addPersonalListItems).toHaveBeenCalledWith(123, { movies: [{ ids: { imdb: "tt5" } }] }, logger);
    expect(traktClient.removePersonalListItems).toHaveBeenCalledWith(123, { movies: [{ ids: { imdb: "tt2" } }] }, logger);
    expect(prismaMock.listLink.update).toHaveBeenLastCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ snapshot: expect.arrayContaining(["series:tt4", "movie:tt5"]), lastError: null }),
      })
    );
    const snapshot = prismaMock.listLink.update.mock.calls.at(-1)![0].data.snapshot as string[];
    expect(snapshot.sort()).toEqual(["movie:tt5", "series:tt4"]);
  });

  it("keeps a failed push out of agreement so the next sync retries it", async () => {
    traktClient.fetchPersonalListItems.mockResolvedValue([{ movie: { ids: { imdb: "tt2" } } }]);
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "movie", imdbId: "tt5" }]);
    traktClient.addPersonalListItems.mockRejectedValue(new Error("Trakt request failed (503)"));
    traktClient.removePersonalListItems.mockRejectedValue(new Error("Trakt request failed (503)"));

    const result = await syncListLink(link({ snapshot: ["movie:tt2"] }) as never, logger);

    expect(result.status).toBe("partial");
    const data = prismaMock.listLink.update.mock.calls.at(-1)![0].data;
    // The add that failed is left out (pushed again next time); the removal
    // that failed stays in (read as a Fetch removal again, not a service add).
    expect(data.snapshot).toEqual(["movie:tt2"]);
    expect(data.lastError).toMatch(/503/);
  });

  it("never deletes the Fetch list's items when the service list is gone", async () => {
    traktClient.fetchPersonalLists.mockResolvedValue([]);

    const result = await syncListLink(link({ snapshot: ["movie:tt1"] }) as never, logger);

    expect(result.status).toBe("missing");
    expect(traktClient.fetchPersonalListItems).not.toHaveBeenCalled();
    expect(prismaMock.listItem.deleteMany).not.toHaveBeenCalled();
    expect(prismaMock.listLink.update).toHaveBeenCalledWith(
      expect.objectContaining({ data: { lastError: expect.stringMatching(/no longer exists/) } })
    );
  });

  it("does nothing while the service is disconnected", async () => {
    isTraktConnected.mockResolvedValue(false);

    const result = await syncListLink(link() as never, logger);

    expect(result.status).toBe("disconnected");
    expect(traktClient.fetchPersonalLists).not.toHaveBeenCalled();
  });

  it("reads and writes MDBList lists the same way", async () => {
    mdblist.getMdbListStatus.mockResolvedValue({ connected: true });
    mdblist.fetchMdbListUserLists.mockResolvedValue([{ id: "77", name: "Kids", itemCount: 1, private: true }]);
    mdblist.fetchMdbListListItems.mockResolvedValue([{ type: "movie", imdbId: "tt9" }]);
    prismaMock.listItem.findMany.mockResolvedValue([{ type: "series", imdbId: "tt8" }]);

    const result = await syncListLink(link({ provider: "mdblist", remoteId: "77" }) as never, logger);

    expect(result).toMatchObject({ status: "ok", addedLocally: 1, pushedAdds: 1 });
    expect(mdblist.changeMdbListListItems).toHaveBeenCalledWith(PROFILE, "77", "add", [{ type: "series", imdbId: "tt8" }]);
  });
});

describe("syncProfileListLinks", () => {
  beforeEach(() => vi.clearAllMocks());

  it("syncs every link and fails the job afterwards if one failed", async () => {
    isTraktConnected.mockResolvedValue(true);
    prismaMock.listLink.findMany.mockResolvedValue([link(), link({ id: "55555555-5555-4555-8555-555555555555", remoteId: "456" })]);
    prismaMock.listLink.update.mockResolvedValue({});
    traktClient.fetchPersonalLists
      .mockRejectedValueOnce(new Error("Trakt request failed (500)"))
      .mockResolvedValueOnce([{ ids: { trakt: 456 }, name: "Other" }]);
    traktClient.fetchPersonalListItems.mockResolvedValue([]);
    prismaMock.listItem.findMany.mockResolvedValue([]);

    await expect(syncProfileListLinks(PROFILE, "trakt", logger)).rejects.toThrow(/1 linked Trakt list/);
    expect(traktClient.fetchPersonalListItems).toHaveBeenCalledWith(456, logger);
  });
});

describe("linkList", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isTraktConnected.mockResolvedValue(true);
    prismaMock.listLink.update.mockResolvedValue({});
    prismaMock.listItem.findMany.mockResolvedValue([]);
    traktClient.fetchPersonalListItems.mockResolvedValue([]);
  });

  it("refuses the watchlist, which already syncs on its own", async () => {
    prismaMock.list.findFirst.mockResolvedValue({ id: LIST, profileId: PROFILE, kind: "watchlist", name: "Watchlist" });

    await expect(linkList(PROFILE, LIST, "trakt", "123", logger)).rejects.toMatchObject({ status: 409 });
    expect(prismaMock.listLink.upsert).not.toHaveBeenCalled();
  });

  it("creates a list on the service named after the Fetch list", async () => {
    prismaMock.list.findFirst.mockResolvedValue({ id: LIST, profileId: PROFILE, kind: "custom", name: "Weekend" });
    traktClient.createPersonalList.mockResolvedValue({ ids: { trakt: 999 }, name: "Weekend" });
    traktClient.fetchPersonalLists.mockResolvedValue([{ ids: { trakt: 999 }, name: "Weekend" }]);
    prismaMock.listLink.upsert.mockImplementation(async ({ create }: { create: Record<string, unknown> }) => link(create));

    const { link: created, sync } = await linkList(PROFILE, LIST, "trakt", "new", logger);

    expect(traktClient.createPersonalList).toHaveBeenCalledWith("Weekend", logger);
    expect(created.remoteId).toBe("999");
    expect(sync.status).toBe("ok");
  });

  it("refuses a list that isn't on the profile's own account", async () => {
    prismaMock.list.findFirst.mockResolvedValue({ id: LIST, profileId: PROFILE, kind: "custom", name: "Weekend" });
    traktClient.fetchPersonalLists.mockResolvedValue([{ ids: { trakt: 1 }, name: "Mine" }]);

    await expect(linkList(PROFILE, LIST, "trakt", "123", logger)).rejects.toMatchObject({ status: 404 });
  });
});

describe("pushListLinkChange", () => {
  beforeEach(() => vi.clearAllMocks());

  it("sends one change to the linked list", async () => {
    prismaMock.listLink.findUnique.mockResolvedValue(link());

    await pushListLinkChange(LIST, "remove", { type: "series", imdbId: "tt3" }, logger);

    expect(traktClient.removePersonalListItems).toHaveBeenCalledWith(123, { shows: [{ ids: { imdb: "tt3" } }] }, logger);
  });

  it("never throws, leaving a failed push to the next sync", async () => {
    prismaMock.listLink.findUnique.mockResolvedValue(link());
    traktClient.addPersonalListItems.mockRejectedValue(new Error("down"));

    await expect(pushListLinkChange(LIST, "add", { type: "movie", imdbId: "tt1" }, logger)).resolves.toBeUndefined();
    expect(logger.warn).toHaveBeenCalled();
  });

  it("does nothing for a list with no link", async () => {
    prismaMock.listLink.findUnique.mockResolvedValue(null);

    await pushListLinkChange(LIST, "add", { type: "movie", imdbId: "tt1" }, logger);

    expect(traktClient.addPersonalListItems).not.toHaveBeenCalled();
  });
});
