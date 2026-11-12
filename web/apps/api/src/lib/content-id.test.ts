import { describe, expect, it } from "vitest";
import {
  MDBLIST_ID_SOURCES,
  SIMKL_ID_SOURCES,
  TRAKT_ID_SOURCES,
  externalIdsFor,
  isImdbId,
  normalizeContentId,
  parseContentId,
  tmdbIdOf,
} from "./content-id.js";

describe("normalizeContentId", () => {
  it.each([
    ["tt0111161", "tt0111161"],
    [" tt0111161 ", "tt0111161"],
    ["TT0111161", "tt0111161"],
    ["imdb:tt0111161", "tt0111161"],
    ["tmdb:603", "tmdb:603"],
    ["TMDB:0603", "tmdb:603"],
    ["kitsu:1376", "kitsu:1376"],
    ["mal:1", "mal:1"],
    ["anilist:1", "anilist:1"],
    ["anidb:23", "anidb:23"],
    ["tvdb:76885", "tvdb:76885"],
    ["trakt:1", "trakt:1"],
    ["simkl:9", "simkl:9"],
  ])("accepts %s as %s", (raw, expected) => {
    expect(normalizeContentId(raw)).toBe(expected);
  });

  it.each([
    [""],
    ["   "],
    ["tt"],
    ["tt12ab"],
    ["kitsu:"],
    ["kitsu:abc"],
    ["kitsu:1376:5"],
    ["tt0111161:1:2"],
    ["foo:123"],
    ["1376"],
    ["tmdb:1234567890123"],
  ])("rejects %j", (raw) => {
    expect(normalizeContentId(raw)).toBeNull();
  });

  it("rejects what isn't a string", () => {
    expect(normalizeContentId(42)).toBeNull();
    expect(normalizeContentId(null)).toBeNull();
  });
});

describe("parseContentId", () => {
  it("names the source of each kind of id", () => {
    expect(parseContentId("tt1")).toEqual({ source: "imdb", value: "tt1" });
    expect(parseContentId("kitsu:1376")).toEqual({ source: "kitsu", value: "1376" });
    expect(parseContentId("nope")).toBeNull();
    expect(isImdbId("tt1")).toBe(true);
    expect(isImdbId("tmdb:1")).toBe(false);
    expect(tmdbIdOf("tmdb:603")).toBe(603);
    expect(tmdbIdOf("tt1")).toBeNull();
  });
});

describe("externalIdsFor", () => {
  it("sends an IMDb id as imdb and a tmdb: id as tmdb, to every Trakt-style service", () => {
    for (const sources of [TRAKT_ID_SOURCES, SIMKL_ID_SOURCES, MDBLIST_ID_SOURCES]) {
      expect(externalIdsFor("tt0111161", sources)).toEqual({ imdb: "tt0111161" });
      expect(externalIdsFor("tmdb:603", sources)).toEqual({ tmdb: 603 });
    }
  });

  it("sends anime ids only where the service takes them", () => {
    expect(externalIdsFor("kitsu:1376", SIMKL_ID_SOURCES)).toEqual({ kitsu: 1376 });
    expect(externalIdsFor("mal:1", SIMKL_ID_SOURCES)).toEqual({ mal: 1 });
    expect(externalIdsFor("kitsu:1376", TRAKT_ID_SOURCES)).toBeNull();
    expect(externalIdsFor("anilist:1", MDBLIST_ID_SOURCES)).toBeNull();
    expect(externalIdsFor("garbage", TRAKT_ID_SOURCES)).toBeNull();
  });
});
