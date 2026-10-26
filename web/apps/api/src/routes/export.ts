import { ListItemType, ListKind, MetadataType, Prisma, WatchEventType } from "@prisma/client";
import type { FastifyPluginAsync } from "fastify";
import { prisma } from "../lib/prisma.js";
import { invalidateProfileCache, resolveProfile } from "../lib/profile.js";
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
  normalizeImdbId,
  tooManyRowsMessage,
} from "../lib/import-validation.js";

const EXPORT_VERSION = 1;

interface ExportListItem {
  type: ListItemType;
  imdbId: string;
  addedAt: string;
}

interface ExportList {
  /** The list's id here, so a collection's website-list source can be pointed at the restored list. */
  id?: string | undefined;
  name: string;
  kind: ListKind;
  items: ExportListItem[];
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
  restoredListIds: Map<string, string>
): Promise<number> => {
  if (!Array.isArray(raw) || raw.length === 0) return 0;
  const remapped = raw.map((collection) => remapListSources(collection, restoredListIds));
  const incoming = validateCollections(remapped);
  if (!incoming.ok) return 0;

  const current = await prisma.profileCollections.findUnique({ where: { profileId } });
  const existing = Array.isArray(current?.collections) ? (current.collections as unknown as CollectionJson[]) : [];
  const existingIds = new Set(existing.map((collection) => collection.id));
  const added = incoming.value.filter((collection) => !existingIds.has(collection.id));
  if (added.length === 0) return 0;

  const merged = [...existing, ...added];
  const checked = validateCollections(merged);
  if (!checked.ok) return 0;
  const collections = checked.value as unknown as Prisma.InputJsonValue;
  const updatedAt = new Date();
  await prisma.profileCollections.upsert({
    where: { profileId },
    create: { profileId, collections, updatedAt },
    update: { collections, updatedAt },
  });
  return added.length;
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

    const [profile, lists, watchEvents, seriesProgress, ratingRows, playbackRows, collectionsRow] = await Promise.all([
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
        kind: list.kind,
        items: (list.items as { type: ListItemType; imdbId: string; addedAt: Date }[]).map((item) => ({
          type: item.type,
          imdbId: item.imdbId,
          addedAt: item.addedAt.toISOString(),
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
    };

    return payload;
  });

  app.post<{ Body: unknown }>("/import", async (request, reply) => {
    const profileId = request.profileId!;
    const body = request.body as Partial<ExportPayload> | null;

    if (!body || typeof body !== "object" || body.version !== EXPORT_VERSION) {
      return reply.code(400).send({ error: `Expected an export payload with version ${EXPORT_VERSION}` });
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
    };
    const collectionLabels: Record<keyof typeof collections, string> = {
      lists: "lists",
      watchEvents: "watch events",
      seriesProgress: "series progress rows",
      ratings: "ratings",
      playback: "resume points",
    };
    for (const [name, rows] of Object.entries(collections)) {
      if (rows.length > MAX_IMPORT_ROWS) {
        const label = collectionLabels[name as keyof typeof collections];
        return reply.code(413).send({ error: tooManyRowsMessage(label), code: "too_many_rows" });
      }
    }
    const listItemCount = collections.lists.reduce(
      (total, list) => total + (isRecord(list) && Array.isArray(list.items) ? list.items.length : 0),
      0
    );
    if (listItemCount > MAX_IMPORT_ROWS) {
      return reply.code(413).send({ error: tooManyRowsMessage("list items"), code: "too_many_rows" });
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
    };
    // Exported list id → the list it was restored into, for collections below.
    const restoredListIds = new Map<string, string>();

    for (const list of collections.lists) {
      if (!isRecord(list) || !isString(list.name) || !Object.values(ListKind).includes(list.kind)) continue;

      const targetList =
        list.kind === ListKind.watchlist
          ? await getDefaultWatchlist(profileId)
          : await (async () => {
              const existing = await prisma.list.findFirst({
                where: { profileId, kind: ListKind.custom, name: list.name.trim() },
              });
              if (existing) return existing;
              summary.lists += 1;
              return prisma.list.create({
                data: { profileId, kind: ListKind.custom, name: list.name.trim() },
              });
            })();

      if (isString(list.id)) restoredListIds.set(list.id, targetList.id);

      const validItems = (Array.isArray(list.items) ? list.items : []).flatMap((item) => {
        if (!isRecord(item) || !Object.values(ListItemType).includes(item.type)) return [];
        const imdbId = normalizeImdbId(item.imdbId);
        return imdbId ? [{ type: item.type, imdbId, addedAt: item.addedAt }] : [];
      });
      if (validItems.length > 0) {
        const { count } = await prisma.listItem.createMany({
          data: validItems.map((item) => ({
            listId: targetList.id,
            type: item.type,
            imdbId: item.imdbId,
            addedAt: parseDate(item.addedAt) ?? new Date(),
          })),
          skipDuplicates: true,
        });
        summary.listItems += count;
      }
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
    await batchUpsertWatchEvents(profileId, watchEventInputs, request.log);

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
    await batchUpsertSeriesProgressIfNewer(profileId, progressInputs);

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
    await batchUpsertRatings(profileId, ratingInputs);

    // After the watch events, on purpose: a point for a title the file also
    // shows as watched since is one the import must not bring back, and
    // `importPlaybackProgress` can only see those watches once they are in.
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
    summary.playback = await importPlaybackProgress(profileId, playbackInputs);

    // Profile settings are restored as they were: an import is the profile
    // coming back, appearance and parental limit included.
    const settings = parseProfileAttributes(isRecord(body.profile) ? body.profile.settings : undefined);
    if (settings.ok && Object.keys(settings.data).length > 0) {
      await prisma.profile.update({ where: { id: profileId }, data: settings.data });
      invalidateProfileCache();
      summary.profileSettings = true;
    }

    summary.collections = await importCollections(profileId, body.collections, restoredListIds);

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
