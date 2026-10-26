import { describe, expect, it } from "vitest";
import { validateCollections } from "./collections.js";

const LIST_ID = "11111111-1111-4111-8111-111111111111";

// A collection exactly as the TV's CollectionsDataStore writes it.
const tvCollection = {
  id: "c1",
  title: "Weekend",
  backdropImageUrl: null,
  pinToTop: true,
  viewMode: "ROWS",
  showAllTab: true,
  folders: [
    {
      id: "f1",
      title: "Picks",
      coverEmoji: "🍿",
      tileShape: "POSTER",
      hideTitle: false,
      focusGifEnabled: true,
      sources: [
        { provider: "addon", addonId: "com.example", type: "movie", catalogId: "top" },
        { provider: "tmdb", tmdbSourceType: "DISCOVER", title: "Eighties", mediaType: "MOVIE", sortBy: "popularity.desc", filters: { year: 1985 } },
        { provider: "trakt", title: "Mine", traktListId: 42, mediaType: "TV", sortBy: "rank", sortHow: "asc" },
        { provider: "fetch", title: "Family night", listId: LIST_ID, mediaType: "MOVIE" },
      ],
      catalogSources: [{ addonId: "com.example", type: "movie", catalogId: "top" }],
      someFieldFromANewerTv: 7,
    },
  ],
};

describe("validateCollections", () => {
  it("accepts the TV's own form and returns it untouched", () => {
    const result = validateCollections([tvCollection]);
    expect(result).toEqual({ ok: true, value: [tvCollection] });
    if (result.ok) expect(result.value[0]).toBe(tvCollection);
  });

  it("accepts an empty list", () => {
    expect(validateCollections([])).toEqual({ ok: true, value: [] });
  });

  it.each([
    ["not an array", {}, /must be an array/],
    ["a collection with no title", [{ id: "c" }], /needs an id and a title/],
    ["duplicate collection ids", [{ id: "c", title: "A" }, { id: "c", title: "B" }], /duplicate id/],
    ["an unknown view mode", [{ id: "c", title: "A", viewMode: "CAROUSEL" }], /viewMode/],
    [
      "an addon source with no catalog",
      [{ id: "c", title: "A", folders: [{ id: "f", title: "F", sources: [{ provider: "addon", addonId: "x", type: "movie" }] }] }],
      /addonId, type and catalogId/,
    ],
    [
      "a TMDB list source with no id",
      [{ id: "c", title: "A", folders: [{ id: "f", title: "F", sources: [{ provider: "tmdb", tmdbSourceType: "LIST" }] }] }],
      /needs a tmdbId/,
    ],
    [
      "a website list source with no list",
      [{ id: "c", title: "A", folders: [{ id: "f", title: "F", sources: [{ provider: "fetch", listId: "nope" }] }] }],
      /needs a listId/,
    ],
    [
      "an unknown provider",
      [{ id: "c", title: "A", folders: [{ id: "f", title: "F", sources: [{ provider: "plex" }] }] }],
      /provider must be one of/,
    ],
  ])("rejects %s", (_label, input, message) => {
    const result = validateCollections(input);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toMatch(message);
  });
});
