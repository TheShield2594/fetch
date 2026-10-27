import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";
import { deleteConnection, getConnection, listConnections, saveConnection } from "./connections.js";
import { connectionKvKey } from "./profile-connections.js";
import { SIMKL_LAST_SYNCED_AT_KEY, SIMKL_PIN_KEY } from "./owner-keys.js";
import { importTrackerItems, type ImportedWatch, type ImportedWatchlistItem, type ImportSummary } from "./tracker-import.js";

// Simkl, as the TV app uses it (app/.../data/simkl): a PIN sign-in, the
// account's library read through /sync/all-items, and plays, watchlist adds
// and live scrobbles sent back. Each profile connects its own Simkl account;
// the website's connection is separate from the TV's.
//
// SIMKL_CLIENT_ID is the Fetch app's id with Simkl (simkl.com/settings/developer),
// the same kind of value the TV build reads.

const SIMKL_API = "https://api.simkl.com";
const APP_NAME = process.env.SIMKL_APP_NAME?.trim() || "fetch";
const APP_VERSION = "web";
const REQUEST_TIMEOUT_MS = 20_000;


/** The PIN code shape the TV accepts, so a stored one can't steer the URL. */
const PIN_PATTERN = /^[A-Za-z0-9]{4,12}$/;

export type SimklCredentials = { accessToken: string };
export type SimklDetails = { username: string | null; accountId: number | null };

export class SimklError extends Error {
  constructor(
    message: string,
    readonly status: number | null = null
  ) {
    super(message);
  }
}

const clientId = (): string | null => process.env.SIMKL_CLIENT_ID?.trim() || null;

export const isSimklConfigured = (): boolean => clientId() !== null;

const simklRequest = async <T>(
  method: "GET" | "POST",
  path: string,
  options: { token?: string; query?: Record<string, string>; body?: unknown } = {}
): Promise<T> => {
  const id = clientId();
  if (!id) throw new SimklError("SIMKL_CLIENT_ID is not configured");
  const url = new URL(path, SIMKL_API);
  for (const [key, value] of Object.entries(options.query ?? {})) url.searchParams.set(key, value);
  url.searchParams.set("client_id", id);
  url.searchParams.set("app-name", APP_NAME);
  url.searchParams.set("app-version", APP_VERSION);

  const headers: Record<string, string> = { Accept: "application/json", "User-Agent": `Fetch/${APP_VERSION}` };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  const response = await fetch(url, {
    method,
    headers,
    ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    redirect: "error",
  });
  if (!response.ok) throw new SimklError(`Simkl ${method} ${path} failed (${response.status})`, response.status);
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
};

// ─── Sign-in ───

export type SimklPin = { userCode: string; verificationUrl: string; expiresAt: string; interval: number };

const pinKey = (profileId: string) => connectionKvKey(SIMKL_PIN_KEY, profileId);

const readPin = async (profileId: string): Promise<SimklPin | null> => {
  const row = await prisma.kV.findUnique({ where: { key: pinKey(profileId) } });
  if (!row) return null;
  try {
    return JSON.parse(row.value) as SimklPin;
  } catch {
    return null;
  }
};

const clearPin = (profileId: string) => prisma.kV.deleteMany({ where: { key: pinKey(profileId) } });

/** Starts a sign-in: a code the person enters at simkl.com/pin. */
export const startSimklPin = async (profileId: string): Promise<SimklPin> => {
  const response = await simklRequest<{
    result?: string;
    user_code?: string;
    verification_url?: string;
    verification_uri?: string;
    expires_in?: number;
    interval?: number;
  }>("GET", "/oauth/pin");
  const userCode = response?.user_code?.trim();
  const verificationUrl = (response?.verification_uri ?? response?.verification_url)?.trim();
  if (response?.result !== "OK" || !userCode || !PIN_PATTERN.test(userCode) || !verificationUrl || !response.expires_in) {
    throw new SimklError("Simkl returned an unexpected sign-in code");
  }
  const pin: SimklPin = {
    userCode,
    verificationUrl,
    expiresAt: new Date(Date.now() + response.expires_in * 1000).toISOString(),
    interval: Math.max(1, response.interval ?? 5),
  };
  const now = new Date();
  await prisma.kV.upsert({
    where: { key: pinKey(profileId) },
    create: { key: pinKey(profileId), value: JSON.stringify(pin), updatedAt: now },
    update: { value: JSON.stringify(pin), updatedAt: now },
  });
  return pin;
};

export type SimklPinPoll = "pending" | "connected" | "expired" | "none";

/** Checks once whether the person has entered the code yet; connects the profile if so. */
export const pollSimklPin = async (profileId: string): Promise<SimklPinPoll> => {
  const pin = await readPin(profileId);
  if (!pin || !PIN_PATTERN.test(pin.userCode)) return "none";
  if (Date.parse(pin.expiresAt) <= Date.now()) {
    await clearPin(profileId);
    return "expired";
  }
  const response = await simklRequest<{ result?: string; access_token?: string }>(
    "GET",
    `/oauth/pin/${encodeURIComponent(pin.userCode)}`
  );
  const token = response?.access_token?.trim();
  if (response?.result !== "OK" || !token) return "pending";

  let details: SimklDetails = { username: null, accountId: null };
  try {
    const settings = await simklRequest<{ user?: { name?: string }; account?: { id?: number } }>(
      "POST",
      "/users/settings",
      { token }
    );
    details = { username: settings?.user?.name?.trim() || null, accountId: settings?.account?.id ?? null };
  } catch {
    // The token works; the name is only for Settings to show.
  }
  await saveConnection<SimklCredentials, SimklDetails>({
    profileId,
    provider: "simkl",
    credentials: { accessToken: token },
    details,
  });
  await clearPin(profileId);
  return "connected";
};

export const getSimklStatus = async (profileId: string) => {
  const [connection, pin, lastSynced] = await Promise.all([
    getConnection<SimklCredentials, SimklDetails>(profileId, "simkl"),
    readPin(profileId),
    prisma.kV.findUnique({ where: { key: connectionKvKey(SIMKL_LAST_SYNCED_AT_KEY, profileId) } }),
  ]);
  return {
    configured: isSimklConfigured(),
    connected: !!connection,
    username: connection?.details.username ?? null,
    lastSyncedAt: lastSynced?.value ?? null,
    pending: pin && Date.parse(pin.expiresAt) > Date.now() ? pin : null,
  };
};

/** Simkl tokens don't expire and have no revoke call: forgetting it is disconnecting. */
export const disconnectSimkl = async (profileId: string): Promise<void> => {
  await deleteConnection(profileId, "simkl");
  await prisma.kV.deleteMany({
    where: { key: { in: [pinKey(profileId), connectionKvKey(SIMKL_LAST_SYNCED_AT_KEY, profileId)] } },
  });
};

const tokenFor = async (profileId: string): Promise<string | null> => {
  if (!isSimklConfigured()) return null;
  const connection = await getConnection<SimklCredentials, SimklDetails>(profileId, "simkl");
  return connection?.credentials?.accessToken ?? null;
};

// ─── Reading the library ───

type SimklIds = { imdb?: string | null };
type SimklMedia = { title?: string | null; ids?: SimklIds | null };
type SimklEpisode = { number?: number | null; watched_at?: string | null };
type SimklSeason = { number?: number | null; episodes?: SimklEpisode[] | null };
type SimklEntry = {
  status?: string | null;
  last_watched_at?: string | null;
  movie?: SimklMedia | null;
  show?: SimklMedia | null;
  anime_type?: string | null;
  seasons?: SimklSeason[] | null;
};
type SimklAllItems = { movies?: SimklEntry[] | null; shows?: SimklEntry[] | null; anime?: SimklEntry[] | null } | null;

/** Turns /sync/all-items into the plays and watchlist entries Fetch keeps. Exported for tests. */
export const projectSimklLibrary = (
  items: SimklAllItems
): { watches: ImportedWatch[]; watchlist: ImportedWatchlistItem[] } => {
  const watches: ImportedWatch[] = [];
  const watchlist: ImportedWatchlistItem[] = [];
  const movieEntry = (entry: SimklEntry) => {
    const media = entry.movie ?? entry.show;
    const imdbId = media?.ids?.imdb?.trim();
    if (!imdbId) return;
    if (entry.status === "plantowatch") watchlist.push({ type: "movie", imdbId, title: media?.title });
    else if (entry.status === "completed" && entry.last_watched_at) {
      watches.push({ type: "movie", imdbId, watchedAt: new Date(entry.last_watched_at), title: media?.title });
    }
  };
  const showEntry = (entry: SimklEntry) => {
    const imdbId = entry.show?.ids?.imdb?.trim();
    if (!imdbId) return;
    if (entry.status === "plantowatch") watchlist.push({ type: "series", imdbId, title: entry.show?.title });
    for (const season of entry.seasons ?? []) {
      if (typeof season.number !== "number") continue;
      for (const episode of season.episodes ?? []) {
        if (typeof episode.number !== "number" || !episode.watched_at) continue;
        watches.push({
          type: "episode",
          seriesImdbId: imdbId,
          season: season.number,
          episode: episode.number,
          watchedAt: new Date(episode.watched_at),
          title: entry.show?.title,
        });
      }
    }
  };

  for (const entry of items?.movies ?? []) movieEntry(entry);
  for (const entry of items?.shows ?? []) showEntry(entry);
  // An anime film is a film; an anime series is a show, numbered by season
  // where Simkl knows the TV seasons (extended=full_anime_seasons).
  for (const entry of items?.anime ?? []) {
    if (entry.anime_type === "movie") movieEntry(entry);
    else showEntry(entry);
  }
  return { watches, watchlist };
};

/**
 * Brings the profile's Simkl library into Fetch: completed films and every
 * watched episode as plays, plan-to-watch as the watchlist. The first run
 * reads everything; later ones only what changed since the last.
 */
export const syncSimkl = async (
  profileId: string,
  logger: FastifyBaseLogger,
  options: { full?: boolean } = {}
): Promise<ImportSummary & { full: boolean }> => {
  const token = await tokenFor(profileId);
  if (!token) return { movies: 0, episodes: 0, watchlist: 0, skipped: 0, full: false };

  const watermarkKey = connectionKvKey(SIMKL_LAST_SYNCED_AT_KEY, profileId);
  const watermark = options.full ? null : (await prisma.kV.findUnique({ where: { key: watermarkKey } }))?.value;
  const startedAt = new Date();
  const items = await simklRequest<SimklAllItems>("GET", "/sync/all-items", {
    token,
    query: {
      extended: "full_anime_seasons",
      episode_watched_at: "yes",
      ...(watermark ? { date_from: watermark } : {}),
    },
  });
  const { watches, watchlist } = projectSimklLibrary(items);
  const summary = await importTrackerItems(profileId, watches, watchlist, logger);

  // A minute back, so a change Simkl recorded while this ran is read next time.
  const next = new Date(startedAt.getTime() - 60_000).toISOString();
  await prisma.kV.upsert({
    where: { key: watermarkKey },
    create: { key: watermarkKey, value: next, updatedAt: startedAt },
    update: { value: next, updatedAt: startedAt },
  });
  return { ...summary, full: !watermark };
};

/** Every profile with Simkl connected, for the scheduled sync. */
export const listSimklProfiles = async (): Promise<string[]> =>
  isSimklConfigured() ? (await listConnections("simkl")).map((connection) => connection.profileId) : [];

// ─── Sending to Simkl ───
//
// Best-effort, like the Trakt pushes: a Simkl that is down must not fail or
// delay the play, list change or scrobble that triggered it.

type SimklWatch =
  | { type: "movie"; imdbId: string; watchedAt?: Date }
  | { type: "episode"; seriesImdbId: string; season: number | null | undefined; episode: number | null | undefined; watchedAt?: Date };

const historyBody = (watch: SimklWatch) =>
  watch.type === "movie"
    ? { movies: [{ ids: { imdb: watch.imdbId }, ...(watch.watchedAt ? { watched_at: watch.watchedAt.toISOString() } : {}) }] }
    : {
        shows: [
          {
            ids: { imdb: watch.seriesImdbId },
            seasons: [
              {
                number: watch.season,
                episodes: [{ number: watch.episode, ...(watch.watchedAt ? { watched_at: watch.watchedAt.toISOString() } : {}) }],
              },
            ],
          },
        ],
      };

/** Adds a play to the profile's Simkl history, if it has Simkl connected. */
export const pushSimklWatched = async (profileId: string, watch: SimklWatch, logger: FastifyBaseLogger): Promise<void> => {
  if (watch.type === "episode" && (watch.season == null || watch.episode == null)) return;
  try {
    const token = await tokenFor(profileId);
    if (!token) return;
    await simklRequest("POST", "/sync/history", { token, body: historyBody(watch) });
  } catch (error) {
    logger.warn({ error }, "Simkl history push failed");
  }
};

/** Puts a title on the profile's Simkl plan-to-watch list. */
export const pushSimklWatchlistAdd = async (
  profileId: string,
  item: { type: "movie" | "series"; imdbId: string },
  logger: FastifyBaseLogger
): Promise<void> => {
  try {
    const token = await tokenFor(profileId);
    if (!token) return;
    const entry = { to: "plantowatch", ids: { imdb: item.imdbId } };
    await simklRequest("POST", "/sync/add-to-list", {
      token,
      body: item.type === "movie" ? { movies: [entry] } : { shows: [entry] },
    });
  } catch (error) {
    logger.warn({ error }, "Simkl watchlist push failed");
  }
};

export type SimklScrobbleAction = "start" | "pause" | "stop";

/** A live playback transition, as the TV sends it. */
export const pushSimklScrobble = async (
  action: SimklScrobbleAction,
  params: {
    profileId: string;
    type: "movie" | "episode";
    imdbId: string;
    seriesImdbId?: string | null | undefined;
    season?: number | null | undefined;
    episode?: number | null | undefined;
    progress: number;
  },
  logger: FastifyBaseLogger
): Promise<void> => {
  if (params.type === "episode" && (params.season == null || params.episode == null)) return;
  try {
    const token = await tokenFor(params.profileId);
    if (!token) return;
    const progress = Math.max(0, Math.min(100, Math.round(params.progress * 100) / 100));
    const body =
      params.type === "movie"
        ? { progress, movie: { ids: { imdb: params.imdbId } } }
        : {
            progress,
            show: { ids: { imdb: params.seriesImdbId ?? params.imdbId } },
            episode: { season: params.season, number: params.episode },
          };
    await simklRequest("POST", `/scrobble/${action}`, { token, body });
  } catch (error) {
    logger.warn({ error, action }, "Simkl scrobble failed");
  }
};
