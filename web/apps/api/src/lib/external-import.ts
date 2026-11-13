// Row parsers for the other trackers' exports that `POST /import/external`
// reads, kept free of the database and TMDB so they can be tested on their own.
// Each one reads columns by header name (lower-cased by the route) and turns a
// row into what Fetch keeps, or drops it; the route counts the drops as skipped.

import { boundedInt, normalizeImdbId } from "./import-validation.js";

export type ColumnLookup = (name: string) => number;

const cell = (row: string[], index: number): string => (index === -1 ? "" : (row[index] ?? "").trim());

/** A date from an export cell, or null when it isn't one. */
export const parseExportDate = (value: string | undefined): Date | null => {
  const text = value?.trim() ?? "";
  if (!text) return null;
  const date = new Date(text);
  return Number.isNaN(date.getTime()) ? null : date;
};

/** A title to find on TMDB: Letterboxd carries names, not ids. */
export type TitleRow = { title: string; year: number | null; date: Date | null };

/**
 * Letterboxd's `watched.csv` and `watchlist.csv` (Date,Name,Year,Letterboxd URI).
 * `Date` is when the film was marked or added, not a viewing date.
 */
export const parseLetterboxdTitles = (rows: string[][], col: ColumnLookup): (TitleRow | null)[] => {
  const nameCol = col("name");
  const yearCol = col("year");
  const dateCol = col("date");
  if (nameCol === -1) throw badFormat("Unrecognized Letterboxd export header");
  return rows.map((row) => {
    const title = cell(row, nameCol);
    if (!title) return null;
    return { title, year: boundedInt(cell(row, yearCol), 1800, 2999), date: parseExportDate(cell(row, dateCol)) };
  });
};

export type ListEntry = { type: "movie" | "series"; imdbId: string };

/**
 * An IMDb list or watchlist export (Position,Const,Created,Modified,Description,
 * Title,…,Title Type,…). Episodes, games and the like can't be list items, so
 * only films and series come through.
 */
export const parseImdbList = (rows: string[][], col: ColumnLookup): (ListEntry | null)[] => {
  const constCol = col("const");
  const typeCol = col("title type");
  // `Position` is what sets a list or watchlist export apart from a ratings export, which also
  // has Const and Title Type: without it, every rated title would land on the list.
  if (constCol === -1 || typeCol === -1 || col("position") === -1) {
    throw badFormat("Unrecognized IMDb list export header: needs Position, Const and Title Type columns");
  }
  return rows.map((row) => {
    const imdbId = normalizeImdbId(cell(row, constCol));
    if (!imdbId) return null;
    const type = imdbListItemType(cell(row, typeCol));
    return type ? { type, imdbId } : null;
  });
};

/** IMDb's Title Type: "Movie", "TV Movie", "Short" are films; "TV Series", "TV Mini Series" are series. A blank cell is a film. */
export const imdbListItemType = (titleType: string): "movie" | "series" | null => {
  const value = titleType.trim().toLowerCase();
  if (!value) return "movie";
  if (value.includes("episode") || value.includes("game") || value.includes("podcast")) return null;
  if (value.includes("series")) return "series";
  if (value === "tv special") return "movie";
  return value.includes("movie") || value === "short" || value === "video" || value === "tv short" ? "movie" : null;
};

/** A custom list's name from the request: trimmed, at most 120 characters, never empty. */
export const importListName = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const name = value.trim().slice(0, 120);
  return name || null;
};

const badFormat = (message: string) => Object.assign(new Error(message), { code: "BAD_FORMAT" });
