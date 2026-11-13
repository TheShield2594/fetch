import { describe, expect, it } from "vitest";
import {
  imdbListItemType,
  importListName,
  parseExportDate,
  parseImdbList,
  parseLetterboxdTitles,
} from "./external-import.js";

/** Rows and a header lookup the way the route builds them. */
const table = (csv: string) => {
  const [head, ...rows] = csv.trim().split("\n").map((line) => line.split(","));
  const header = head!.map((h) => h.trim().toLowerCase());
  return { rows, col: (name: string) => header.indexOf(name) };
};

describe("parseExportDate", () => {
  it("reads dates and rejects junk", () => {
    expect(parseExportDate("2024-01-05")?.toISOString()).toBe("2024-01-05T00:00:00.000Z");
    expect(parseExportDate("soon")).toBeNull();
    expect(parseExportDate("")).toBeNull();
  });
});

describe("parseLetterboxdTitles", () => {
  it("reads watched.csv and watchlist.csv rows", () => {
    const { rows, col } = table(`Date,Name,Year,Letterboxd URI
2023-04-01,Paris Texas,1984,https://boxd.it/x
2023-04-02,,2001,https://boxd.it/y`);
    expect(parseLetterboxdTitles(rows, col)).toEqual([
      { title: "Paris Texas", year: 1984, date: new Date("2023-04-01T00:00:00.000Z") },
      null,
    ]);
  });

  it("rejects a file without a Name column", () => {
    const { rows, col } = table(`Const,Title\ntt1,A`);
    expect(() => parseLetterboxdTitles(rows, col)).toThrow(/Letterboxd/);
  });
});

describe("parseImdbList", () => {
  it("keeps films and series, drops episodes and bad ids", () => {
    const { rows, col } = table(`Position,Const,Created,Title,Title Type
1,tt0111161,2024-01-01,A Film,Movie
2,tt0903747,2024-01-01,A Show,TV Series
3,tt0959621,2024-01-01,An Episode,TV Episode
4,nm0000001,2024-01-01,A Person,Movie`);
    expect(parseImdbList(rows, col)).toEqual([
      { type: "movie", imdbId: "tt0111161" },
      { type: "series", imdbId: "tt0903747" },
      null,
      null,
    ]);
  });

  it("maps IMDb's title types", () => {
    expect(imdbListItemType("TV Mini Series")).toBe("series");
    expect(imdbListItemType("TV Movie")).toBe("movie");
    expect(imdbListItemType("Short")).toBe("movie");
    expect(imdbListItemType("Video Game")).toBeNull();
    expect(imdbListItemType("")).toBe("movie");
  });
});

describe("importListName", () => {
  it("trims and caps the name", () => {
    expect(importListName("  Favourites  ")).toBe("Favourites");
    expect(importListName("x".repeat(200))).toHaveLength(120);
    expect(importListName("   ")).toBeNull();
    expect(importListName(7)).toBeNull();
  });
});
