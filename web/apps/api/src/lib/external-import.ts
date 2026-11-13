// Row parsers for the other trackers' exports that `POST /import/external`
// reads, kept free of the database and TMDB so they can be tested on their own.
// Each one reads columns by header name (lower-cased by the route) and turns a
// row into what Fetch keeps, or drops it; the route counts the drops as skipped.

import { boundedInt, MAX_EPISODE, MAX_SEASON, normalizeImdbId } from "./import-validation.js";

export type ColumnLookup = (name: string) => number;

/** The first of `names` the header has, or -1. Exports rename columns between versions. */
export const firstColumn = (col: ColumnLookup, ...names: string[]): number => {
  for (const name of names) {
    const index = col(name);
    if (index !== -1) return index;
  }
  return -1;
};

const cell = (row: string[], index: number): string => (index === -1 ? "" : (row[index] ?? "").trim());

/**
 * A date from an export cell. TV Time writes `2022-09-28 18:48:36` (one or two
 * spaces, no zone), read as UTC; anything else goes to `Date`. Null when it
 * isn't a date, and for TV Time's `0000-00-00` placeholder.
 */
export const parseExportDate = (value: string | undefined): Date | null => {
  const text = value?.trim() ?? "";
  if (!text || text.startsWith("0000")) return null;
  const local = /^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})$/.exec(text);
  const date = new Date(local ? `${local[1]}T${local[2]}Z` : text);
  return Number.isNaN(date.getTime()) ? null : date;
};

/** A title to find on TMDB: Letterboxd and TV Time carry names, not ids. */
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
  if (constCol === -1) throw badFormat("Unrecognized IMDb list export header");
  return rows.map((row) => {
    const imdbId = normalizeImdbId(cell(row, constCol));
    if (!imdbId) return null;
    const type = imdbListItemType(cell(row, typeCol));
    return type ? { type, imdbId } : null;
  });
};

/** IMDb's Title Type: "Movie", "TV Movie", "Short" are films; "TV Series", "TV Mini Series" are series. */
export const imdbListItemType = (titleType: string): "movie" | "series" | null => {
  const value = titleType.trim().toLowerCase();
  if (!value) return "movie";
  if (value.includes("episode") || value.includes("game") || value.includes("podcast")) return null;
  if (value.includes("series")) return "series";
  if (value === "tv special") return "movie";
  return value.includes("movie") || value === "short" || value === "video" || value === "tv short" ? "movie" : null;
};

/** One watched episode from TV Time's show history. */
export type TvTimeEpisodeRow = { seriesName: string; season: number; episode: number; watchedAt: Date };

/**
 * TV Time's show history (`tracking-prod-records-v2.csv` in most exports, earlier
 * ones named it `seen_episode.csv`): one row per watched episode, by series name.
 * Rows that aren't an episode (follows, specials without a number) are dropped.
 */
export const parseTvTimeEpisodes = (rows: string[][], col: ColumnLookup): (TvTimeEpisodeRow | null)[] => {
  const nameCol = firstColumn(col, "series_name", "show_name", "tv_show_name");
  const seasonCol = firstColumn(col, "season_number", "season");
  const episodeCol = firstColumn(col, "episode_number", "episode");
  const dateCol = firstColumn(col, "created_at", "watched_at", "updated_at");
  if (nameCol === -1 || seasonCol === -1 || episodeCol === -1) {
    throw badFormat("Unrecognized TV Time export header: needs series_name, season_number and episode_number columns");
  }
  return rows.map((row) => {
    const seriesName = cell(row, nameCol);
    const season = boundedInt(cell(row, seasonCol), 0, MAX_SEASON);
    const episode = boundedInt(cell(row, episodeCol), 1, MAX_EPISODE);
    const watchedAt = parseExportDate(cell(row, dateCol));
    if (!seriesName || season === null || episode === null || !watchedAt) return null;
    return { seriesName, season, episode, watchedAt };
  });
};

/** A TV Time movie: watched (with when) or only on the to-watch list. */
export type TvTimeMovieRow = TitleRow & { watched: boolean };

/**
 * TV Time's movies (`tracking-prod-records.csv`): `type` is `watch` or `rewatch`
 * for a viewing, anything else for a film on the to-watch list.
 */
export const parseTvTimeMovies = (rows: string[][], col: ColumnLookup): (TvTimeMovieRow | null)[] => {
  const nameCol = firstColumn(col, "movie_name", "name", "title");
  const typeCol = col("type");
  const dateCol = firstColumn(col, "updated_at", "created_at", "watched_at");
  const releaseCol = firstColumn(col, "release_date", "year");
  if (nameCol === -1) throw badFormat("Unrecognized TV Time export header: needs a movie_name column");
  return rows.map((row) => {
    const title = cell(row, nameCol);
    if (!title) return null;
    const kind = cell(row, typeCol).toLowerCase();
    const watched = kind === "watch" || kind === "rewatch";
    const release = cell(row, releaseCol);
    const year = /^\d{4}/.test(release) && !release.startsWith("0000") ? boundedInt(release.slice(0, 4), 1800, 2999) : null;
    const date = parseExportDate(cell(row, dateCol));
    // A viewing needs a date; a to-watch entry doesn't.
    if (watched && !date) return null;
    return { title, year, date, watched };
  });
};

/** A custom list's name from the request: trimmed, at most 120 characters, never empty. */
export const importListName = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const name = value.trim().slice(0, 120);
  return name || null;
};

const badFormat = (message: string) => Object.assign(new Error(message), { code: "BAD_FORMAT" });
