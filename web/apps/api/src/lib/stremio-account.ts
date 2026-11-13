// Importing a Stremio account's watch history, as the TV does
// (app/.../core/tracking/StremioImport.kt): sign in with Stremio's link code,
// read the library once, sign the session out.
//
// Stremio keeps one library item per title. Its `state` holds the resume point
// (`timeOffset` of `duration`, both ms, for `video_id`), how often it was
// watched, and for a series a compressed bit per episode (`watched`). Those
// bits only mean something against the series' episode list in the order
// Stremio sorts it, so series need their episodes: Cinemeta's, which is where a
// Stremio library's IMDb ids come from.

import { inflateSync } from "node:zlib";
import type { FastifyBaseLogger } from "fastify";
import { fetchWithPolicy } from "./http.js";
import { IMDB_ID_PATTERN } from "./content-id.js";
import type { ImportedWatch } from "./tracker-import.js";
import type { PlaybackImportRow } from "./playback.js";

const API = "https://api.strem.io";
const LINK_API = "https://link.stremio.com";
const CINEMETA = "https://v3-cinemeta.strem.io";

export type StremioLibraryItem = {
  id: string;
  type: string;
  name: string;
  removed: boolean;
  temp: boolean;
  lastWatched: Date | null;
  timeOffsetMs: number;
  durationMs: number;
  timesWatched: number;
  flaggedWatched: number;
  videoId: string | null;
  watched: string | null;
};

/** One episode as Stremio orders them for the watched bitfield. */
export type StremioVideo = { id: string; season: number | null; episode: number | null; released: number | null };

export class StremioError extends Error {
  constructor(
    message: string,
    readonly code?: number
  ) {
    super(message);
  }
}

const text = (value: unknown): string | null => (typeof value === "string" && value.trim() ? value.trim() : null);
const num = (value: unknown): number => (typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0);
const time = (value: unknown): Date | null => {
  const raw = text(value);
  if (!raw) return null;
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? null : date;
};

/** The `result` of `datastoreGet` on `libraryItem`. Malformed items are dropped. */
export const parseStremioLibrary = (body: unknown): StremioLibraryItem[] => {
  const root = (body ?? {}) as { error?: { code?: number; message?: string }; result?: unknown };
  if (root.error) throw new StremioError(root.error.message ?? "Stremio error", root.error.code);
  if (!Array.isArray(root.result)) return [];
  return root.result.flatMap((raw): StremioLibraryItem[] => {
    const item = (raw ?? {}) as Record<string, unknown>;
    const id = text(item._id);
    if (!id) return [];
    const state = (item.state ?? {}) as Record<string, unknown>;
    return [
      {
        id,
        type: text(item.type) ?? "",
        name: text(item.name) ?? "",
        removed: item.removed === true,
        temp: item.temp === true,
        lastWatched: time(state.lastWatched),
        timeOffsetMs: num(state.timeOffset),
        durationMs: num(state.duration),
        timesWatched: num(state.timesWatched),
        flaggedWatched: num(state.flaggedWatched),
        videoId: text(state.video_id),
        watched: text(state.watched),
      },
    ];
  });
};

type WatchedField = { anchorVideo: string; anchorLength: number; bits: Buffer };

/** Splits from the right: the anchor video id has colons of its own (`tt0944947:1:2`). */
export const parseWatchedField = (value: string): WatchedField | null => {
  const last = value.lastIndexOf(":");
  if (last <= 0) return null;
  const lengthColon = value.lastIndexOf(":", last - 1);
  if (lengthColon <= 0) return null;
  const anchorVideo = value.slice(0, lengthColon);
  const anchorLength = Number(value.slice(lengthColon + 1, last));
  if (!Number.isInteger(anchorLength) || anchorLength <= 0 || anchorVideo === "undefined") return null;
  try {
    // One bit per episode: anything near this size is not a real series.
    const bits = inflateSync(Buffer.from(value.slice(last + 1), "base64"), { maxOutputLength: 64 * 1024 });
    return { anchorVideo, anchorLength, bits };
  } catch {
    return null;
  }
};

/**
 * Stremio's episode order for the bitfield: season, then episode, then release
 * time, each missing value first; a video has a season and episode only when it
 * has both. Stable, so ties keep the source's order.
 */
export const stremioVideoOrder = (videos: StremioVideo[]): StremioVideo[] => {
  const numbered = (v: StremioVideo) => v.season !== null && v.episode !== null;
  const key = (v: StremioVideo): [number, number, number] => [
    numbered(v) ? v.season! : -Infinity,
    numbered(v) ? v.episode! : -Infinity,
    v.released ?? -Infinity,
  ];
  return videos
    .map((video, index) => ({ video, index, key: key(video) }))
    .sort((a, b) => a.key[0] - b.key[0] || a.key[1] - b.key[1] || a.key[2] - b.key[2] || a.index - b.index)
    .map(({ video }) => video);
};

/**
 * The ids of the videos a `watched` field (`<anchor>:<length>:<base64 zlib>`)
 * marks watched. Bit `i` (least significant first) is the i-th video in
 * Stremio's order, and the anchor, the last watched video, sits at
 * `length - 1`; episodes added since shift the list, so the bits are realigned
 * on the anchor as Stremio does. Null when the anchor isn't in [videos].
 */
export const decodeWatchedVideoIds = (watched: string, videos: StremioVideo[]): Set<string> | null => {
  const field = parseWatchedField(watched);
  if (!field) return null;
  const ordered = stremioVideoOrder(videos).map((v) => v.id);
  const anchorIndex = ordered.indexOf(field.anchorVideo);
  if (anchorIndex < 0) return null;
  const offset = field.anchorLength - anchorIndex - 1;
  const ids = new Set<string>();
  ordered.forEach((id, index) => {
    const bit = index + offset;
    const byte = bit >> 3;
    if (bit >= 0 && byte < field.bits.length && (field.bits[byte]! >> (bit & 7)) & 1) ids.add(id);
  });
  return ids;
};

/** Season and episode of an episode video: from the list, or from a `<series>:<s>:<e>` id. */
const episodeOf = (videoId: string, seriesId: string, byId: Map<string, StremioVideo>): [number, number] | null => {
  const video = byId.get(videoId);
  if (video && video.season !== null && video.episode !== null) return [video.season, video.episode];
  if (!videoId.startsWith(`${seriesId}:`)) return null;
  const parts = videoId.slice(seriesId.length + 1).split(":");
  if (parts.length !== 2) return null;
  const [season, episode] = parts.map(Number);
  return Number.isInteger(season) && Number.isInteger(episode) ? [season!, episode!] : null;
};

export type StremioImportPlan = {
  watches: ImportedWatch[];
  playback: PlaybackImportRow[];
  /** Titles Fetch can't hold (not IMDb ids: channels, addon-only catalogs). */
  skipped: number;
  /** Series whose watched episodes couldn't all be placed (no episode list for them). */
  unreadable: number;
};

const isSeries = (item: StremioLibraryItem) => item.type === "series" || item.type === "anime";
/** Stremio's own rule for Continue Watching, plus a length to make a percentage of. */
const hasResumePoint = (item: StremioLibraryItem) =>
  (!item.removed || item.temp) && item.timeOffsetMs > 0 && item.durationMs > 0;

/**
 * What to write. A watch whose date Stremio doesn't keep is `undated`, so it is
 * only added when the profile has no play of it at all; the last video played
 * carries `lastWatched`, unless it is being played again now (then that time is
 * this playback, not when it was finished, and its resume point must survive).
 */
export const planStremioImport = async (
  items: StremioLibraryItem[],
  videosFor: (item: StremioLibraryItem) => Promise<StremioVideo[] | null>,
  now: Date = new Date()
): Promise<StremioImportPlan> => {
  const plan: StremioImportPlan = { watches: [], playback: [], skipped: 0, unreadable: 0 };

  for (const item of items) {
    const movie = item.type === "movie";
    if (!movie && !isSeries(item)) continue;
    const imdbId = item.id;
    if (!IMDB_ID_PATTERN.test(imdbId)) {
      if (item.timesWatched > 0 || item.watched || item.timeOffsetMs > 0) plan.skipped += 1;
      continue;
    }
    const resuming = hasResumePoint(item);
    const dated = (videoId: string): { watchedAt: Date; undated?: boolean } =>
      videoId === item.videoId && item.lastWatched && !resuming
        ? { watchedAt: item.lastWatched }
        : { watchedAt: item.lastWatched ?? now, undated: true };

    if (movie) {
      if (item.timesWatched > 0 || item.flaggedWatched > 0) {
        plan.watches.push({ type: "movie", imdbId, title: item.name, ...dated(item.videoId ?? imdbId) });
      }
      if (resuming) {
        plan.playback.push(resumeRow(item, { type: "movie", imdbId }, now));
      }
      continue;
    }

    if (!item.watched && !resuming) continue;
    const videos = (await videosFor(item)) ?? [];
    const byId = new Map(videos.map((v) => [v.id, v]));

    if (item.watched) {
      const ids = decodeWatchedVideoIds(item.watched, videos);
      const marked = ids ? [...ids] : [parseWatchedField(item.watched)?.anchorVideo].filter((v): v is string => !!v);
      let unmapped = ids === null;
      for (const videoId of marked) {
        const position = episodeOf(videoId, imdbId, byId);
        if (!position) {
          unmapped = true;
          continue;
        }
        plan.watches.push({
          type: "episode",
          seriesImdbId: imdbId,
          season: position[0],
          episode: position[1],
          title: item.name,
          ...dated(videoId),
        });
      }
      if (unmapped) plan.unreadable += 1;
    }

    if (resuming && item.videoId) {
      const position = episodeOf(item.videoId, imdbId, byId);
      if (position) {
        plan.playback.push(
          resumeRow(item, { type: "episode", imdbId, seriesImdbId: imdbId, season: position[0], episode: position[1] }, now)
        );
      }
    }
  }
  return plan;
};

const resumeRow = (item: StremioLibraryItem, target: PlaybackImportRow["target"], now: Date): PlaybackImportRow => {
  const position = Math.min(item.timeOffsetMs, item.durationMs);
  return {
    target,
    progress: Math.round((position / item.durationMs) * 1000) / 10,
    positionSeconds: Math.round(position / 1000),
    durationSeconds: Math.round(item.durationMs / 1000),
    pausedAt: item.lastWatched ?? now,
  };
};

// ─── Network ───

const STREMIO_POLICY = { timeoutMs: 15_000, retries: 1, concurrency: 4 };

const json = async (response: Response): Promise<unknown> => {
  // Stremio answers its own errors (bad key, a code not approved yet) as a 200
  // with an `error` object; any other status is a failure, whatever its body.
  if (!response.ok) throw new StremioError(`Stremio HTTP ${response.status}`);
  return response.json();
};

export const createLinkCode = async (): Promise<{ code: string; link: string }> => {
  const body = (await json(
    await fetchWithPolicy(`${LINK_API}/api/v2/create?type=Create`, {}, STREMIO_POLICY)
  )) as { result?: { code?: string; link?: string }; error?: { message?: string } };
  const code = text(body.result?.code);
  const link = text(body.result?.link);
  if (!code || !link) throw new StremioError(body.error?.message ?? "Stremio returned no link code");
  return { code, link };
};

/** The auth key once the code has been approved, or null while it waits. */
export const readLinkCode = async (code: string): Promise<string | null> => {
  const url = `${LINK_API}/api/v2/read?type=Read&code=${encodeURIComponent(code)}`;
  const body = (await json(await fetchWithPolicy(url, {}, STREMIO_POLICY))) as {
    result?: { authKey?: string };
    error?: unknown;
  };
  if (body.error) return null;
  return text(body.result?.authKey);
};

const post = async (method: string, payload: object): Promise<unknown> =>
  json(
    await fetchWithPolicy(
      `${API}/api/${method}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload) },
      { ...STREMIO_POLICY, retries: 0, timeoutMs: 60_000 }
    )
  );

export const fetchLibrary = async (authKey: string): Promise<StremioLibraryItem[]> =>
  parseStremioLibrary(await post("datastoreGet", { authKey, collection: "libraryItem", ids: [], all: true }));

export const logout = async (authKey: string): Promise<void> => {
  await post("logout", { type: "Logout", authKey });
};

/** A series' episodes from Cinemeta, or null when it has none for that id. */
export const cinemetaVideos = async (imdbId: string): Promise<StremioVideo[] | null> => {
  const response = await fetchWithPolicy(`${CINEMETA}/meta/series/${encodeURIComponent(imdbId)}.json`, {}, STREMIO_POLICY);
  if (!response.ok) return null;
  const body = (await response.json()) as { meta?: { videos?: unknown[] } };
  const videos = body.meta?.videos;
  if (!Array.isArray(videos) || videos.length === 0) return null;
  return videos.flatMap((raw): StremioVideo[] => {
    const video = (raw ?? {}) as Record<string, unknown>;
    const id = text(video.id);
    if (!id) return [];
    const intOrNull = (v: unknown) => (typeof v === "number" && Number.isInteger(v) ? v : null);
    const released = time(video.released)?.getTime() ?? null;
    return [{ id, season: intOrNull(video.season), episode: intOrNull(video.episode ?? video.number), released }];
  });
};

export type StremioLog = FastifyBaseLogger;

// ─── The link flow, per profile ───

export type StremioImportSummary = {
  movies: number;
  episodes: number;
  resumePoints: number;
  skipped: number;
  unreadable: number;
};

export type StremioLinkState =
  | { state: "idle" }
  | { state: "waiting"; code: string; link: string; expiresAt: string }
  | { state: "importing" }
  | { state: "done"; summary: StremioImportSummary }
  | { state: "expired" }
  | { state: "failed"; error: string };

/** Stremio doesn't say how long a link code lives; stop asking after this long. */
const LINK_LIFETIME_MS = 10 * 60_000;

type Pending = { code: string; link: string; expiresAt: number } | { done: StremioLinkState };

/**
 * One pending link or finished result per profile, in memory: a link is a few
 * minutes long and an import a few more, so a restart losing one only means
 * starting again. Keyed by profile, so an import always lands in the profile
 * that started it.
 */
const links = new Map<string, Pending>();

export type StremioImportDeps = {
  createLinkCode: typeof createLinkCode;
  readLinkCode: typeof readLinkCode;
  fetchLibrary: typeof fetchLibrary;
  logout: typeof logout;
  videosFor: (imdbId: string) => Promise<StremioVideo[] | null>;
  /** Writes the plan into the profile; returns what was written. */
  write: (profileId: string, plan: StremioImportPlan, log: StremioLog) => Promise<Omit<StremioImportSummary, "skipped" | "unreadable">>;
  now: () => number;
};

export const startStremioLink = async (profileId: string, deps: StremioImportDeps): Promise<StremioLinkState> => {
  const current = links.get(profileId);
  if (current && "done" in current && current.done.state === "importing") return current.done;
  const { code, link } = await deps.createLinkCode();
  const expiresAt = deps.now() + LINK_LIFETIME_MS;
  links.set(profileId, { code, link, expiresAt });
  return { state: "waiting", code, link, expiresAt: new Date(expiresAt).toISOString() };
};

/**
 * Where the profile's link stands. While it waits, asks Stremio once whether
 * the code has been approved; once it has, starts the import in the background
 * and reports `importing` until it finishes.
 */
export const pollStremioLink = async (profileId: string, deps: StremioImportDeps, log: StremioLog): Promise<StremioLinkState> => {
  const current = links.get(profileId);
  if (!current) return { state: "idle" };
  if ("done" in current) return current.done;
  if (deps.now() > current.expiresAt) {
    links.set(profileId, { done: { state: "expired" } });
    return { state: "expired" };
  }
  const authKey = await deps.readLinkCode(current.code);
  if (!authKey) return { state: "waiting", code: current.code, link: current.link, expiresAt: new Date(current.expiresAt).toISOString() };

  links.set(profileId, { done: { state: "importing" } });
  void runImport(profileId, authKey, deps, log);
  return { state: "importing" };
};

export const cancelStremioLink = (profileId: string): void => {
  const current = links.get(profileId);
  // An import already under way finishes; only a waiting code or a result is dropped.
  if (current && "done" in current && current.done.state === "importing") return;
  links.delete(profileId);
};

const SERIES_LOOKUP_CONCURRENCY = 5;

const runImport = async (profileId: string, authKey: string, deps: StremioImportDeps, log: StremioLog): Promise<void> => {
  try {
    const items = await deps.fetchLibrary(authKey);
    // Episode lists up front, a few at a time, then the plan is built from them.
    const series = items.filter((item) => isSeries(item) && IMDB_ID_PATTERN.test(item.id) && (item.watched || hasResumePoint(item)));
    const videos = new Map<string, StremioVideo[] | null>();
    for (let i = 0; i < series.length; i += SERIES_LOOKUP_CONCURRENCY) {
      await Promise.all(
        series.slice(i, i + SERIES_LOOKUP_CONCURRENCY).map(async (item) => {
          try {
            videos.set(item.id, await deps.videosFor(item.id));
          } catch (error) {
            log.warn({ err: error, imdbId: item.id }, "No episode list for a Stremio library item");
            videos.set(item.id, null);
          }
        })
      );
    }
    const plan = await planStremioImport(items, async (item) => videos.get(item.id) ?? null, new Date(deps.now()));
    const written = await deps.write(profileId, plan, log);
    links.set(profileId, { done: { state: "done", summary: { ...written, skipped: plan.skipped, unreadable: plan.unreadable } } });
  } catch (error) {
    log.warn({ err: error }, "Stremio import failed");
    links.set(profileId, {
      done: { state: "failed", error: error instanceof StremioError ? error.message : "The import failed. Try again." },
    });
  } finally {
    await deps.logout(authKey).catch((error: unknown) => log.warn({ err: error }, "Couldn't sign the Stremio import session out"));
  }
};

/** For tests: forget every profile's link. */
export const resetStremioLinks = (): void => links.clear();
