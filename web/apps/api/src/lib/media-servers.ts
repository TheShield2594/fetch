import { createHash } from "node:crypto";
import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";
import {
  type Connection,
  type ConnectionProvider,
  changeConnectionDetails,
  deleteConnectionById,
  getConnection,
  getProfileConnectionById,
  listConnections,
  listProfileConnections,
  saveConnection,
  updateConnectionState,
} from "./connections.js";
import { connectionKvKey } from "./profile-connections.js";
import { PLEX_PIN_KEY } from "./owner-keys.js";
import { outboundScopeForProfile } from "./integration-owner.js";
import { type OutboundScope, resolveServerUrl } from "./ssrf.js";
import { importTrackerItems, type ImportedWatch } from "./tracker-import.js";
import { importPlaybackProgress, type PlaybackImportRow } from "./playback.js";

// Plex, Jellyfin and Emby sign-ins, as the TV app has them (app/.../data/
// mediaserver): Plex through a plex.tv link code, Jellyfin and Emby with the
// server's address and a user name and password. The website doesn't play
// from them; it does what the TV's "Import watch progress" does — reads what
// the server says was watched (and where playback stopped) into the profile —
// and marks plays recorded in Fetch as played on the server.
//
// Each profile signs in on its own, separately from the TV. Only the admin
// account's profiles may reach a server on this network (lib/ssrf.ts); anyone
// else's server has to be reachable at a public address.

export type MediaServerProvider = Extract<ConnectionProvider, "plex" | "jellyfin" | "emby">;

export const MEDIA_SERVER_PROVIDERS: { id: MediaServerProvider; name: string; signIn: "link" | "password" }[] = [
  { id: "plex", name: "Plex", signIn: "link" },
  { id: "jellyfin", name: "Jellyfin", signIn: "password" },
  { id: "emby", name: "Emby", signIn: "password" },
];

export const isMediaServerProvider = (value: unknown): value is MediaServerProvider =>
  value === "plex" || value === "jellyfin" || value === "emby";

export type MediaServerCredentials = { token: string };
export type MediaServerDetails = {
  serverName: string;
  address: string;
  serverId: string;
  userId: string;
  userName: string;
  /** The TV's "Import watch progress": whether a sync brings plays and resume points in. */
  importWatchState: boolean;
  lastSyncedAt?: string | null;
  /**
   * The server's film and series libraries as last seen, each with whether a
   * sync reads it, as the TV's `ServerConnection.libraries`. Missing on a
   * sign-in from before libraries could be chosen; a library not listed here
   * (new on the server since) is read, so the default is all of them.
   */
  libraries?: MediaServerLibrary[];
  /**
   * The libraries the last sync read. One chosen since (or new on the server)
   * wasn't, so the next sync reads it in full rather than only what changed
   * since `lastSyncedAt`. Missing when that sync read the whole server.
   */
  coveredLibraries?: string[];
};
export type MediaServerLibraryKind = "movies" | "shows" | "mixed";
export type MediaServerLibrary = { id: string; name: string; kind: MediaServerLibraryKind; selected: boolean };
/** IMDb id (or "imdb:season:episode") to the server's item id, for marking plays. */
export type MediaServerState = { movies?: Record<string, string>; episodes?: Record<string, string> };
type ServerConnection = Connection<MediaServerCredentials, MediaServerDetails, MediaServerState>;

export class MediaServerError extends Error {
  constructor(
    message: string,
    readonly kind: "unreachable" | "auth" | "unsupported" | "blocked" | "failed" = "failed"
  ) {
    super(message);
  }
}

const REQUEST_TIMEOUT_MS = 20_000;
const PAGE_SIZE = 500;
/** Items read per library pass, so a misbehaving server can't page forever. */
const MAX_ITEMS = 50_000;
const PLEX_TV = "https://plex.tv";
const CLIENT_VERSION = "web";
const IMDB_ID = /^tt\d+$/;
/** Libraries kept per server, and the most a selection may name. */
export const MAX_LIBRARIES = 500;

/** A per-profile id the servers know this sign-in by, stable across restarts. */
const deviceId = (profileId: string) =>
  createHash("sha256")
    .update(`fetch-media-server:${process.env.API_TOKEN ?? ""}:${profileId}`)
    .digest("hex")
    .slice(0, 32);

const readJson = async (response: Response): Promise<unknown> => {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    throw new MediaServerError("The server answered with something that isn't JSON");
  }
};

/** Every request to a server goes through the address check first, under the profile's scope. */
const serverFetch = async (
  scope: OutboundScope,
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: string } = {}
): Promise<Response> => {
  if (!(await resolveServerUrl(url, scope))) {
    throw new MediaServerError(
      scope.allowPrivateNetwork
        ? "That address isn't one this server may connect to"
        : "That server is on a private network; only the admin's profiles can use one. Use its public address.",
      "blocked"
    );
  }
  let response: Response;
  try {
    response = await fetch(url, {
      method: init.method ?? "GET",
      headers: { Accept: "application/json", ...init.headers },
      ...(init.body !== undefined ? { body: init.body } : {}),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      redirect: "error",
    });
  } catch {
    throw new MediaServerError("Couldn't reach the server", "unreachable");
  }
  if (response.status === 401 || response.status === 403) throw new MediaServerError("The server refused the sign-in", "auth");
  if (!response.ok) throw new MediaServerError(`The server answered ${response.status}`, response.status >= 500 ? "unreachable" : "failed");
  return response;
};

const withQuery = (base: string, path: string, query: Record<string, string | undefined> = {}) => {
  const url = new URL(base.replace(/\/+$/, "") + path);
  for (const [key, value] of Object.entries(query)) if (value !== undefined) url.searchParams.set(key, value);
  return url.toString();
};

/** The server's current libraries, keeping what was chosen for the ones seen before; new ones start selected. */
export const mergeLibraries = (
  previous: MediaServerLibrary[] | undefined,
  current: Omit<MediaServerLibrary, "selected">[]
): MediaServerLibrary[] =>
  current.slice(0, MAX_LIBRARIES).map((library) => ({
    ...library,
    selected: previous?.find((known) => known.id === library.id)?.selected ?? true,
  }));

/**
 * The libraries to store at a sign-in: the ones just listed, keeping what was
 * chosen if this server was signed in to before (or the stored list, if the
 * server wouldn't list them now).
 */
const knownLibraries = async (
  profileId: string,
  provider: MediaServerProvider,
  externalKey: string,
  listed: Omit<MediaServerLibrary, "selected">[] | null
): Promise<MediaServerLibrary[] | undefined> => {
  const previous = (await getConnection<MediaServerCredentials, MediaServerDetails>(profileId, provider, externalKey))?.details
    .libraries;
  return listed ? mergeLibraries(previous, listed) : previous;
};

// ─── Plex ───

const plexHeaders = (profileId: string, token?: string): Record<string, string> => ({
  "X-Plex-Product": "Fetch",
  "X-Plex-Version": CLIENT_VERSION,
  "X-Plex-Client-Identifier": deviceId(profileId),
  "X-Plex-Platform": "Web",
  "X-Plex-Device": "Fetch website",
  "X-Plex-Device-Name": "Fetch website",
  ...(token ? { "X-Plex-Token": token } : {}),
});

export type PlexLink = { code: string; linkUrl: string; expiresAt: string; interval: number };
type StoredPlexPin = { id: number; code: string; expiresAt: string };

const plexPinKey = (profileId: string) => connectionKvKey(PLEX_PIN_KEY, profileId);

const readPlexPin = async (profileId: string): Promise<StoredPlexPin | null> => {
  const row = await prisma.kV.findUnique({ where: { key: plexPinKey(profileId) } });
  if (!row) return null;
  try {
    return JSON.parse(row.value) as StoredPlexPin;
  } catch {
    return null;
  }
};

export const publicPlexLink = (pin: StoredPlexPin): PlexLink => ({
  code: pin.code,
  linkUrl: "https://plex.tv/link",
  expiresAt: pin.expiresAt,
  interval: 2,
});

/** Starts a Plex sign-in: a code the person enters at plex.tv/link. */
export const startPlexLink = async (profileId: string): Promise<PlexLink> => {
  const response = await fetch(withQuery(PLEX_TV, "/api/v2/pins", { strong: "false" }), {
    method: "POST",
    headers: { Accept: "application/json", ...plexHeaders(profileId) },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    redirect: "error",
  });
  if (!response.ok) throw new MediaServerError(`plex.tv answered ${response.status}`, "unreachable");
  const pin = (await readJson(response)) as { id?: number; code?: string; expiresIn?: number } | null;
  if (typeof pin?.id !== "number" || !pin.code || !/^[A-Za-z0-9]{4,12}$/.test(pin.code)) {
    throw new MediaServerError("plex.tv returned an unexpected link code");
  }
  const stored: StoredPlexPin = {
    id: pin.id,
    code: pin.code,
    expiresAt: new Date(Date.now() + (pin.expiresIn ?? 900) * 1000).toISOString(),
  };
  const now = new Date();
  await prisma.kV.upsert({
    where: { key: plexPinKey(profileId) },
    create: { key: plexPinKey(profileId), value: JSON.stringify(stored), updatedAt: now },
    update: { value: JSON.stringify(stored), updatedAt: now },
  });
  return publicPlexLink(stored);
};

type PlexResource = {
  name?: string;
  provides?: string;
  clientIdentifier?: string;
  accessToken?: string;
  productVersion?: string;
  connections?: { uri?: string; local?: boolean; relay?: boolean }[];
};

/** Plex Media Server 1.30 or later, as the TV requires. */
const plexVersionSupported = (version: string | undefined) => {
  const [major = 0, minor = 0] = (version ?? "").split(".").map((part) => Number.parseInt(part, 10) || 0);
  return major > 1 || (major === 1 && minor >= 30);
};

/** The first of a server's addresses that answers as that server: local, then remote, then relayed. */
const reachablePlexAddress = async (profileId: string, scope: OutboundScope, server: PlexResource, token: string) => {
  const ordered = [...(server.connections ?? [])].sort(
    (a, b) => Number(!a.local) - Number(!b.local) || Number(!!a.relay) - Number(!!b.relay)
  );
  for (const connection of ordered) {
    const address = connection.uri?.replace(/\/+$/, "");
    if (!address) continue;
    try {
      const response = await serverFetch(scope, `${address}/identity`, { headers: plexHeaders(profileId, token) });
      const identity = (await readJson(response)) as { MediaContainer?: { machineIdentifier?: string } } | null;
      const id = identity?.MediaContainer?.machineIdentifier;
      if (!id || id === server.clientIdentifier) return address;
    } catch {
      // Try the next address.
    }
  }
  return null;
};

export type PlexLinkPoll = { result: "pending" | "connected" | "expired" | "none"; servers: number };

/**
 * Checks once whether the code has been entered; when it has, finds the
 * account's servers and saves a sign-in for each one this profile can reach.
 */
export const pollPlexLink = async (profileId: string): Promise<PlexLinkPoll> => {
  const pin = await readPlexPin(profileId);
  if (!pin) return { result: "none", servers: 0 };
  if (Date.parse(pin.expiresAt) <= Date.now()) {
    await prisma.kV.deleteMany({ where: { key: plexPinKey(profileId) } });
    return { result: "expired", servers: 0 };
  }
  const response = await fetch(withQuery(PLEX_TV, `/api/v2/pins/${pin.id}`, { code: pin.code }), {
    headers: { Accept: "application/json", ...plexHeaders(profileId) },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    redirect: "error",
  });
  if (response.status === 404) {
    // plex.tv forgets a PIN once it expires.
    await prisma.kV.deleteMany({ where: { key: plexPinKey(profileId) } });
    return { result: "expired", servers: 0 };
  }
  if (!response.ok) throw new MediaServerError(`plex.tv answered ${response.status}`, "unreachable");
  const accountToken = ((await readJson(response)) as { authToken?: string | null } | null)?.authToken?.trim();
  if (!accountToken) return { result: "pending", servers: 0 };

  const tvGet = async <T>(path: string, query: Record<string, string> = {}): Promise<T> => {
    const answer = await fetch(withQuery(PLEX_TV, path, query), {
      headers: { Accept: "application/json", ...plexHeaders(profileId, accountToken) },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      redirect: "error",
    });
    if (!answer.ok) throw new MediaServerError(`plex.tv answered ${answer.status}`, "unreachable");
    return (await readJson(answer)) as T;
  };
  const account = await tvGet<{ id?: number; username?: string; title?: string; email?: string }>("/api/v2/user");
  const resources = await tvGet<PlexResource[]>("/api/v2/resources", {
    includeHttps: "1",
    includeRelay: "1",
    includeIPv6: "1",
  });
  const servers = (resources ?? []).filter((resource) => resource.provides?.split(",").includes("server"));
  const supported = servers.filter((server) => plexVersionSupported(server.productVersion) && server.clientIdentifier);
  if (servers.length > 0 && supported.length === 0) {
    throw new MediaServerError("Plex Media Server 1.30 or later is needed", "unsupported");
  }

  const scope = await outboundScopeForProfile(profileId);
  const userId = String(account?.id ?? "");
  const userName = account?.username ?? account?.title ?? account?.email ?? "";
  let saved = 0;
  for (const server of supported) {
    const token = server.accessToken?.trim() || accountToken;
    const address = await reachablePlexAddress(profileId, scope, server, token);
    if (!address) continue;
    // Listed now so they can be chosen before the first sync; a server that
    // won't list them yet still signs in, and the sync lists them.
    const libraries = await plexLibraries(address, profileId, token, scope).catch(() => null);
    const externalKey = `${server.clientIdentifier}|${userId}`;
    const known = await knownLibraries(profileId, "plex", externalKey, libraries);
    await saveConnection<MediaServerCredentials, MediaServerDetails>({
      profileId,
      provider: "plex",
      externalKey,
      credentials: { token },
      details: {
        serverName: server.name?.trim() || new URL(address).host,
        address,
        serverId: server.clientIdentifier!,
        userId,
        userName,
        importWatchState: true,
        lastSyncedAt: null,
        ...(known ? { libraries: known } : {}),
      },
    });
    saved += 1;
  }
  await prisma.kV.deleteMany({ where: { key: plexPinKey(profileId) } });
  if (supported.length > 0 && saved === 0) {
    throw new MediaServerError(
      scope.allowPrivateNetwork
        ? "None of your Plex servers answered from here"
        : "None of your Plex servers answered at a public address. Turn on Remote Access in Plex.",
      "unreachable"
    );
  }
  return { result: "connected", servers: saved };
};

// ─── Jellyfin and Emby ───

const mediaBrowserApiRoot = (provider: "jellyfin" | "emby", address: string) =>
  provider === "emby" ? `${address}/emby` : address;

const mediaBrowserHeaders = (provider: "jellyfin" | "emby", profileId: string, token?: string): Record<string, string> => {
  const value =
    `MediaBrowser Client="Fetch", Device="Fetch website", DeviceId="${deviceId(profileId)}", Version="${CLIENT_VERSION}"` +
    (token ? `, Token="${token.replace(/"/g, "")}"` : "");
  return { [provider === "emby" ? "X-Emby-Authorization" : "Authorization"]: value };
};

/** `jellyfin.example.com` → `https://jellyfin.example.com`; trailing slashes and an `/emby` suffix dropped. */
export const normalizeServerAddress = (input: string): string | null => {
  const trimmed = input.trim();
  if (!trimmed) return null;
  let url: URL;
  try {
    url = new URL(/^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  const path = url.pathname.replace(/\/+$/, "").replace(/\/emby$/i, "");
  return `${url.protocol}//${url.host}${path}`;
};

const versionAtLeast = (version: string | undefined, major: number, minor: number) => {
  const [a = 0, b = 0] = (version ?? "").split(".").map((part) => Number.parseInt(part, 10) || 0);
  return a > major || (a === major && b >= minor);
};

/** Signs a profile in to a Jellyfin or Emby server with a user name and password. */
export const signInMediaBrowser = async (
  profileId: string,
  provider: "jellyfin" | "emby",
  input: { address: string; username: string; password: string }
): Promise<MediaServerDetails> => {
  const address = normalizeServerAddress(input.address);
  if (!address) throw new MediaServerError("That isn't a server address", "failed");
  const scope = await outboundScopeForProfile(profileId);
  const root = mediaBrowserApiRoot(provider, address);

  const info = (await readJson(await serverFetch(scope, `${root}/System/Info/Public`))) as {
    ServerName?: string;
    Version?: string;
    Id?: string;
    ProductName?: string;
  } | null;
  const isJellyfin = info?.ProductName?.toLowerCase().includes("jellyfin") ?? false;
  if (provider === "jellyfin" ? !versionAtLeast(info?.Version, 10, 9) : isJellyfin || !versionAtLeast(info?.Version, 4, 7)) {
    throw new MediaServerError(
      provider === "jellyfin" ? "That isn't a Jellyfin 10.9 or later server" : "That isn't an Emby 4.7 or later server",
      "unsupported"
    );
  }

  const response = await serverFetch(scope, `${root}/Users/AuthenticateByName`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...mediaBrowserHeaders(provider, profileId) },
    body: JSON.stringify({ Username: input.username, Pw: input.password }),
  });
  const result = (await readJson(response)) as { AccessToken?: string; ServerId?: string; User?: { Id?: string; Name?: string } } | null;
  const token = result?.AccessToken?.trim();
  const userId = result?.User?.Id;
  const serverId = result?.ServerId ?? info?.Id;
  if (!token || !userId || !serverId) throw new MediaServerError("The server refused the sign-in", "auth");

  const libraries = await mediaBrowserLibraries(provider, { address, userId }, profileId, token, scope).catch(() => null);
  const known = await knownLibraries(profileId, provider, `${serverId}|${userId}`, libraries);
  const details: MediaServerDetails = {
    serverName: info?.ServerName?.trim() || new URL(address).host,
    address,
    serverId,
    userId,
    userName: result?.User?.Name ?? input.username,
    importWatchState: true,
    lastSyncedAt: null,
    ...(known ? { libraries: known } : {}),
  };
  await saveConnection<MediaServerCredentials, MediaServerDetails>({
    profileId,
    provider,
    externalKey: `${serverId}|${userId}`,
    credentials: { token },
    details,
  });
  return details;
};

// ─── Reading a server ───

/** One title the server knows, with what it says about this user's viewing. */
type ServerTitle = {
  itemId: string;
  imdbId: string;
  season?: number;
  episode?: number;
  played: boolean;
  lastPlayedAt: Date | null;
  positionSeconds: number | null;
  durationSeconds: number | null;
  title?: string | null | undefined;
};

const imdbFromPlexGuids = (guids: { id?: string }[] | undefined, guid?: string): string | null => {
  for (const value of [...(guids ?? []).map((entry) => entry.id), guid]) {
    const match = value?.match(/(?:^|\.)imdb:\/\/(tt\d+)/i);
    if (match?.[1]) return match[1];
  }
  return null;
};

type PlexItem = {
  ratingKey?: string;
  guid?: string;
  Guid?: { id?: string }[];
  title?: string;
  grandparentTitle?: string;
  grandparentRatingKey?: string;
  parentIndex?: number;
  index?: number;
  viewCount?: number;
  lastViewedAt?: number;
  viewOffset?: number;
  duration?: number;
};

const plexItems = async (
  connection: ServerConnection,
  scope: OutboundScope,
  path: string,
  query: Record<string, string>
): Promise<PlexItem[]> => {
  const items: PlexItem[] = [];
  for (let start = 0; start < MAX_ITEMS; start += PAGE_SIZE) {
    const response = await serverFetch(
      scope,
      withQuery(connection.details.address, path, {
        ...query,
        includeGuids: "1",
        "X-Plex-Container-Start": String(start),
        "X-Plex-Container-Size": String(PAGE_SIZE),
      }),
      { headers: plexHeaders(connection.profileId, connection.credentials!.token) }
    );
    const page = ((await readJson(response)) as { MediaContainer?: { Metadata?: PlexItem[]; totalSize?: number } } | null)
      ?.MediaContainer;
    const metadata = page?.Metadata ?? [];
    items.push(...metadata);
    if (metadata.length < PAGE_SIZE || (page?.totalSize !== undefined && start + PAGE_SIZE >= page.totalSize)) break;
  }
  return items;
};

const plexTitle = (item: PlexItem, imdbId: string, extra: Partial<ServerTitle> = {}): ServerTitle => ({
  itemId: item.ratingKey!,
  imdbId,
  played: (item.viewCount ?? 0) > 0,
  lastPlayedAt: item.lastViewedAt ? new Date(item.lastViewedAt * 1000) : null,
  positionSeconds: item.viewOffset ? Math.round(item.viewOffset / 1000) : null,
  durationSeconds: item.duration ? Math.round(item.duration / 1000) : null,
  title: item.title,
  ...extra,
});

/** A Plex server's film and series libraries (`/library/sections`). */
const plexLibraries = async (
  address: string,
  profileId: string,
  token: string,
  scope: OutboundScope
): Promise<Omit<MediaServerLibrary, "selected">[]> => {
  const sections =
    ((await readJson(
      await serverFetch(scope, withQuery(address, "/library/sections"), { headers: plexHeaders(profileId, token) })
    )) as { MediaContainer?: { Directory?: { key?: string; type?: string; title?: string }[] } } | null)?.MediaContainer
      ?.Directory ?? [];
  return sections.flatMap((section) =>
    section.key && (section.type === "movie" || section.type === "show")
      ? [{ id: section.key, name: section.title?.trim() || section.key, kind: section.type === "movie" ? ("movies" as const) : ("shows" as const) }]
      : []
  );
};

const readPlex = async (connection: ServerConnection, scope: OutboundScope, libraries: MediaServerLibrary[]) => {
  const movies: ServerTitle[] = [];
  const episodes: ServerTitle[] = [];
  for (const library of libraries) {
    if (!library.selected) continue;
    const path = `/library/sections/${encodeURIComponent(library.id)}/all`;
    if (library.kind === "movies") {
      for (const item of await plexItems(connection, scope, path, { type: "1" })) {
        const imdbId = imdbFromPlexGuids(item.Guid, item.guid);
        if (imdbId && item.ratingKey) movies.push(plexTitle(item, imdbId));
      }
    } else if (library.kind === "shows") {
      const shows = new Map<string, string>();
      for (const show of await plexItems(connection, scope, path, { type: "2" })) {
        const imdbId = imdbFromPlexGuids(show.Guid, show.guid);
        if (imdbId && show.ratingKey) shows.set(show.ratingKey, imdbId);
      }
      for (const item of await plexItems(connection, scope, path, { type: "4" })) {
        const imdbId = item.grandparentRatingKey ? shows.get(item.grandparentRatingKey) : undefined;
        if (!imdbId || !item.ratingKey || item.parentIndex === undefined || item.index === undefined) continue;
        episodes.push(plexTitle(item, imdbId, { season: item.parentIndex, episode: item.index, title: item.grandparentTitle }));
      }
    }
  }
  return { movies, episodes };
};

type MediaBrowserItem = {
  Id?: string;
  Name?: string;
  SeriesId?: string;
  SeriesName?: string;
  ParentIndexNumber?: number;
  IndexNumber?: number;
  RunTimeTicks?: number;
  ProviderIds?: Record<string, string>;
  UserData?: { Played?: boolean; LastPlayedDate?: string; PlaybackPositionTicks?: number; PlayCount?: number };
};

const TICKS_PER_SECOND = 10_000_000;

const imdbFromProviderIds = (ids: Record<string, string> | undefined): string | null => {
  const value = Object.entries(ids ?? {}).find(([key]) => key.toLowerCase() === "imdb")?.[1]?.trim();
  return value && IMDB_ID.test(value) ? value : null;
};

const mediaBrowserItems = async (
  connection: ServerConnection,
  scope: OutboundScope,
  provider: "jellyfin" | "emby",
  itemType: string,
  userData: boolean,
  parentId?: string
): Promise<MediaBrowserItem[]> => {
  const { address, userId } = connection.details;
  const root = mediaBrowserApiRoot(provider, address);
  const path = provider === "emby" ? `/Users/${encodeURIComponent(userId)}/Items` : "/Items";
  const items: MediaBrowserItem[] = [];
  for (let start = 0; start < MAX_ITEMS; start += PAGE_SIZE) {
    const response = await serverFetch(
      scope,
      withQuery(root, path, {
        ...(provider === "jellyfin" ? { userId } : {}),
        ...(parentId ? { parentId } : {}),
        recursive: "true",
        includeItemTypes: itemType,
        fields: "ProviderIds",
        enableImages: "false",
        enableUserData: String(userData),
        startIndex: String(start),
        limit: String(PAGE_SIZE),
        enableTotalRecordCount: "true",
      }),
      { headers: mediaBrowserHeaders(provider, connection.profileId, connection.credentials!.token) }
    );
    const page = (await readJson(response)) as { Items?: MediaBrowserItem[]; TotalRecordCount?: number } | null;
    const batch = page?.Items ?? [];
    items.push(...batch);
    if (batch.length < PAGE_SIZE || (page?.TotalRecordCount !== undefined && start + PAGE_SIZE >= page.TotalRecordCount)) break;
  }
  return items;
};

const mediaBrowserTitle = (item: MediaBrowserItem, imdbId: string, extra: Partial<ServerTitle> = {}): ServerTitle => ({
  itemId: item.Id!,
  imdbId,
  played: item.UserData?.Played === true || (item.UserData?.PlayCount ?? 0) > 0,
  lastPlayedAt: item.UserData?.LastPlayedDate ? new Date(item.UserData.LastPlayedDate) : null,
  positionSeconds: item.UserData?.PlaybackPositionTicks ? Math.round(item.UserData.PlaybackPositionTicks / TICKS_PER_SECOND) : null,
  durationSeconds: item.RunTimeTicks ? Math.round(item.RunTimeTicks / TICKS_PER_SECOND) : null,
  title: item.Name,
  ...extra,
});

/** A Jellyfin or Emby user's film, series and mixed libraries (their views). */
const mediaBrowserLibraries = async (
  provider: "jellyfin" | "emby",
  details: Pick<MediaServerDetails, "address" | "userId">,
  profileId: string,
  token: string,
  scope: OutboundScope
): Promise<Omit<MediaServerLibrary, "selected">[]> => {
  const root = mediaBrowserApiRoot(provider, details.address);
  const url =
    provider === "jellyfin"
      ? withQuery(root, "/UserViews", { userId: details.userId })
      : withQuery(root, `/Users/${encodeURIComponent(details.userId)}/Views`);
  const views =
    ((await readJson(await serverFetch(scope, url, { headers: mediaBrowserHeaders(provider, profileId, token) }))) as {
      Items?: { Id?: string; Name?: string; CollectionType?: string }[];
    } | null)?.Items ?? [];
  return views.flatMap((view) => {
    const type = view.CollectionType?.toLowerCase();
    const kind: MediaServerLibraryKind | null =
      type === "movies" ? "movies" : type === "tvshows" ? "shows" : !type || type === "mixed" ? "mixed" : null;
    return view.Id && kind ? [{ id: view.Id, name: view.Name?.trim() || view.Id, kind }] : [];
  });
};

const readMediaBrowser = async (
  connection: ServerConnection,
  scope: OutboundScope,
  provider: "jellyfin" | "emby",
  libraries: MediaServerLibrary[]
) => {
  // With every library chosen, the whole server in one pass per type, as
  // before; otherwise one pass per chosen library.
  const parents: (string | undefined)[] = libraries.every((library) => library.selected)
    ? [undefined]
    : libraries.filter((library) => library.selected).map((library) => library.id);
  const read = async (itemType: string, userData: boolean) => {
    const items: MediaBrowserItem[] = [];
    for (const parentId of parents) items.push(...(await mediaBrowserItems(connection, scope, provider, itemType, userData, parentId)));
    return items;
  };

  const movies: ServerTitle[] = [];
  for (const item of await read("Movie", true)) {
    const imdbId = imdbFromProviderIds(item.ProviderIds);
    if (imdbId && item.Id) movies.push(mediaBrowserTitle(item, imdbId));
  }
  const series = new Map<string, string>();
  for (const item of await read("Series", false)) {
    const imdbId = imdbFromProviderIds(item.ProviderIds);
    if (imdbId && item.Id) series.set(item.Id, imdbId);
  }
  const episodes: ServerTitle[] = [];
  for (const item of await read("Episode", true)) {
    const imdbId = item.SeriesId ? series.get(item.SeriesId) : undefined;
    if (!imdbId || !item.Id || item.ParentIndexNumber === undefined || item.IndexNumber === undefined) continue;
    episodes.push(
      mediaBrowserTitle(item, imdbId, { season: item.ParentIndexNumber, episode: item.IndexNumber, title: item.SeriesName })
    );
  }
  return { movies, episodes };
};

/** Asks the server for its libraries now. */
const fetchLibraries = (connection: ServerConnection, scope: OutboundScope) => {
  const token = connection.credentials!.token;
  return connection.provider === "plex"
    ? plexLibraries(connection.details.address, connection.profileId, token, scope)
    : mediaBrowserLibraries(connection.provider as "jellyfin" | "emby", connection.details, connection.profileId, token, scope);
};

/** Re-reads a server's libraries, keeps the choices made for them, and saves the list. */
export const refreshMediaServerLibraries = async (connection: ServerConnection): Promise<MediaServerDetails> => {
  if (!connection.credentials) throw new MediaServerError("The stored sign-in can't be read; sign in again", "auth");
  const scope = await outboundScopeForProfile(connection.profileId);
  const listed = await fetchLibraries(connection, scope);
  const details = await changeConnectionDetails<MediaServerDetails>(connection.id, (current) => ({
    ...current,
    libraries: mergeLibraries(current.libraries, listed),
  }));
  if (!details) throw new MediaServerError("That server was removed");
  return details;
};

const episodeKey = (imdbId: string, season: number, episode: number) => `${imdbId}:${season}:${episode}`;

/** What a server's titles mean for the profile: plays, resume points, and the item index. Exported for tests. */
export const projectServerTitles = (titles: { movies: ServerTitle[]; episodes: ServerTitle[] }, syncedAt: Date) => {
  const watches: ImportedWatch[] = [];
  const resume: PlaybackImportRow[] = [];
  const state: Required<MediaServerState> = { movies: {}, episodes: {} };

  for (const title of titles.movies) {
    state.movies[title.imdbId] ??= title.itemId;
    if (title.played) {
      // A server without a date for the play still knows it happened; it is
      // dated to this sync, and added only if the profile has no play of it.
      watches.push({
        type: "movie",
        imdbId: title.imdbId,
        watchedAt: title.lastPlayedAt ?? syncedAt,
        undated: !title.lastPlayedAt,
        title: title.title,
      });
    } else if (title.positionSeconds && title.positionSeconds > 0) {
      resume.push({
        target: { type: "movie", imdbId: title.imdbId },
        progress: title.durationSeconds ? Math.min(99, (title.positionSeconds / title.durationSeconds) * 100) : 0,
        positionSeconds: title.positionSeconds,
        durationSeconds: title.durationSeconds,
        pausedAt: title.lastPlayedAt ?? syncedAt,
      });
    }
  }
  for (const title of titles.episodes) {
    const season = title.season!;
    const episode = title.episode!;
    state.episodes[episodeKey(title.imdbId, season, episode)] ??= title.itemId;
    if (title.played) {
      watches.push({
        type: "episode",
        seriesImdbId: title.imdbId,
        season,
        episode,
        watchedAt: title.lastPlayedAt ?? syncedAt,
        undated: !title.lastPlayedAt,
        title: title.title,
      });
    } else if (title.positionSeconds && title.positionSeconds > 0) {
      resume.push({
        target: { type: "episode", imdbId: title.imdbId, seriesImdbId: title.imdbId, season, episode },
        progress: title.durationSeconds ? Math.min(99, (title.positionSeconds / title.durationSeconds) * 100) : 0,
        positionSeconds: title.positionSeconds,
        durationSeconds: title.durationSeconds,
        pausedAt: title.lastPlayedAt ?? syncedAt,
      });
    }
  }
  return { watches, resume, state };
};

export type MediaServerSyncSummary = { movies: number; episodes: number; resumePoints: number; skipped: number };

/**
 * Reads one server into its profile: what the user watched there as plays
 * (deduped against what the profile already has), where they stopped as
 * resume points (only ever forward), and an index for marking plays later.
 */
export const syncMediaServer = async (connection: ServerConnection, logger: FastifyBaseLogger): Promise<MediaServerSyncSummary> => {
  if (!connection.credentials) throw new MediaServerError("The stored sign-in can't be read; sign in again", "auth");
  const scope = await outboundScopeForProfile(connection.profileId);
  const provider = connection.provider as MediaServerProvider;
  // The library list is read fresh each time, so one added on the server is
  // picked up (and read) without a visit to Settings. Jellyfin and Emby read
  // their items without it if the views can't be listed, as before libraries
  // could be chosen, so a views hiccup doesn't skip the sync.
  let listed: Omit<MediaServerLibrary, "selected">[] | null;
  try {
    listed = await fetchLibraries(connection, scope);
  } catch (error) {
    if (provider === "plex") throw error;
    logger.warn({ err: error, provider }, "Listing a media server's libraries failed; using the stored list");
    listed = null;
  }
  const libraries = listed ? mergeLibraries(connection.details.libraries, listed) : connection.details.libraries ?? [];
  const listKnown = listed !== null || connection.details.libraries !== undefined;
  const reading = libraries.filter((library) => library.selected).map((library) => library.id);
  const titles =
    provider === "plex"
      ? await readPlex(connection, scope, libraries)
      : await readMediaBrowser(connection, scope, provider, libraries);
  const syncedAt = new Date();
  const { watches, resume, state } = projectServerTitles(titles, syncedAt);

  // After the first pass, only what the server dated since the last one (with
  // a day's overlap): the rest was imported then, and re-checking a whole
  // library against the profile every hour is thousands of lookups for nothing.
  // Undated plays are only looked at on the first pass for the same reason.
  // A library the last sync didn't read (chosen since, or new on the server)
  // has never been imported, so this pass is a full one; plays already
  // recorded are skipped by the import, so nothing is added twice.
  const covered = connection.details.coveredLibraries;
  const newlyRead = listKnown && covered !== undefined && reading.some((id) => !covered.includes(id));
  const last = connection.details.lastSyncedAt ? Date.parse(connection.details.lastSyncedAt) : null;
  const cutoff = newlyRead || last === null || Number.isNaN(last) ? null : new Date(last - 24 * 60 * 60 * 1000);
  const recentWatches = cutoff ? watches.filter((watch) => !watch.undated && watch.watchedAt >= cutoff) : watches;
  const recentResume = cutoff ? resume.filter((row) => row.pausedAt >= cutoff) : resume;

  let summary: MediaServerSyncSummary = { movies: 0, episodes: 0, resumePoints: 0, skipped: 0 };
  if (connection.details.importWatchState) {
    const imported = await importTrackerItems(connection.profileId, recentWatches, [], logger);
    const resumePoints = await importPlaybackProgress(connection.profileId, recentResume);
    summary = { movies: imported.movies, episodes: imported.episodes, resumePoints, skipped: imported.skipped };
  }
  await updateConnectionState<MediaServerState>(connection.id, state);
  // From the stored details as they are now, so a choice made in Settings
  // while the server was being read isn't undone.
  await changeConnectionDetails<MediaServerDetails>(connection.id, (current) => {
    const next: MediaServerDetails = {
      ...current,
      ...(listed ? { libraries: mergeLibraries(current.libraries, listed) } : {}),
      lastSyncedAt: syncedAt.toISOString(),
    };
    if (listKnown) next.coveredLibraries = reading;
    else delete next.coveredLibraries;
    return next;
  });
  return summary;
};

export const listAllMediaServerConnections = async (): Promise<ServerConnection[]> => {
  const all = await Promise.all(
    MEDIA_SERVER_PROVIDERS.map((provider) =>
      listConnections<MediaServerCredentials, MediaServerDetails>(provider.id) as Promise<ServerConnection[]>
    )
  );
  return all.flat();
};

export const listProfileMediaServers = async (profileId: string): Promise<ServerConnection[]> => {
  const all = await Promise.all(
    MEDIA_SERVER_PROVIDERS.map(
      (provider) => listProfileConnections<MediaServerCredentials, MediaServerDetails>(profileId, provider.id) as Promise<ServerConnection[]>
    )
  );
  return all.flat();
};

export const getProfileMediaServer = (profileId: string, id: string) =>
  getProfileConnectionById<MediaServerCredentials, MediaServerDetails, MediaServerState>(profileId, id).then((connection) =>
    connection && isMediaServerProvider(connection.provider) ? connection : null
  );

export const getPlexLinkPending = async (profileId: string): Promise<PlexLink | null> => {
  const pin = await readPlexPin(profileId);
  return pin && Date.parse(pin.expiresAt) > Date.now() ? publicPlexLink(pin) : null;
};

/** Signs out of the server (best-effort, Jellyfin and Emby) and forgets the sign-in. */
export const removeMediaServer = async (connection: ServerConnection): Promise<void> => {
  if (connection.provider !== "plex" && connection.credentials) {
    const provider = connection.provider as "jellyfin" | "emby";
    try {
      const scope = await outboundScopeForProfile(connection.profileId);
      await serverFetch(scope, `${mediaBrowserApiRoot(provider, connection.details.address)}/Sessions/Logout`, {
        method: "POST",
        headers: mediaBrowserHeaders(provider, connection.profileId, connection.credentials.token),
      });
    } catch {
      // Forgetting it here is what removing it means.
    }
  }
  await deleteConnectionById(connection.profileId, connection.id);
};

// ─── Marking plays on the servers ───

const markPlayed = async (connection: ServerConnection, scope: OutboundScope, itemId: string) => {
  const token = connection.credentials!.token;
  const { address, userId } = connection.details;
  if (connection.provider === "plex") {
    await serverFetch(
      scope,
      withQuery(address, "/:/scrobble", { identifier: "com.plexapp.plugins.library", key: itemId }),
      { headers: plexHeaders(connection.profileId, token) }
    );
    return;
  }
  const provider = connection.provider as "jellyfin" | "emby";
  const root = mediaBrowserApiRoot(provider, address);
  const url =
    provider === "jellyfin"
      ? withQuery(root, `/UserPlayedItems/${encodeURIComponent(itemId)}`, { userId })
      : withQuery(root, `/Users/${encodeURIComponent(userId)}/PlayedItems/${encodeURIComponent(itemId)}`);
  await serverFetch(scope, url, { method: "POST", headers: mediaBrowserHeaders(provider, connection.profileId, token) });
};

/**
 * Marks a play recorded in Fetch as played on each of the profile's servers
 * that has the title, found by the index the last sync built. Best-effort.
 */
export const pushPlayedToMediaServers = async (
  profileId: string,
  watch:
    | { type: "movie"; imdbId: string }
    | { type: "episode"; seriesImdbId: string; season: number | null | undefined; episode: number | null | undefined },
  logger: FastifyBaseLogger
): Promise<void> => {
  try {
    const servers = await listProfileMediaServers(profileId);
    if (servers.length === 0) return;
    const scope = await outboundScopeForProfile(profileId);
    await Promise.all(
      servers.map(async (connection) => {
        const state = connection.state as MediaServerState;
        const itemId =
          watch.type === "movie"
            ? state.movies?.[watch.imdbId]
            : watch.season != null && watch.episode != null
              ? state.episodes?.[episodeKey(watch.seriesImdbId, watch.season, watch.episode)]
              : undefined;
        if (!itemId || !connection.credentials) return;
        try {
          await markPlayed(connection, scope, itemId);
        } catch (error) {
          logger.warn({ error, provider: connection.provider }, "Marking a play on a media server failed");
        }
      })
    );
  } catch (error) {
    logger.warn({ error }, "Media server played push failed");
  }
};
