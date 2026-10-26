import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";
import { deleteConnection, getConnection, listConnections, saveConnection } from "./connections.js";
import { connectionKvKey } from "./profile-connections.js";
import { MDBLIST_ACTIVITIES_KEY, MDBLIST_DEVICE_KEY } from "./owner-keys.js";
import { deleteSecretKv, readSecretKv, writeSecretKv } from "./secret-store.js";
import { importTrackerItems, type ImportedWatch, type ImportedWatchlistItem, type ImportSummary } from "./tracker-import.js";

// MDBList as a tracker, as the TV app uses it (app/.../data/mdblist): an OAuth
// device sign-in, the account's watched history and watchlist read in, and
// plays, watchlist adds and scrobbles sent back. Each profile connects its own;
// the website's connection is separate from the TV's.
//
// MDBLIST_CLIENT_ID is the Fetch app's OAuth client id with MDBList, the same
// kind of value the TV build reads. The device flow needs no secret.

const MDBLIST_API = "https://api.mdblist.com";
const REQUEST_TIMEOUT_MS = 20_000;
const PAGE_LIMIT = 1000;
/** Pages read per list before giving up, so a misbehaving cursor can't loop forever. */
const MAX_PAGES = 50;
/** Refresh this long before the access token runs out. */
const REFRESH_MARGIN_MS = 60_000;

export type MdbListCredentials = { accessToken: string; refreshToken: string; expiresAt: string };
export type MdbListDetails = { username: string | null };

export class MdbListError extends Error {
  constructor(
    message: string,
    readonly status: number | null = null,
    readonly code: string | null = null
  ) {
    super(message);
  }
}

const clientId = (): string | null => process.env.MDBLIST_CLIENT_ID?.trim() || null;

export const isMdbListConfigured = (): boolean => clientId() !== null;

const parse = (text: string): unknown => {
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
};

const mdbRequest = async <T>(
  method: "GET" | "POST",
  path: string,
  options: { token?: string; query?: Record<string, string>; body?: unknown; form?: Record<string, string> } = {}
): Promise<T> => {
  const url = new URL(path, MDBLIST_API);
  for (const [key, value] of Object.entries(options.query ?? {})) url.searchParams.set(key, value);
  const headers: Record<string, string> = { Accept: "application/json", "User-Agent": "Fetch/web" };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  let body: string | undefined;
  if (options.form) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    body = new URLSearchParams(options.form).toString();
  } else if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  const response = await fetch(url, {
    method,
    headers,
    ...(body !== undefined ? { body } : {}),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    redirect: "error",
  });
  const payload = parse(await response.text());
  if (!response.ok) {
    const code = (payload as { error?: unknown } | null)?.error;
    throw new MdbListError(
      `MDBList ${method} ${path} failed (${response.status})`,
      response.status,
      typeof code === "string" ? code : null
    );
  }
  return payload as T;
};

// ─── Sign-in ───

export type MdbListDevicePending = {
  userCode: string;
  verificationUrl: string;
  verificationUrlComplete: string;
  expiresAt: string;
  interval: number;
};
type StoredDevice = MdbListDevicePending & { deviceCode: string };

const deviceKey = (profileId: string) => connectionKvKey(MDBLIST_DEVICE_KEY, profileId);

/** The sign-in page must be MDBList's own, as the TV insists: it is shown as a link. */
const verificationUrl = (value: unknown): string => {
  if (typeof value !== "string") throw new MdbListError("MDBList returned an unexpected sign-in page");
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new MdbListError("MDBList returned an unexpected sign-in page");
  }
  if (url.protocol !== "https:" || url.hostname !== "mdblist.com" || url.username || url.password) {
    throw new MdbListError("MDBList returned an unexpected sign-in page");
  }
  return url.toString();
};

const readDevice = async (profileId: string): Promise<StoredDevice | null> => {
  const stored = await readSecretKv(deviceKey(profileId));
  if (!stored) return null;
  try {
    return JSON.parse(stored) as StoredDevice;
  } catch {
    return null;
  }
};

const publicPending = (stored: StoredDevice): MdbListDevicePending => ({
  userCode: stored.userCode,
  verificationUrl: stored.verificationUrl,
  verificationUrlComplete: stored.verificationUrlComplete,
  expiresAt: stored.expiresAt,
  interval: stored.interval,
});

/** Starts a sign-in: a code the person enters on mdblist.com. */
export const startMdbListDevice = async (profileId: string): Promise<MdbListDevicePending> => {
  const id = clientId();
  if (!id) throw new MdbListError("MDBLIST_CLIENT_ID is not configured");
  const response = await mdbRequest<{
    device_code?: string;
    user_code?: string;
    verification_uri?: string;
    verification_uri_complete?: string;
    expires_in?: number;
    interval?: number;
  }>("POST", "/oauth/device-authorization/", { form: { client_id: id, scope: "write" } });
  const deviceCode = response?.device_code?.trim();
  const userCode = response?.user_code?.trim();
  const interval = response?.interval;
  if (!deviceCode || !userCode || !response?.expires_in || !interval || interval < 1 || interval > 3600) {
    throw new MdbListError("MDBList returned an unexpected sign-in code");
  }
  const page = verificationUrl(response.verification_uri);
  const complete = response.verification_uri_complete
    ? verificationUrl(response.verification_uri_complete)
    : `${page}${page.includes("?") ? "&" : "?"}user_code=${encodeURIComponent(userCode)}`;
  const stored: StoredDevice = {
    deviceCode,
    userCode,
    verificationUrl: page,
    verificationUrlComplete: complete,
    expiresAt: new Date(Date.now() + response.expires_in * 1000).toISOString(),
    interval,
  };
  // The device code is what turns into tokens, so it is kept like one.
  await writeSecretKv(deviceKey(profileId), JSON.stringify(stored));
  return publicPending(stored);
};

type TokenResponse = { access_token?: string; refresh_token?: string; token_type?: string; expires_in?: number; scope?: string };

const credentialsFrom = (response: TokenResponse, previousRefresh?: string): MdbListCredentials => {
  const accessToken = response?.access_token?.trim();
  const refreshToken = response?.refresh_token?.trim() || previousRefresh;
  if (!accessToken || !refreshToken || (response.token_type ?? "").toLowerCase() !== "bearer" || !response.expires_in) {
    throw new MdbListError("MDBList returned unexpected tokens");
  }
  if (response.scope && !response.scope.split(/\s+/).includes("write")) {
    throw new MdbListError("MDBList didn't grant write access");
  }
  return { accessToken, refreshToken, expiresAt: new Date(Date.now() + response.expires_in * 1000).toISOString() };
};

export type MdbListDevicePoll = "pending" | "connected" | "expired" | "denied" | "none";

/** Checks once whether the person has approved the code yet; connects the profile if so. */
export const pollMdbListDevice = async (profileId: string): Promise<MdbListDevicePoll> => {
  const id = clientId();
  const device = await readDevice(profileId);
  if (!id || !device) return "none";
  if (Date.parse(device.expiresAt) <= Date.now()) {
    await deleteSecretKv(deviceKey(profileId));
    return "expired";
  }
  let tokens: TokenResponse;
  try {
    tokens = await mdbRequest<TokenResponse>("POST", "/oauth/token/", {
      form: {
        grant_type: "urn:ietf:params:oauth:grant-type:device_code",
        device_code: device.deviceCode,
        client_id: id,
        scope: "write",
      },
    });
  } catch (error) {
    const code = error instanceof MdbListError ? error.code : null;
    if (code === "authorization_pending" || code === "slow_down") return "pending";
    if (code === "access_denied" || code === "expired_token") {
      await deleteSecretKv(deviceKey(profileId));
      return code === "access_denied" ? "denied" : "expired";
    }
    throw error;
  }
  const credentials = credentialsFrom(tokens);
  let username: string | null = null;
  try {
    const user = await mdbRequest<{ username?: string; name?: string }>("GET", "/user", { token: credentials.accessToken });
    username = user?.username?.trim() || user?.name?.trim() || null;
  } catch {
    // The sign-in worked; the name is only for Settings to show.
  }
  await saveConnection<MdbListCredentials, MdbListDetails>({
    profileId,
    provider: "mdblist",
    credentials,
    details: { username },
  });
  await deleteSecretKv(deviceKey(profileId));
  return "connected";
};

export const getMdbListStatus = async (profileId: string) => {
  const [connection, device, activities] = await Promise.all([
    getConnection<MdbListCredentials, MdbListDetails>(profileId, "mdblist"),
    readDevice(profileId),
    prisma.kV.findUnique({ where: { key: connectionKvKey(MDBLIST_ACTIVITIES_KEY, profileId) } }),
  ]);
  return {
    configured: isMdbListConfigured(),
    connected: !!connection,
    username: connection?.details.username ?? null,
    lastSyncedAt: activities?.updatedAt.toISOString() ?? null,
    pending: device && Date.parse(device.expiresAt) > Date.now() ? publicPending(device) : null,
  };
};

/** Revokes the token with MDBList (best-effort) and forgets the sign-in. */
export const disconnectMdbList = async (profileId: string): Promise<void> => {
  const connection = await getConnection<MdbListCredentials, MdbListDetails>(profileId, "mdblist");
  const id = clientId();
  if (connection?.credentials && id) {
    try {
      await mdbRequest("POST", "/oauth/revoke_token/", { form: { token: connection.credentials.accessToken, client_id: id } });
    } catch {
      // Forgetting it here is what disconnecting means; MDBList's copy expires.
    }
  }
  await deleteConnection(profileId, "mdblist");
  await deleteSecretKv(deviceKey(profileId));
  await prisma.kV.deleteMany({ where: { key: connectionKvKey(MDBLIST_ACTIVITIES_KEY, profileId) } });
};

/**
 * A usable access token for the profile, refreshed first if it is about to
 * run out. Null when it has no MDBList connection, or MDBList has revoked it
 * (the connection is then dropped, as the TV drops it).
 */
const accessTokenFor = async (profileId: string): Promise<string | null> => {
  const id = clientId();
  if (!id) return null;
  const connection = await getConnection<MdbListCredentials, MdbListDetails>(profileId, "mdblist");
  const credentials = connection?.credentials;
  if (!connection || !credentials) return null;
  if (Date.parse(credentials.expiresAt) - REFRESH_MARGIN_MS > Date.now()) return credentials.accessToken;
  try {
    const refreshed = credentialsFrom(
      await mdbRequest<TokenResponse>("POST", "/oauth/token/", {
        form: { grant_type: "refresh_token", refresh_token: credentials.refreshToken, client_id: id },
      }),
      credentials.refreshToken
    );
    await saveConnection({ profileId, provider: "mdblist", credentials: refreshed, details: connection.details });
    return refreshed.accessToken;
  } catch (error) {
    if (error instanceof MdbListError && (error.code === "invalid_grant" || error.status === 401)) {
      await deleteConnection(profileId, "mdblist");
    }
    throw error;
  }
};

// ─── Reading the account ───

type Ids = { imdb?: string | null; imdbid?: string | null };
type Media = { title?: string | null; name?: string | null; ids?: Ids | null };
type WatchedPage = {
  movies?: { movie?: Media; last_watched_at?: string | null; watched_at?: string | null }[] | null;
  shows?: {
    show?: Media;
    seasons?: { number?: number | null; episodes?: { number?: number | null; last_watched_at?: string | null; watched_at?: string | null }[] | null }[] | null;
  }[] | null;
  episodes?: { show?: Media; episode?: { season?: number | null; number?: number | null }; watched_at?: string | null; last_watched_at?: string | null }[] | null;
  pagination?: { offset?: number; limit?: number; total?: number; has_more?: boolean; next_cursor?: string } | null;
};
type WatchlistRow = { mediatype?: string; type?: string; imdb_id?: string | null; title?: string | null; ids?: Ids | null };
type WatchlistPage = {
  items?: WatchlistRow[] | null;
  movies?: WatchlistRow[] | null;
  shows?: WatchlistRow[] | null;
  pagination?: WatchedPage["pagination"];
};

const imdbOf = (ids: Ids | null | undefined): string | null => {
  const id = (ids?.imdb ?? ids?.imdbid)?.trim();
  return id && /^tt\d+$/.test(id) ? id : null;
};

/** Turns /sync/watched pages into plays. Exported for tests. */
export const projectMdbListWatched = (pages: WatchedPage[]): ImportedWatch[] => {
  const watches: ImportedWatch[] = [];
  for (const page of pages) {
    for (const row of page?.movies ?? []) {
      const imdbId = imdbOf(row.movie?.ids);
      const at = row.last_watched_at ?? row.watched_at;
      if (imdbId && at) watches.push({ type: "movie", imdbId, watchedAt: new Date(at), title: row.movie?.title });
    }
    for (const row of page?.shows ?? []) {
      const imdbId = imdbOf(row.show?.ids);
      if (!imdbId) continue;
      for (const season of row.seasons ?? []) {
        if (typeof season.number !== "number") continue;
        for (const episode of season.episodes ?? []) {
          const at = episode.last_watched_at ?? episode.watched_at;
          if (typeof episode.number !== "number" || !at) continue;
          watches.push({
            type: "episode",
            seriesImdbId: imdbId,
            season: season.number,
            episode: episode.number,
            watchedAt: new Date(at),
            title: row.show?.title,
          });
        }
      }
    }
    for (const row of page?.episodes ?? []) {
      const imdbId = imdbOf(row.show?.ids);
      const at = row.watched_at ?? row.last_watched_at;
      const season = row.episode?.season;
      const episode = row.episode?.number;
      if (!imdbId || !at || typeof season !== "number" || typeof episode !== "number") continue;
      watches.push({ type: "episode", seriesImdbId: imdbId, season, episode, watchedAt: new Date(at), title: row.show?.title });
    }
  }
  return watches;
};

/** Turns /watchlist/items pages into watchlist entries. Exported for tests. */
export const projectMdbListWatchlist = (pages: WatchlistPage[]): ImportedWatchlistItem[] => {
  const items: ImportedWatchlistItem[] = [];
  const add = (row: WatchlistRow, bucket: "movie" | "series" | null) => {
    const declared = (row.mediatype ?? row.type)?.toLowerCase();
    const type = declared === "movie" ? "movie" : declared === "show" || declared === "tv" ? "series" : bucket;
    const imdbId = imdbOf({ imdb: row.imdb_id ?? row.ids?.imdb ?? null });
    if (type && imdbId) items.push({ type, imdbId, title: row.title });
  };
  for (const page of pages) {
    for (const row of page?.items ?? []) add(row, null);
    for (const row of page?.movies ?? []) add(row, "movie");
    for (const row of page?.shows ?? []) add(row, "series");
  }
  return items;
};

const readPages = async <T extends { pagination?: WatchedPage["pagination"] }>(path: string, token: string): Promise<T[]> => {
  const pages: T[] = [];
  let query: Record<string, string> = { limit: String(PAGE_LIMIT) };
  for (let i = 0; i < MAX_PAGES; i += 1) {
    const page = await mdbRequest<T>("GET", path, { token, query });
    pages.push(page);
    const pagination = page?.pagination;
    if (pagination?.next_cursor) {
      query = { limit: String(PAGE_LIMIT), cursor: pagination.next_cursor };
    } else if (
      pagination?.has_more ??
      (pagination?.offset !== undefined && pagination.limit !== undefined && pagination.total !== undefined
        ? pagination.offset + pagination.limit < pagination.total
        : false)
    ) {
      query = { limit: String(PAGE_LIMIT), offset: String((pagination?.offset ?? 0) + (pagination?.limit ?? PAGE_LIMIT)) };
    } else {
      break;
    }
  }
  return pages;
};

/**
 * Brings the profile's MDBList watched history and watchlist into Fetch.
 * Skipped when MDBList's last-activities say nothing has changed since the
 * last sync, so a quiet account costs one small request per pass.
 */
export const syncMdbList = async (
  profileId: string,
  logger: FastifyBaseLogger,
  options: { full?: boolean } = {}
): Promise<ImportSummary & { full: boolean; unchanged: boolean }> => {
  const empty = { movies: 0, episodes: 0, watchlist: 0, skipped: 0, full: false, unchanged: false };
  const token = await accessTokenFor(profileId);
  if (!token) return empty;

  const activitiesKey = connectionKvKey(MDBLIST_ACTIVITIES_KEY, profileId);
  const activities = JSON.stringify(await mdbRequest<unknown>("GET", "/sync/last_activities", { token }));
  const previous = (await prisma.kV.findUnique({ where: { key: activitiesKey } }))?.value;
  if (!options.full && previous === activities) return { ...empty, unchanged: true };

  const [watched, watchlist] = await Promise.all([
    readPages<WatchedPage>("/sync/watched", token),
    readPages<WatchlistPage>("/watchlist/items", token),
  ]);
  const summary = await importTrackerItems(
    profileId,
    projectMdbListWatched(watched),
    projectMdbListWatchlist(watchlist),
    logger
  );
  const now = new Date();
  await prisma.kV.upsert({
    where: { key: activitiesKey },
    create: { key: activitiesKey, value: activities, updatedAt: now },
    update: { value: activities, updatedAt: now },
  });
  return { ...summary, full: previous === undefined || options.full === true, unchanged: false };
};

/** Every profile with MDBList connected, for the scheduled sync. */
export const listMdbListProfiles = async (): Promise<string[]> =>
  isMdbListConfigured() ? (await listConnections("mdblist")).map((connection) => connection.profileId) : [];

// ─── Sending to MDBList ───
//
// Best-effort, like the Trakt and Simkl pushes.

type Watch =
  | { type: "movie"; imdbId: string; watchedAt?: Date }
  | { type: "episode"; seriesImdbId: string; season: number | null | undefined; episode: number | null | undefined; watchedAt?: Date };

const at = (date: Date | undefined) => (date ? { watched_at: date.toISOString() } : {});

export const pushMdbListWatched = async (profileId: string, watch: Watch, logger: FastifyBaseLogger): Promise<void> => {
  if (watch.type === "episode" && (watch.season == null || watch.episode == null)) return;
  try {
    const token = await accessTokenFor(profileId);
    if (!token) return;
    const body =
      watch.type === "movie"
        ? { movies: [{ ids: { imdb: watch.imdbId }, ...at(watch.watchedAt) }] }
        : {
            shows: [
              {
                ids: { imdb: watch.seriesImdbId },
                seasons: [{ number: watch.season, episodes: [{ number: watch.episode, ...at(watch.watchedAt) }] }],
              },
            ],
          };
    await mdbRequest("POST", "/sync/watched", { token, body });
  } catch (error) {
    logger.warn({ error }, "MDBList watched push failed");
  }
};

export const pushMdbListWatchlistAdd = async (
  profileId: string,
  item: { type: "movie" | "series"; imdbId: string },
  logger: FastifyBaseLogger
): Promise<void> => {
  try {
    const token = await accessTokenFor(profileId);
    if (!token) return;
    const entry = { ids: { imdb: item.imdbId } };
    await mdbRequest("POST", "/watchlist/items/add", {
      token,
      body: item.type === "movie" ? { movies: [entry] } : { shows: [entry] },
    });
  } catch (error) {
    logger.warn({ error }, "MDBList watchlist push failed");
  }
};

export const pushMdbListScrobble = async (
  action: "start" | "pause" | "stop",
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
    const token = await accessTokenFor(params.profileId);
    if (!token) return;
    const progress = Math.floor(Math.max(0, Math.min(100, params.progress)) * 100) / 100;
    const body =
      params.type === "movie"
        ? { movie: { ids: { imdb: params.imdbId } }, progress }
        : { show: { ids: { imdb: params.seriesImdbId ?? params.imdbId }, season: params.season, episode: params.episode }, progress };
    await mdbRequest("POST", `/scrobble/${action}`, { token, body });
  } catch (error) {
    logger.warn({ error, action }, "MDBList scrobble failed");
  }
};
