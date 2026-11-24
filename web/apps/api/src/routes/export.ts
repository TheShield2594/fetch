import { ItemType, ListItemType, ListKind, MetadataType, Prisma, WatchEventType } from "@prisma/client";
import type { FastifyPluginAsync } from "fastify";
import { prisma } from "../lib/prisma.js";
import { hasParentalAuthority, invalidateProfileCache, resolveProfile } from "../lib/profile.js";
import { writeProfileGuarded } from "../lib/parental-write.js";
import { parseProfileAttributes, PROFILE_VIEW_SELECT, type ProfileAttributeUpdate } from "../lib/profile-view.js";
import { type CollectionJson, validateCollections } from "../lib/collections.js";
import { batchUpsertSeriesProgressIfNewer } from "../lib/series-progress.js";
import type { SeriesProgressCandidate } from "../lib/types.js";
import { getDefaultWatchlist } from "../lib/watchlist.js";
import { batchUpsertWatchEvents, type WatchEventInput } from "../lib/batch-watch-events.js";
import { batchUpsertRatings, type RatingInput } from "../lib/batch-ratings.js";
import { importPlaybackProgress, MAX_PLAYBACK_SECONDS, type PlaybackImportRow } from "../lib/playback.js";
import {
  boundedInt,
  boundedNumber,
  isRecord,
  MAX_EPISODE,
  MAX_IMPORT_ROWS,
  MAX_PLAYS,
  MAX_RATING,
  MAX_SEASON,
  MIN_RATING,
  tooManyRowsMessage,
} from "../lib/import-validation.js";
import { normalizeContentId } from "../lib/content-id.js";
import { droppedSeriesKey } from "../lib/dropped-shows.js";
import { MAX_TV_SETTINGS_BYTES, opaqueDocumentProblem } from "../lib/profile-document.js";
import {
  MAX_HOME_ROWS,
  listRowKey,
  parseRowKey,
  pruneRows,
  readStoredRows,
  type StoredRow,
} from "../lib/home-layout.js";

// A backup restores every title it holds, including those known by a prefixed
// id rather than an IMDb one (lib/content-id.ts), so the ids it accepts are the
// ones the rest of the API keys titles by.
const normalizeImdbId = normalizeContentId;

/**
 * Version 2 added list descriptions, each list item's place in the list's
 * manual order, tags, dropped shows, the TV settings and the Home layout.
 * Version 1 files are still read: they have none of those, and import exactly
 * as they did before.
 *
 * The profile's addons are not in either version. A manifest URL often
 * carries a debrid or account key in its path, which is why it is encrypted at
 * rest (lib/secret-box.ts), and a backup is a file that gets copied around and
 * left in download folders. Installing them again also means fetching each
 * manifest, which an import shouldn't depend on. The Home layout keeps their
 * rows, so their order comes back once they are reinstalled.
 */
const EXPORT_VERSION = 2;
const IMPORTABLE_VERSIONS: readonly unknown[] = [1, 2];

/**
 * Long enough for a file at every row cap to land, short of holding a
 * connection forever. Each statement still has `DATABASE_STATEMENT_TIMEOUT_MS`.
 */
const IMPORT_TRANSACTION_TIMEOUT_MS = 10 * 60_000;
const IMPORT_TRANSACTION_MAX_WAIT_MS = 10_000;

/** The caps PATCH /lists/:id and POST /tags put on what a person types. */
const MAX_LIST_DESCRIPTION = 280;
const MAX_TAG_NAME = 50;
/** The largest position the 32-bit column holds. */
const MAX_LIST_POSITION = 2_147_483_647;
/** A tagged title's id is free text on POST /tags/assign; this is only a sanity bound. */
const MAX_TAGGED_ID = 300;

/**
 * Credentials the TV keeps out of its settings document
 * (`credentialProfileSettingsKeys` in its ProfileSettingsSyncService). A
 * document written by an older TV could still hold one, and a backup file is
 * no place for a key, so the export drops them whatever the document says.
 */
const TV_SETTINGS_CREDENTIAL_KEYS: Record<string, readonly string[]> = {
  mdblist_settings: ["mdblist_api_key"],
  animeskip_settings: ["animeskip_client_id"],
};

interface ExportListItem {
  type: ListItemType;
  imdbId: string;
  addedAt: string;
  /** Its place in the list's manual order; null when the list was never arranged around it. */
  position?: number | null | undefined;
}

interface ExportList {
  /** The list's id here, so a collection's website-list source can be pointed at the restored list. */
  id?: string | undefined;
  name: string;
  description?: string | null | undefined;
  kind: ListKind;
  items: ExportListItem[];
}

interface ExportTag {
  name: string;
  createdAt: string;
  items: { type: ItemType; imdbId: string; createdAt: string }[];
}

interface ExportDroppedShow {
  imdbId: string;
  droppedAt: string;
}

interface ExportWatchEvent {
  type: WatchEventType;
  imdbId: string;
  seriesImdbId: string | null;
  season: number | null;
  episode: number | null;
  watchedAt: string;
  plays: number;
  note?: string | null;
}

interface ExportSeriesProgress {
  seriesImdbId: string;
  lastSeason: number;
  lastEpisode: number;
  lastWatchedAt: string;
}

interface ExportRating {
  imdbId: string;
  type: string;
  season?: number | undefined;
  episode?: number | undefined;
  rating: number;
  note?: string | null | undefined;
  ratedAt: string;
}

/**
 * A resume point. Episodes carry the series id as `imdbId`, as watch events
 * do. Added without bumping `EXPORT_VERSION`: a file from before it simply has
 * no `playback`, which imports as none, and an older build ignores the key.
 */
interface ExportPlayback {
  type: WatchEventType;
  imdbId: string;
  season: number | null;
  episode: number | null;
  progress: number;
  positionSeconds: number | null;
  durationSeconds: number | null;
  pausedAt: string;
}

/**
 * The profile's appearance, sharing and parental settings, and its collections.
 * Added without bumping `EXPORT_VERSION`, like `playback`: an older file has
 * neither and imports without them.
 */
type ExportProfile = { name: string; settings?: ProfileAttributeUpdate | undefined };

interface ExportPayload {
  version: number;
  exportedAt: string;
  profile: ExportProfile;
  collections?: unknown[] | undefined;
  lists: ExportList[];
  watchEvents: ExportWatchEvent[];
  seriesProgress: ExportSeriesProgress[];
  ratings: ExportRating[];
  playback: ExportPlayback[];
  tags?: ExportTag[] | undefined;
  droppedShows?: ExportDroppedShow[] | undefined;
  /** The TV's settings document as stored, less any credential; null when it has none. */
  tvSettings?: Record<string, unknown> | null | undefined;
  /** The Home rows someone arranged, as stored; null when nobody has. */
  homeLayout?: { rows: StoredRow[] } | null | undefined;
}

const isString = (v: unknown): v is string => typeof v === "string" && v.trim().length > 0;

const parseDate = (v: unknown): Date | null => {
  if (typeof v !== "string") return null;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d;
};

/**
 * Adds the exported collections the profile doesn't already have (by id),
 * pointing website-list sources at the lists they were restored into. A file
 * whose collections don't validate is skipped rather than failing the import:
 * collections are the least of what a backup carries.
 */
const importCollections = async (
  profileId: string,
  raw: unknown,
  restoredListIds: Map<string, string>,
  client: Prisma.TransactionClient = prisma
): Promise<number> => {
  if (!Array.isArray(raw) || raw.length === 0) return 0;
  const remapped = raw.map((collection) => remapListSources(collection, restoredListIds));
  const incoming = validateCollections(remapped);
  if (!incoming.ok) return 0;

  const current = await client.profileCollections.findUnique({ where: { profileId } });
  const existing = Array.isArray(current?.collections) ? (current.collections as unknown as CollectionJson[]) : [];
  const existingIds = new Set(existing.map((collection) => collection.id));
  const added = incoming.value.filter((collection) => !existingIds.has(collection.id));
  if (added.length === 0) return 0;

  const merged = [...existing, ...added];
  const checked = validateCollections(merged);
  if (!checked.ok) return 0;
  const collections = checked.value as unknown as Prisma.InputJsonValue;
  const updatedAt = new Date();
  await client.profileCollections.upsert({
    where: { profileId },
    create: { profileId, collections, updatedAt },
    update: { collections, updatedAt },
  });
  return added.length;
};

/** `document` without the credentials listed in `TV_SETTINGS_CREDENTIAL_KEYS`. */
const withoutTvCredentials = (document: Record<string, unknown>): Record<string, unknown> => {
  if (!isRecord(document.features)) return document;
  const features = Object.fromEntries(
    Object.entries(document.features).map(([feature, preferences]) => {
      // Own keys only: the TV may store a feature named after an Object
      // prototype member (`constructor`), which must pass through untouched.
      const credentials = Object.hasOwn(TV_SETTINGS_CREDENTIAL_KEYS, feature) ? TV_SETTINGS_CREDENTIAL_KEYS[feature] : undefined;
      if (!credentials || !isRecord(preferences)) return [feature, preferences];
      return [feature, Object.fromEntries(Object.entries(preferences).filter(([key]) => !credentials.includes(key)))];
    })
  );
  return { ...document, features };
};

const remapListSources = (collection: unknown, restoredListIds: Map<string, string>): unknown => {
  if (!isRecord(collection) || !Array.isArray(collection.folders)) return collection;
  return {
    ...collection,
    folders: collection.folders.map((folder: unknown) => {
      if (!isRecord(folder) || !Array.isArray(folder.sources)) return folder;
      return {
        ...folder,
        sources: folder.sources.map((source: unknown) => {
          if (!isRecord(source) || source.provider !== "fetch" || typeof source.listId !== "string") return source;
          const restored = restoredListIds.get(source.listId);
          return restored ? { ...source, listId: restored } : source;
        }),
      };
    }),
  };
};

const exportRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/export", async (request) => {
    const profileId = request.profileId!;
    const droppedPrefix = droppedSeriesKey(profileId, "");

    const [
      profile,
      lists,
      watchEvents,
      seriesProgress,
      ratingRows,
      playbackRows,
      collectionsRow,
      tagRows,
      droppedRows,
      documentRows,
    ] = await Promise.all([
      prisma.profile.findUnique({ where: { id: profileId }, select: PROFILE_VIEW_SELECT }),
      prisma.list.findMany({
        where: { profileId },
        include: { items: true },
        orderBy: { createdAt: "asc" },
      }),
      prisma.watchEvent.findMany({ where: { profileId }, orderBy: { watchedAt: "asc" } }),
      prisma.seriesProgress.findMany({ where: { profileId } }),
      prisma.rating.findMany({ where: { profileId } }),
      prisma.playbackProgress.findMany({ where: { profileId }, orderBy: { pausedAt: "asc" } }),
      prisma.profileCollections.findUnique({ where: { profileId } }),
      prisma.tag.findMany({ where: { profileId }, include: { items: true }, orderBy: { name: "asc" } }),
      prisma.kV.findMany({ where: { key: { startsWith: droppedPrefix } }, select: { key: true, updatedAt: true } }),
      prisma.profileDocument.findMany({
        where: { profileId, kind: { in: ["tv-settings", "home-layout"] } },
        select: { kind: true, document: true },
      }),
    ]);

    const ratings: ExportRating[] = ratingRows.map((row) => ({
      imdbId: row.imdbId,
      type: row.type,
      season: row.type === "season" || row.type === "episode" ? row.season : undefined,
      episode: row.type === "episode" ? row.episode : undefined,
      rating: row.rating,
      note: row.note,
      ratedAt: row.ratedAt.toISOString(),
    }));

    const documentOf = (kind: string) => documentRows.find((row) => row.kind === kind)?.document;
    const tvSettings = documentOf("tv-settings");
    const homeRows = readStoredRows(documentOf("home-layout"));

    const payload: ExportPayload = {
      version: EXPORT_VERSION,
      exportedAt: new Date().toISOString(),
      profile: {
        name: profile?.name ?? "Default",
        ...(profile
          ? {
              settings: {
                avatarColorHex: profile.avatarColorHex,
                avatarId: profile.avatarId,
                profileBackgroundId: profile.profileBackgroundId,
                webTheme: profile.webTheme,
                usesPrimaryAddons: profile.usesPrimaryAddons,
                usesPrimaryPlugins: profile.usesPrimaryPlugins,
                isKidsProfile: profile.isKidsProfile,
                maxRating: profile.maxRating,
                allowUnrated: profile.allowUnrated,
              },
            }
          : {}),
      },
      collections: Array.isArray(collectionsRow?.collections) ? collectionsRow.collections : [],
      lists: lists.map((list) => ({
        id: list.id,
        name: list.name,
        description: list.description ?? null,
        kind: list.kind,
        items: list.items.map((item) => ({
          type: item.type,
          imdbId: item.imdbId,
          addedAt: item.addedAt.toISOString(),
          position: item.position ?? null,
        })),
      })),
      watchEvents: watchEvents.map((event) => ({
        type: event.type,
        imdbId: event.imdbId,
        seriesImdbId: event.seriesImdbId,
        season: event.season,
        episode: event.episode,
        watchedAt: event.watchedAt.toISOString(),
        plays: event.plays,
        note: event.note,
      })),
      seriesProgress: seriesProgress.map((sp) => ({
        seriesImdbId: sp.seriesImdbId,
        lastSeason: sp.lastSeason,
        lastEpisode: sp.lastEpisode,
        lastWatchedAt: sp.lastWatchedAt.toISOString(),
      })),
      ratings,
      playback: playbackRows.map((row) => ({
        type: row.type,
        imdbId: row.imdbId,
        season: row.season,
        episode: row.episode,
        progress: row.progress,
        positionSeconds: row.positionSeconds,
        durationSeconds: row.durationSeconds,
        pausedAt: row.pausedAt.toISOString(),
      })),
      tags: tagRows.map((tag) => ({
        name: tag.name,
        createdAt: tag.createdAt.toISOString(),
        items: tag.items.map((item) => ({ type: item.type, imdbId: item.imdbId, createdAt: item.createdAt.toISOString() })),
      })),
      droppedShows: droppedRows.map((row) => ({
        imdbId: row.key.slice(droppedPrefix.length),
        droppedAt: row.updatedAt.toISOString(),
      })),
      tvSettings: isRecord(tvSettings) ? withoutTvCredentials(tvSettings) : null,
      homeLayout: homeRows.length > 0 ? { rows: homeRows } : null,
    };

    return payload;
  });

  // Clears the active profile's watch history (with the progress worked out
  // from it and its resume points) or its ratings, short of deleting the
  // profile. Lists and settings are left alone.
  app.post<{ Body: unknown }>("/data/clear", async (request, reply) => {
    const profileId = request.profileId!;
    const body = (request.body ?? {}) as { history?: unknown; ratings?: unknown };
    const history = body.history === true;
    const ratings = body.ratings === true;
    if (!history && !ratings) return reply.code(400).send({ error: "Choose history, ratings or both" });

    const cleared = await prisma.$transaction(async (tx) => {
      const counts = { watchEvents: 0, seriesProgress: 0, playbackProgress: 0, ratings: 0 };
      if (history) {
        counts.watchEvents = (await tx.watchEvent.deleteMany({ where: { profileId } })).count;
        counts.seriesProgress = (await tx.seriesProgress.deleteMany({ where: { profileId } })).count;
        counts.playbackProgress = (await tx.playbackProgress.deleteMany({ where: { profileId } })).count;
        await tx.checkIn.deleteMany({ where: { profileId } });
      }
      if (ratings) counts.ratings = (await tx.rating.deleteMany({ where: { profileId } })).count;
      return counts;
    });
    return { cleared };
  });

  app.post<{ Body: unknown }>("/import", async (request, reply) => {
    const profileId = request.profileId!;
    const body = request.body as Partial<ExportPayload> | null;

    if (!body || typeof body !== "object" || !IMPORTABLE_VERSIONS.includes(body.version)) {
      return reply.code(400).send({ error: `Expected an export payload with version 1 or ${EXPORT_VERSION}` });
    }

    // Every collection is capped independently. `MAX_BODY_SIZE_MB` bounds the
    // bytes, but minimal JSON objects pack far more rows into those bytes than
    // any real library holds, and each row is database work.
    const collections = {
      lists: Array.isArray(body.lists) ? body.lists : [],
      watchEvents: Array.isArray(body.watchEvents) ? body.watchEvents : [],
      seriesProgress: Array.isArray(body.seriesProgress) ? body.seriesProgress : [],
      ratings: Array.isArray(body.ratings) ? body.ratings : [],
      playback: Array.isArray(body.playback) ? body.playback : [],
      tags: Array.isArray(body.tags) ? (body.tags as unknown[]) : [],
      droppedShows: Array.isArray(body.droppedShows) ? (body.droppedShows as unknown[]) : [],
    };
    const collectionLabels: Record<keyof typeof collections, string> = {
      lists: "lists",
      watchEvents: "watch events",
      seriesProgress: "series progress rows",
      ratings: "ratings",
      playback: "resume points",
      tags: "tags",
      droppedShows: "dropped shows",
    };
    for (const [name, rows] of Object.entries(collections)) {
      if (rows.length > MAX_IMPORT_ROWS) {
        const label = collectionLabels[name as keyof typeof collections];
        return reply.code(413).send({ error: tooManyRowsMessage(label), code: "too_many_rows" });
      }
    }
    const nestedCount = (rows: unknown[]) =>
      rows.reduce<number>((total, row) => total + (isRecord(row) && Array.isArray(row.items) ? row.items.length : 0), 0);
    if (nestedCount(collections.lists) > MAX_IMPORT_ROWS) {
      return reply.code(413).send({ error: tooManyRowsMessage("list items"), code: "too_many_rows" });
    }
    if (nestedCount(collections.tags) > MAX_IMPORT_ROWS) {
      return reply.code(413).send({ error: tooManyRowsMessage("tagged titles"), code: "too_many_rows" });
    }

    const summary = {
      lists: 0,
      listItems: 0,
      watchEvents: 0,
      seriesProgress: 0,
      ratings: 0,
      playback: 0,
      collections: 0,
      profileSettings: false,
      // Parental fields in the file that would have lifted this profile's
      // limits, left out because the request had no authority to lift them.
      parentalLimitsSkipped: [] as string[],
      tags: 0,
      taggedItems: 0,
      droppedShows: 0,
      tvSettings: false,
      homeLayout: false,
    };

    // The whole file is read and checked before anything is written, so the
    // transaction below holds its connection only for the writes.

    type ListInput = {
      exportedId: string | null;
      name: string;
      kind: ListKind;
      description: string | null;
      items: { type: ListItemType; imdbId: string; addedAt: Date; position: number | null }[];
    };
    const listInputs: ListInput[] = [];
    for (const list of collections.lists) {
      if (!isRecord(list) || !isString(list.name) || !Object.values(ListKind).includes(list.kind)) continue;
      const items = (Array.isArray(list.items) ? list.items : []).flatMap((item: unknown) => {
        if (!isRecord(item) || !Object.values(ListItemType).includes(item.type as ListItemType)) return [];
        const imdbId = normalizeImdbId(item.imdbId);
        if (!imdbId) return [];
        return [
          {
            type: item.type as ListItemType,
            imdbId,
            addedAt: parseDate(item.addedAt) ?? new Date(),
            position: item.position == null ? null : boundedInt(item.position, 0, MAX_LIST_POSITION),
          },
        ];
      });
      listInputs.push({
        exportedId: isString(list.id) ? list.id : null,
        name: list.name.trim(),
        kind: list.kind,
        // Longer than the website lets anyone type only if the file was edited
        // by hand; it is cut to fit rather than costing the list its items.
        description: isString(list.description) ? list.description.trim().slice(0, MAX_LIST_DESCRIPTION) : null,
        items,
      });
    }

    const watchEventInputs: WatchEventInput[] = [];
    for (const event of collections.watchEvents) {
      if (!isRecord(event) || !Object.values(WatchEventType).includes(event.type)) continue;
      const imdbId = normalizeImdbId(event.imdbId);
      if (!imdbId) continue;
      const watchedAt = parseDate(event.watchedAt);
      if (!watchedAt) continue;

      // A present-but-unusable season/episode (fractional, or past what the
      // 32-bit column holds) drops the event rather than being nulled out —
      // nulling would silently change which episode the row refers to.
      const season = event.season == null ? null : boundedInt(event.season, 0, MAX_SEASON);
      const episode = event.episode == null ? null : boundedInt(event.episode, 0, MAX_EPISODE);
      if ((event.season != null && season === null) || (event.episode != null && episode === null)) continue;

      const seriesImdbId = normalizeImdbId(event.seriesImdbId);
      if (event.seriesImdbId != null && !seriesImdbId) continue;

      watchEventInputs.push({
        type: event.type,
        imdbId,
        seriesImdbId,
        season,
        episode,
        watchedAt,
        plays: boundedInt(event.plays, 1, MAX_PLAYS) ?? 1,
        note: isString(event.note) ? event.note : null,
      });
      summary.watchEvents += 1;
    }

    const progressInputs: { seriesImdbId: string; incoming: SeriesProgressCandidate }[] = [];
    for (const sp of collections.seriesProgress) {
      if (!isRecord(sp)) continue;
      const seriesImdbId = normalizeImdbId(sp.seriesImdbId);
      const lastSeason = boundedInt(sp.lastSeason, 0, MAX_SEASON);
      const lastEpisode = boundedInt(sp.lastEpisode, 0, MAX_EPISODE);
      if (!seriesImdbId || lastSeason === null || lastEpisode === null) continue;
      const lastWatchedAt = parseDate(sp.lastWatchedAt);
      if (!lastWatchedAt) continue;

      progressInputs.push({ seriesImdbId, incoming: { lastSeason, lastEpisode, lastWatchedAt } });
      summary.seriesProgress += 1;
    }

    const ratingInputs: RatingInput[] = [];
    for (const rating of collections.ratings) {
      if (!isRecord(rating)) continue;
      const imdbId = normalizeImdbId(rating.imdbId);
      const value = boundedNumber(rating.rating, MIN_RATING, MAX_RATING);
      if (
        !imdbId ||
        value === null ||
        (rating.type !== "movie" &&
          rating.type !== "series" &&
          rating.type !== "season" &&
          rating.type !== "episode")
      ) {
        continue;
      }
      const type = rating.type as MetadataType;
      // Season and episode ratings are located by a season, episode ratings
      // also by an episode; movie and series ratings pin both to 0, as the
      // column defaults do. A rating whose type needs one of those numbers and
      // hasn't got a usable one is dropped, not filed under season 0 — that is
      // Specials, a real season, and would land the rating on something else.
      // Same rule POST /ratings applies.
      const carriesSeason = type === "season" || type === "episode";
      if (carriesSeason && rating.season == null) continue;
      if (type === "episode" && rating.episode == null) continue;
      const season = carriesSeason ? boundedInt(rating.season, 0, MAX_SEASON) : 0;
      const episode = type === "episode" ? boundedInt(rating.episode, 0, MAX_EPISODE) : 0;
      if (season === null || episode === null) continue;

      ratingInputs.push({
        type,
        imdbId,
        season,
        episode,
        rating: value,
        ratedAt: parseDate(rating.ratedAt) ?? new Date(),
        note: isString(rating.note) ? rating.note : null,
      });
      summary.ratings += 1;
    }

    const playbackInputs: PlaybackImportRow[] = [];
    for (const point of collections.playback) {
      if (!isRecord(point) || !Object.values(WatchEventType).includes(point.type)) continue;
      const imdbId = normalizeImdbId(point.imdbId);
      const progress = boundedNumber(point.progress, 0, 100);
      const pausedAt = parseDate(point.pausedAt);
      if (!imdbId || progress === null || !pausedAt) continue;

      // An episode is only a resume point with both numbers — the same rule
      // PUT /playback applies — and a film ignores whatever it was handed.
      const isEpisode = point.type === WatchEventType.episode;
      const season = isEpisode ? boundedInt(point.season, 0, MAX_SEASON) : null;
      const episode = isEpisode ? boundedInt(point.episode, 0, MAX_EPISODE) : null;
      if (isEpisode && (season === null || episode === null)) continue;

      // Unusable seconds are dropped rather than the point: the percentage is
      // still a place to resume from, just a less exact one.
      let positionSeconds = point.positionSeconds == null ? null : boundedInt(point.positionSeconds, 0, MAX_PLAYBACK_SECONDS);
      let durationSeconds = point.durationSeconds == null ? null : boundedInt(point.durationSeconds, 1, MAX_PLAYBACK_SECONDS);
      if (positionSeconds !== null && durationSeconds !== null && positionSeconds > durationSeconds) {
        positionSeconds = null;
        durationSeconds = null;
      }

      playbackInputs.push({
        target: { type: point.type, imdbId, season, episode },
        progress,
        positionSeconds,
        durationSeconds,
        pausedAt,
      });
    }

    // Tags merge by name, as POST /tags/assign does: a tag the profile already
    // has gains the file's titles, and one it hasn't is created. A tagged
    // title's id is kept as written, since tagging never required a known id.
    type TagInput = { createdAt: Date; items: Map<string, { type: ItemType; imdbId: string; createdAt: Date }> };
    const tagInputs = new Map<string, TagInput>();
    for (const tag of collections.tags) {
      if (!isRecord(tag) || !isString(tag.name)) continue;
      const name = tag.name.trim();
      if (name.length > MAX_TAG_NAME) continue;
      const entry = tagInputs.get(name) ?? { createdAt: parseDate(tag.createdAt) ?? new Date(), items: new Map() };
      for (const item of Array.isArray(tag.items) ? tag.items : []) {
        if (!isRecord(item) || !Object.values(ItemType).includes(item.type as ItemType) || !isString(item.imdbId)) continue;
        const imdbId = item.imdbId.trim();
        if (imdbId.length > MAX_TAGGED_ID) continue;
        const type = item.type as ItemType;
        entry.items.set(`${type}:${imdbId}`, { type, imdbId, createdAt: parseDate(item.createdAt) ?? new Date() });
      }
      tagInputs.set(name, entry);
    }

    const droppedInputs = new Map<string, Date>();
    for (const show of collections.droppedShows) {
      if (!isRecord(show)) continue;
      const imdbId = normalizeImdbId(show.imdbId);
      if (imdbId) droppedInputs.set(imdbId, parseDate(show.droppedAt) ?? new Date());
    }

    // Checked the way PUT /profiles/:id/settings/tv checks it: the API doesn't
    // read the TV's settings, only bounds them.
    const tvSettings =
      isRecord(body.tvSettings) && opaqueDocumentProblem(body.tvSettings, MAX_TV_SETTINGS_BYTES) === null
        ? (body.tvSettings as Prisma.InputJsonValue)
        : null;
    const homeRows = readStoredRows(body.homeLayout).slice(0, MAX_HOME_ROWS);

    // Profile settings are restored as they were: an import is the profile
    // coming back, appearance and parental limit included. Lifting a limit is
    // still a grown-up's call, as on PATCH /profiles/:id and a TV's sync: a
    // kids profile restoring a file cannot use it to turn kids mode off, raise
    // its rating cap or let unrated titles in. Those fields are left out (a
    // tightening still applies) and the rest of the settings restore.
    const settings = parseProfileAttributes(isRecord(body.profile) ? body.profile.settings : undefined);
    const settingsData: ProfileAttributeUpdate = settings.ok ? settings.data : {};

    // Every write happens in one transaction, so a file that fails partway —
    // a database error, a timeout, the process going away — leaves the profile
    // as it was rather than half restored. Nothing here reaches outside the
    // database, which is what makes that possible; addons, which would need
    // their manifests fetched, are not in the file (see `EXPORT_VERSION`).
    let profileRestored = false;
    try {
      await prisma.$transaction(
        async (tx) => {
          // Exported list id → the list it was restored into, for collections
          // and the Home layout below.
          const restoredListIds = new Map<string, string>();

          for (const list of listInputs) {
            let created = false;
            const targetList =
              list.kind === ListKind.watchlist
                ? await getDefaultWatchlist(profileId, tx)
                : await (async () => {
                    const existing = await tx.list.findFirst({
                      where: { profileId, kind: ListKind.custom, name: list.name },
                    });
                    if (existing) return existing;
                    created = true;
                    summary.lists += 1;
                    return tx.list.create({
                      data: { profileId, kind: ListKind.custom, name: list.name, description: list.description },
                    });
                  })();

            if (list.exportedId) restoredListIds.set(list.exportedId, targetList.id);

            // A description fills in a list that has none; one written here
            // since the backup was taken is newer than the file's.
            if (!created && list.description && !targetList.description) {
              await tx.list.update({ where: { id: targetList.id }, data: { description: list.description } });
            }

            if (list.items.length === 0) continue;
            // The file's order is only applied to a list nobody has arranged
            // here. Positions are a single sequence per list, so mixing the
            // file's into an existing arrangement would interleave the two at
            // random; instead the file's titles join it unarranged, after the
            // arranged ones, as a title added from the website does.
            const arranged =
              !created &&
              (await tx.listItem.count({ where: { listId: targetList.id, position: { not: null } } })) > 0;
            const { count } = await tx.listItem.createMany({
              data: list.items.map((item) => ({
                listId: targetList.id,
                type: item.type,
                imdbId: item.imdbId,
                addedAt: item.addedAt,
                position: arranged ? null : item.position,
              })),
              skipDuplicates: true,
            });
            summary.listItems += count;
            // Titles the list already held were skipped above and kept no
            // position, which would put them out of the file's order (after
            // the ones it placed). An unarranged list takes the file's order
            // for those too.
            if (!created && !arranged) {
              for (const item of list.items) {
                if (item.position == null) continue;
                await tx.listItem.updateMany({
                  where: { listId: targetList.id, type: item.type, imdbId: item.imdbId, position: null },
                  data: { position: item.position },
                });
              }
            }
          }

          await batchUpsertWatchEvents(profileId, watchEventInputs, request.log, tx);
          await batchUpsertSeriesProgressIfNewer(profileId, progressInputs, tx);
          await batchUpsertRatings(profileId, ratingInputs, tx);

          // After the watch events, on purpose: a point for a title the file
          // also shows as watched since is one the import must not bring back,
          // and `importPlaybackProgress` can only see those watches once they
          // are in.
          summary.playback = await importPlaybackProgress(profileId, playbackInputs, tx);

          if (tagInputs.size > 0) {
            const names = [...tagInputs.keys()];
            const created = await tx.tag.createMany({
              data: names.map((name) => ({ profileId, name, createdAt: tagInputs.get(name)!.createdAt })),
              skipDuplicates: true,
            });
            summary.tags = created.count;
            const tags = await tx.tag.findMany({ where: { profileId, name: { in: names } }, select: { id: true, name: true } });
            const items = tags.flatMap((tag) =>
              [...(tagInputs.get(tag.name)?.items.values() ?? [])].map((item) => ({ tagId: tag.id, ...item }))
            );
            if (items.length > 0) {
              summary.taggedItems = (await tx.itemTag.createMany({ data: items, skipDuplicates: true })).count;
            }
          }

          // A show dropped in the file is dropped here too; one already
          // dropped keeps the date it was dropped on.
          if (droppedInputs.size > 0) {
            const dropped = await tx.kV.createMany({
              data: [...droppedInputs].map(([imdbId, droppedAt]) => ({
                key: droppedSeriesKey(profileId, imdbId),
                value: "true",
                updatedAt: droppedAt,
              })),
              skipDuplicates: true,
            });
            summary.droppedShows = dropped.count;
          }

          if (Object.keys(settingsData).length > 0) {
            // The check and the write are one step (lib/parental-write.ts), so
            // a grown-up tightening the limits mid-import can't be undone by it.
            const restored = await writeProfileGuarded(profileId, settingsData, {
              // Asked at the write, through the transaction's own connection, so
              // the answer is current for the limits being checked and the
              // transaction never waits on a second connection from the pool.
              checkAuthority: () => hasParentalAuthority(request, tx),
              client: tx,
            });
            if (restored.status === "ok") {
              summary.profileSettings = restored.profile !== undefined;
              summary.parentalLimitsSkipped = restored.skipped;
              profileRestored = restored.profile !== undefined;
            }
          }

          summary.collections = await importCollections(profileId, body.collections, restoredListIds, tx);

          // The TV settings and the Home layout are each one document that a
          // TV keeps in step with its own copy, so there is nothing to merge
          // into: they are restored onto a profile that has none yet, and a
          // profile that has one keeps it, as it keeps its collections. Version
          // 1 is what a first write gets, so a TV pulls them like any other.
          const updatedAt = new Date();
          if (tvSettings) {
            const written = await tx.profileDocument.createMany({
              data: [{ profileId, kind: "tv-settings", document: tvSettings, version: 1, updatedAt }],
              skipDuplicates: true,
            });
            summary.tvSettings = written.count > 0;
          }

          if (homeRows.length > 0) {
            // A list row follows its list to the id it was restored under, and
            // one whose list wasn't in the file is dropped. Addon and
            // collection rows are kept as they are: the addons aren't in the
            // file, and a row for one not installed is skipped when the layout
            // is read, until the addon is installed again and its row comes
            // back in place.
            const remapped = homeRows.map((row): StoredRow => {
              const parsed = parseRowKey(row.key);
              const restored = parsed.source === "list" ? restoredListIds.get(parsed.listId) : undefined;
              return restored ? { ...row, key: listRowKey(restored) } : row;
            });
            const rows = pruneRows(remapped, {
              sources: [],
              installedAddonIds: new Set(),
              hasUnidentifiedAddon: true,
              listIds: new Set(restoredListIds.values()),
              collectionIds: null,
            });
            if (rows.length > 0) {
              const written = await tx.profileDocument.createMany({
                data: [{ profileId, kind: "home-layout", document: { rows }, version: 1, updatedAt }],
                skipDuplicates: true,
              });
              summary.homeLayout = written.count > 0;
            }
          }
        },
        { maxWait: IMPORT_TRANSACTION_MAX_WAIT_MS, timeout: IMPORT_TRANSACTION_TIMEOUT_MS }
      );
    } catch (error) {
      request.log.error({ err: error }, "Import failed and was rolled back");
      return reply.code(500).send({
        error: "The import failed, so nothing from the file was saved. Try again in a moment.",
        code: "import_failed",
      });
    }

    // Only once the writes are committed: a cache refilled from rows that
    // were then rolled back would show a profile that never existed.
    if (profileRestored) invalidateProfileCache();

    return reply.code(200).send({ status: "imported", summary });
  });

  app.post<{ Body: unknown }>("/import/csv", async (request, reply) => {
    const profileId = request.profileId!;
    const body = request.body as { csv?: unknown } | null;
    if (!body || typeof body.csv !== "string" || !body.csv.trim()) {
      return reply.code(400).send({ error: "csv is required" });
    }

    const [headerRow, ...rest] = parseCsv(body.csv);
    if (!headerRow) {
      return reply.code(400).send({ error: "No rows found in CSV" });
    }
    const rows = [headerRow, ...rest];

    const header = headerRow.map((h) => h.trim().toLowerCase());
    const col = (name: string) => header.indexOf(name);
    const imdbIdCol = col("imdbid");
    const typeCol = col("type");
    const seasonCol = col("season");
    const episodeCol = col("episode");
    const watchedAtCol = col("watchedat");

    if (imdbIdCol === -1 || typeCol === -1 || watchedAtCol === -1) {
      return reply.code(400).send({
        error: "CSV header must include at least: imdbId,type,watchedAt (season,episode optional)",
      });
    }

    const dataRows = rows.slice(1);
    if (dataRows.length > MAX_IMPORT_ROWS) {
      return reply.code(413).send({ error: tooManyRowsMessage("CSV rows"), code: "too_many_rows" });
    }

    let imported = 0;
    let skipped = 0;
    const watchEventInputs: WatchEventInput[] = [];

    for (const row of dataRows) {
      if (row.length === 0 || row.every((cell) => cell.trim() === "")) continue;

      const imdbId = normalizeImdbId(row[imdbIdCol]);
      const rawType = row[typeCol]?.trim().toLowerCase();
      const watchedAt = parseDate(row[watchedAtCol]?.trim());

      if (!imdbId || (rawType !== "movie" && rawType !== "episode") || !watchedAt) {
        skipped += 1;
        continue;
      }

      const rawSeason = seasonCol !== -1 ? row[seasonCol]?.trim() : "";
      const rawEpisode = episodeCol !== -1 ? row[episodeCol]?.trim() : "";
      const season = rawSeason ? boundedInt(rawSeason, 0, MAX_SEASON) : null;
      const episode = rawEpisode ? boundedInt(rawEpisode, 0, MAX_EPISODE) : null;
      if ((rawSeason && season === null) || (rawEpisode && episode === null)) {
        skipped += 1;
        continue;
      }

      const type = rawType as WatchEventType;
      const seriesImdbId = type === "episode" ? imdbId : null;

      watchEventInputs.push({ type, imdbId, seriesImdbId, season, episode, watchedAt });
      imported += 1;
    }

    await batchUpsertWatchEvents(profileId, watchEventInputs, request.log);

    return reply.code(200).send({ status: "imported", summary: { imported, skipped } });
  });
};

export function parseCsv(input: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;

  for (let i = 0; i < input.length; i++) {
    const ch = input[i];

    if (inQuotes) {
      if (ch === '"') {
        if (input[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
      continue;
    }

    if (ch === '"') {
      inQuotes = true;
    } else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && input[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += ch;
    }
  }

  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }

  return rows.filter((r) => r.length > 0);
}

export default exportRoutes;
