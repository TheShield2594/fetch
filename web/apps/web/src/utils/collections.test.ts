import { describe, expect, it } from "vitest";
import { addonBaseUrl, describeSource, newCollection, parseAddonManifest } from "./collections";

describe("newCollection", () => {
  it("starts with one empty folder, in the TV's shape", () => {
    const collection = newCollection("Weekend");
    expect(collection).toMatchObject({ title: "Weekend", viewMode: "TABBED_GRID", showAllTab: true, pinToTop: false });
    expect(collection.folders).toHaveLength(1);
    expect(collection.folders[0]).toMatchObject({ tileShape: "POSTER", sources: [] });
    expect(collection.id).not.toBe(newCollection().id);
  });
});

describe("describeSource", () => {
  it.each([
    [{ provider: "fetch", listId: "x", title: "Family night", mediaType: "MOVIE" }, "Family night · movies"],
    [{ provider: "fetch", listId: "x", title: "Family night" }, "Family night · movies and series"],
    [{ provider: "trakt", traktListId: 4, title: "Mine", mediaType: "TV" }, "Trakt: Mine · series"],
    [
      { provider: "tmdb", tmdbSourceType: "DISCOVER", mediaType: "MOVIE", filters: { withGenres: "28,35", year: 1985, voteAverageGte: 7 } },
      "TMDB movies: Action, Comedy, 1985, rated 7+",
    ],
    [{ provider: "tmdb", tmdbSourceType: "COLLECTION", tmdbId: 10, title: "Star Wars" }, "TMDB film collection: Star Wars · movies"],
    [{ provider: "addon", addonId: "org.example", type: "movie", catalogId: "top", title: "Top" }, "Top · org.example (movie)"],
  ] as const)("describes %j", (source, expected) => {
    expect(describeSource(source)).toBe(expected);
  });
});

describe("addon manifests", () => {
  it.each([
    ["https://addon.example/manifest.json", "https://addon.example"],
    ["https://addon.example/secret/manifest.json", "https://addon.example/secret"],
    ["stremio://addon.example/manifest.json", "https://addon.example"],
    ["https://addon.example/", "https://addon.example"],
  ])("finds the base of %s", (input, expected) => {
    expect(addonBaseUrl(input)).toBe(expected);
  });

  it("reads the id and catalogs", () => {
    expect(
      parseAddonManifest(
        { id: "org.example", name: "Example", catalogs: [{ type: "movie", id: "top", name: "Top" }, { type: "series", id: "new" }, { bad: true }] },
        "https://addon.example"
      )
    ).toEqual({
      id: "org.example",
      name: "Example",
      transportUrl: "https://addon.example",
      catalogs: [
        { type: "movie", id: "top", name: "Top" },
        { type: "series", id: "new", name: "new" },
      ],
    });
  });

  it("rejects something that isn't a manifest", () => {
    expect(() => parseAddonManifest({ name: "x" }, "u")).toThrow(/no addon id/);
  });
});
