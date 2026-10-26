import { describe, expect, it, vi } from "vitest";

vi.mock("./prisma.js", () => ({ prisma: {} }));
import {
  addonRowKey,
  isValidRowKey,
  listRowKey,
  mergeHomeLayout,
  parseRowKey,
  pruneRows,
  readStoredRows,
  type HomeRowContext,
} from "./home-layout.js";

const LIST_ID = "11111111-1111-4111-8111-111111111111";

const context = (overrides: Partial<HomeRowContext> = {}): HomeRowContext => ({
  sources: [
    { key: "builtin:continue_watching", title: "Continue Watching", source: "builtin", addonName: null, type: null, available: true },
    { key: addonRowKey("com.cinemeta", "movie", "top"), title: "Popular", source: "addon", addonName: "Cinemeta", type: "movie", available: true },
    { key: addonRowKey("com.cinemeta", "series", "top"), title: "Popular", source: "addon", addonName: "Cinemeta", type: "series", available: true },
    { key: listRowKey(LIST_ID), title: "Watchlist", source: "list", addonName: null, type: null, available: true },
  ],
  installedAddonIds: new Set(["com.cinemeta"]),
  hasUnidentifiedAddon: false,
  listIds: new Set([LIST_ID]),
  collectionIds: new Set(["c1"]),
  ...overrides,
});

describe("row keys", () => {
  it("encodes each part so a colon inside one can't split it", () => {
    const key = addonRowKey("org.x:y", "movie", "a:b");
    expect(key).toBe("addon:org.x%3Ay:movie:a%3Ab");
    expect(parseRowKey(key)).toEqual({ source: "addon", addonId: "org.x:y", type: "movie", catalogId: "a:b" });
  });

  it("reads list and built-in keys, and treats anything else as the TV's", () => {
    expect(parseRowKey(listRowKey(LIST_ID))).toEqual({ source: "list", listId: LIST_ID });
    expect(parseRowKey("builtin:continue_watching")).toEqual({ source: "builtin", name: "continue_watching" });
    expect(parseRowKey("collection:abc")).toEqual({ source: "collection", collectionId: "abc" });
    expect(parseRowKey("folder:abc")).toEqual({ source: "other" });
  });

  it("validates the shape of a key", () => {
    expect(isValidRowKey("list:abc")).toBe(true);
    expect(isValidRowKey("no-prefix")).toBe(false);
    expect(isValidRowKey("list:has space")).toBe(false);
  });
});

describe("readStoredRows", () => {
  it("skips entries that aren't rows", () => {
    expect(
      readStoredRows({ rows: [{ key: "list:a", hidden: true, title: " T " }, { key: 3 }, null, { key: "bad key" }] })
    ).toEqual([{ key: "list:a", hidden: true, title: "T" }]);
    expect(readStoredRows(null)).toEqual([]);
  });
});

describe("pruneRows", () => {
  it("drops rows of removed addons and deleted lists, and repeated keys, keeping the rest in order", () => {
    const rows = [
      { key: addonRowKey("com.removed", "movie", "x"), hidden: false },
      { key: listRowKey(LIST_ID), hidden: false },
      { key: listRowKey("22222222-2222-4222-8222-222222222222"), hidden: false },
      { key: "collection:c1", hidden: true },
      { key: "collection:deleted", hidden: true },
      { key: "folder:tv-owned", hidden: false },
      { key: listRowKey(LIST_ID), hidden: true },
      { key: "builtin:continue_watching", hidden: false },
    ];
    expect(pruneRows(rows, context()).map((row) => row.key)).toEqual([
      listRowKey(LIST_ID),
      "collection:c1",
      "folder:tv-owned",
      "builtin:continue_watching",
    ]);
  });

  it("keeps collection rows while the profile has no collections stored here", () => {
    const rows = [{ key: "collection:from-the-tv", hidden: false }];
    expect(pruneRows(rows, context({ collectionIds: null }))).toHaveLength(1);
  });

  it("keeps addon rows it can't rule out while an installed addon is unidentified", () => {
    const rows = [{ key: addonRowKey("com.unknown", "movie", "x"), hidden: false }];
    expect(pruneRows(rows, context({ hasUnidentifiedAddon: true }))).toHaveLength(1);
  });
});

describe("mergeHomeLayout", () => {
  it("puts arranged rows first and appends every row not placed yet", () => {
    const stored = [
      { key: listRowKey(LIST_ID), hidden: false },
      { key: addonRowKey("com.cinemeta", "series", "top"), hidden: true },
    ];
    const merged = mergeHomeLayout(stored, context());
    expect(merged.map((row) => [row.key, row.hidden])).toEqual([
      [listRowKey(LIST_ID), false],
      [addonRowKey("com.cinemeta", "series", "top"), true],
      ["builtin:continue_watching", false],
      [addonRowKey("com.cinemeta", "movie", "top"), false],
    ]);
  });

  it("keeps a row whose catalog it can't resolve, by its stored title", () => {
    const stored = [{ key: addonRowKey("com.cinemeta", "movie", "gone"), hidden: false, title: "Old row" }];
    const [row] = mergeHomeLayout(stored, context());
    expect(row).toMatchObject({ title: "Old row", available: false, source: "addon", type: "movie" });
  });
});
