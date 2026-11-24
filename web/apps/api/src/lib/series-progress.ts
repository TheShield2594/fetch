import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma.js";
import { mapWithConcurrency } from "./concurrency.js";
import type { SeriesProgressCandidate } from "./types.js";

const UPSERT_CONCURRENCY = 10;

export const isIncomingSeriesProgressNewer = (
  existing: SeriesProgressCandidate,
  incoming: SeriesProgressCandidate
): boolean => {
  const incomingTimestamp = incoming.lastWatchedAt.getTime();
  const existingTimestamp = existing.lastWatchedAt.getTime();

  if (incomingTimestamp !== existingTimestamp) return incomingTimestamp > existingTimestamp;
  if (incoming.lastSeason !== existing.lastSeason) return incoming.lastSeason > existing.lastSeason;
  return incoming.lastEpisode > existing.lastEpisode;
};

/**
 * Import-sized version of `upsertSeriesProgressIfNewer`: an export carries one
 * row per series, but nothing stops a caller sending thousands, and running
 * them one at a time meant that many sequential round trips inside a single
 * request. Rows for the same series collapse to the newest one first (the
 * sequential version reached the same end state, one write at a time), then
 * what's left runs with bounded concurrency.
 *
 * `client` is the transaction to write in, when the caller has one.
 */
export const batchUpsertSeriesProgressIfNewer = async (
  profileId: string,
  entries: { seriesImdbId: string; incoming: SeriesProgressCandidate }[],
  client: Prisma.TransactionClient = prisma
): Promise<number> => {
  if (entries.length === 0) return 0;

  const newest = new Map<string, SeriesProgressCandidate>();
  for (const { seriesImdbId, incoming } of entries) {
    const current = newest.get(seriesImdbId);
    if (!current || isIncomingSeriesProgressNewer(current, incoming)) {
      newest.set(seriesImdbId, incoming);
    }
  }

  await mapWithConcurrency([...newest.entries()], UPSERT_CONCURRENCY, ([seriesImdbId, incoming]) =>
    upsertSeriesProgressIfNewer(profileId, seriesImdbId, incoming, client)
  );

  return newest.size;
};

export const upsertSeriesProgressIfNewer = async (
  profileId: string,
  seriesImdbId: string,
  incoming: SeriesProgressCandidate,
  client: Prisma.TransactionClient = prisma
) => {
  const { count } = await client.seriesProgress.updateMany({
    where: {
      profileId,
      seriesImdbId,
      OR: [
        { lastWatchedAt: { lt: incoming.lastWatchedAt } },
        {
          lastWatchedAt: incoming.lastWatchedAt,
          lastSeason: { lt: incoming.lastSeason },
        },
        {
          lastWatchedAt: incoming.lastWatchedAt,
          lastSeason: incoming.lastSeason,
          lastEpisode: { lt: incoming.lastEpisode },
        },
      ],
    },
    data: {
      lastSeason: incoming.lastSeason,
      lastEpisode: incoming.lastEpisode,
      lastWatchedAt: incoming.lastWatchedAt,
      updatedAt: incoming.lastWatchedAt,
    },
  });

  if (count > 0) return;

  // No row was old enough to move: either there is none yet, or the stored one
  // is already as far along. Insert-or-nothing covers both, and a concurrent
  // writer creating the row first, without an error. That matters inside a
  // transaction (a backup import), where Postgres aborts the whole transaction
  // on a unique violation even if the code catches it.
  await client.seriesProgress.createMany({
    data: [
      {
        profileId,
        seriesImdbId,
        lastSeason: incoming.lastSeason,
        lastEpisode: incoming.lastEpisode,
        lastWatchedAt: incoming.lastWatchedAt,
        updatedAt: incoming.lastWatchedAt,
      },
    ],
    skipDuplicates: true,
  });
};
