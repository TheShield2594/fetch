import { WatchEventType } from "@prisma/client";
import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";
import { mapWithConcurrency } from "./concurrency.js";
import { watchEventDayWindow } from "./watch-event-dedup.js";

const UPDATE_CONCURRENCY = 10;

export type WatchEventInput = {
  type: WatchEventType;
  /** For episodes, pass the series' imdbId here (matches recordWatchEvent's create shape). */
  imdbId: string;
  seriesImdbId: string | null;
  season: number | null;
  episode: number | null;
  watchedAt: Date;
  note?: string | null;
  /** Weight to add to `plays` on create/merge. Defaults to 1 (one row = one watch). */
  plays?: number;
};

const dedupKey =(type: WatchEventType, matchId: string, season: number | null, episode: number | null) =>
  `${type}:${matchId}:${season ?? "null"}:${episode ?? "null"}`;

/**
 * Bulk dedup-then-create for CSV/export imports that can carry thousands of rows.
 * Instead of one findFirst + one create/update round trip per row, this pre-fetches
 * every WatchEvent the batch could possibly match in a single query, resolves the
 * batch in memory, then flushes with one createMany and one batch of concurrent
 * updates.
 *
 * Rows in the batch that land on the same title and UTC day merge into one row whose
 * `plays` is their sum: a diary with two entries on one day is two plays.
 *
 * A row that matches one the profile already has is the same watch arriving again —
 * the same file imported twice, or Letterboxd and a Plex webhook both recording one
 * evening — so it does not add to that row's `plays`. The stored row keeps the larger
 * of its own count and the batch's. That is what makes an import safe to repeat:
 * a second pass over the same file changes nothing.
 */
export async function batchUpsertWatchEvents(
  profileId: string,
  inputs: WatchEventInput[],
  log?: Pick<FastifyBaseLogger, "warn">
): Promise<number> {
  if (inputs.length === 0) return 0;

  const movieIds = new Set<string>();
  const seriesIds = new Set<string>();
  for (const input of inputs) {
    if (input.type === WatchEventType.movie) movieIds.add(input.imdbId);
    else seriesIds.add(input.seriesImdbId ?? input.imdbId);
  }

  const existing = await prisma.watchEvent.findMany({
    where: {
      profileId,
      OR: [
        ...(movieIds.size > 0 ? [{ type: WatchEventType.movie, imdbId: { in: [...movieIds] } }] : []),
        ...(seriesIds.size > 0 ? [{ type: WatchEventType.episode, seriesImdbId: { in: [...seriesIds] } }] : []),
      ],
    },
    select: {
      id: true,
      type: true,
      imdbId: true,
      seriesImdbId: true,
      season: true,
      episode: true,
      watchedAt: true,
      plays: true,
    },
  });
  const storedPlays = new Map(existing.map((row) => [row.id, row.plays]));

  type Candidate = { id: string; watchedAt: Date };
  const bucket = new Map<string, Candidate[]>();
  for (const row of existing) {
    const matchId = row.type === WatchEventType.episode ? (row.seriesImdbId ?? row.imdbId) : row.imdbId;
    const key = dedupKey(row.type, matchId, row.season, row.episode);
    const list = bucket.get(key);
    if (list) list.push({ id: row.id, watchedAt: row.watchedAt });
    else bucket.set(key, [{ id: row.id, watchedAt: row.watchedAt }]);
  }

  const creates: {
    type: WatchEventType;
    imdbId: string;
    seriesImdbId: string | null;
    season: number | null;
    episode: number | null;
    watchedAt: Date;
    plays: number;
    note: string | null;
    profileId: string;
  }[] = [];
  // Per stored row the batch matched: the plays the batch carries for that title
  // and day, and the plays every stored row of that title and day already holds.
  // More than one stored row per day happens where Trakt history rows (outside
  // the dedup index) sit beside each other or beside another writer's.
  const updates = new Map<string, { batch: number; storedDay: number }>();
  let imported = 0;

  for (const input of inputs) {
    const weight = input.plays ?? 1;
    const matchId = input.type === WatchEventType.episode ? (input.seriesImdbId ?? input.imdbId) : input.imdbId;
    const key = dedupKey(input.type, matchId, input.season, input.episode);
    const { gte: start, lte: end } = watchEventDayWindow(input.watchedAt);

    const candidates = bucket.get(key) ?? [];
    const match = candidates.find((c) => c.watchedAt >= start && c.watchedAt <= end);

    const pending = match?.id.startsWith("new:") ? creates[Number(match.id.slice(4))] : undefined;
    if (pending) {
      pending.plays += weight;
    } else if (match) {
      // Summed across the batch, then compared with what is stored, not added to it.
      const current = updates.get(match.id);
      if (current) {
        current.batch += weight;
      } else {
        const storedDay = candidates
          .filter((c) => !c.id.startsWith("new:") && c.watchedAt >= start && c.watchedAt <= end)
          .reduce((total, c) => total + (storedPlays.get(c.id) ?? 0), 0);
        updates.set(match.id, { batch: weight, storedDay });
      }
    } else {
      const newId = `new:${creates.length}`;
      creates.push({
        type: input.type,
        imdbId: input.type === WatchEventType.episode ? matchId : input.imdbId,
        seriesImdbId: input.type === WatchEventType.episode ? matchId : null,
        season: input.season,
        episode: input.episode,
        watchedAt: input.watchedAt,
        plays: weight,
        note: input.note ?? null,
        profileId,
      });
      candidates.push({ id: newId, watchedAt: input.watchedAt });
      bucket.set(key, candidates);
    }
    imported += 1;
  }

  if (creates.length > 0) {
    // `skipDuplicates` because the read at the top of this function and the insert
    // here are not one operation: a webhook or a scrobble can write into one of
    // these buckets in between, and `watchevent_dedup_key` would then reject the
    // whole statement. An import of several thousand rows failing on one collision
    // — and reporting an "imported" count for rows that were rolled back — is a far
    // worse outcome than the one this trades for, which is that the colliding row's
    // play weight folds into the row that won instead of being added to it.
    const created = await prisma.watchEvent.createMany({ data: creates, skipDuplicates: true });
    if (created.count < creates.length) {
      log?.warn(
        { requested: creates.length, created: created.count },
        "Some watch events were already recorded by a concurrent writer and were skipped"
      );
    }
  }
  // Only a day the batch carries more plays for than is already stored needs a
  // write, and only the difference is added. The `plays` guard keeps a concurrent
  // increment from being counted twice.
  const raises = [...updates.entries()].filter(([, { batch, storedDay }]) => batch > storedDay);
  if (raises.length > 0) {
    await mapWithConcurrency(raises, UPDATE_CONCURRENCY, ([id, { batch, storedDay }]) => {
      const plays = storedPlays.get(id) ?? 0;
      return prisma.watchEvent.updateMany({ where: { id, plays }, data: { plays: plays + batch - storedDay } });
    });
  }

  return imported;
}
