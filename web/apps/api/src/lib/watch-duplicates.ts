import { Prisma, WatchEventType } from "@prisma/client";
import { prisma } from "./prisma.js";

// Finding and merging watches that are the same watch recorded more than once.
//
// `watchevent_dedup_key` already stops two rows for one title on one UTC day, so
// what is left for this to find is what that index cannot see:
//
//   * the same evening on two UTC days — a Letterboxd diary dates a film to
//     midnight UTC while a Plex webhook stamps the real time, and an evening in
//     the Americas is already tomorrow in UTC;
//   * a watch marked "date unknown" next to the dated play it stood for, once a
//     tracker import brought the real date in;
//   * a Trakt history row (outside the index, since it carries its own id) next
//     to the row another writer recorded for the same play.
//
// None of that can be decided by a rule the way a same-day collision can — a
// film watched two nights running is two plays — so this only suggests. The
// person picks what to merge.

/**
 * How far apart two plays of one title can be and still be offered as one watch.
 * A date-only entry (stored at midnight UTC) and the real timestamp of the same
 * local evening are at most about 34 hours apart, at UTC-12 or UTC+14.
 */
export const DUPLICATE_WINDOW_HOURS = 36;
const DUPLICATE_WINDOW_MS = DUPLICATE_WINDOW_HOURS * 60 * 60 * 1000;

/** The most groups one scan returns; a person reviews these, and can scan again. */
export const MAX_DUPLICATE_GROUPS = 500;

/**
 * The most rows one group may hold, in a scan and in a merge. A title with more
 * copies than this is offered this many at a time; the rest come up again on the
 * next scan, beside the row the first merge kept.
 */
export const MAX_DUPLICATE_GROUP_SIZE = 50;

export type DuplicateCandidate = {
  id: string;
  type: WatchEventType;
  imdbId: string;
  seriesImdbId: string | null;
  season: number | null;
  episode: number | null;
  watchedAt: Date;
  dateUnknown: boolean;
  plays: number;
  note: string | null;
  traktHistoryId: bigint | null;
};

/** The title (and episode) a row is a watch of, the identity the dedup index keys on. */
export const titleKey = (row: Pick<DuplicateCandidate, "type" | "imdbId" | "seriesImdbId" | "season" | "episode">) =>
  row.type === WatchEventType.episode
    ? `episode:${row.seriesImdbId ?? row.imdbId}:${row.season ?? -1}:${row.episode ?? -1}`
    : `movie:${row.imdbId}`;

/**
 * Which row a merge keeps: one with a real date over one marked unknown (a date
 * is information the group would otherwise lose), then one carrying a Trakt id
 * (so the next Trakt poll finds it rather than importing the play again), then
 * the earliest.
 */
export const pickSurvivor = <T extends DuplicateCandidate>(rows: T[]): T => {
  const sorted = [...rows].sort(
    (a, b) =>
      Number(a.dateUnknown) - Number(b.dateUnknown) ||
      Number(a.traktHistoryId == null) - Number(b.traktHistoryId == null) ||
      a.watchedAt.getTime() - b.watchedAt.getTime() ||
      a.id.localeCompare(b.id)
  );
  return sorted[0]!;
};

const traktIds = (rows: DuplicateCandidate[]) =>
  new Set(rows.flatMap((row) => (row.traktHistoryId != null ? [row.traktHistoryId.toString()] : [])));

/** Whether `row` can join `cluster` without the group holding two different Trakt plays. */
const fitsTrakt = (cluster: DuplicateCandidate[], row: DuplicateCandidate): boolean => {
  if (row.traktHistoryId == null) return true;
  const ids = traktIds(cluster);
  return ids.size === 0 || ids.has(row.traktHistoryId.toString());
};

/**
 * The groups of rows that look like one watch, each of two or more rows of one
 * title. Pure, so the rule can be tested without a database.
 *
 * Dated rows are taken in time order, and a row joins the current group when it
 * is within the window of the group's first row — measured from the first, not
 * the previous, so a show watched every evening for a week does not chain into
 * one "duplicate". Two rows with different Trakt ids are never grouped: each is
 * a play Trakt itself holds, and merging one away would only see it re-imported.
 * Rows marked "date unknown" join the title's first group, or form one of their
 * own when the title has nothing dated.
 */
export const findDuplicateGroups = <T extends DuplicateCandidate>(rows: T[]): T[][] => {
  const byTitle = new Map<string, T[]>();
  for (const row of rows) {
    const key = titleKey(row);
    const list = byTitle.get(key);
    if (list) list.push(row);
    else byTitle.set(key, [row]);
  }

  const groups: T[][] = [];
  for (const titleRows of byTitle.values()) {
    if (titleRows.length < 2) continue;
    const dated = titleRows.filter((row) => !row.dateUnknown).sort((a, b) => a.watchedAt.getTime() - b.watchedAt.getTime());
    const undated = titleRows.filter((row) => row.dateUnknown);

    const clusters: T[][] = [];
    let current: T[] = [];
    for (const row of dated) {
      const first = current[0];
      if (first && row.watchedAt.getTime() - first.watchedAt.getTime() <= DUPLICATE_WINDOW_MS && fitsTrakt(current, row)) {
        current.push(row);
      } else {
        if (current.length > 0) clusters.push(current);
        current = [row];
      }
    }
    if (current.length > 0) clusters.push(current);

    // Undated rows join the title's first group where their Trakt ids allow;
    // the rest group among themselves under the same rule.
    const leftover: T[] = [];
    for (const row of undated) {
      const target = clusters[0];
      if (target && fitsTrakt(target, row)) target.push(row);
      else leftover.push(row);
    }
    for (const row of leftover) {
      const home = clusters.find((cluster) => cluster.every((member) => member.dateUnknown) && fitsTrakt(cluster, row));
      if (home) home.push(row);
      else clusters.push([row]);
    }

    for (const cluster of clusters) {
      if (cluster.length < 2) continue;
      // The survivor first, so a capped group still holds the row a merge keeps.
      const survivor = pickSurvivor(cluster);
      groups.push([survivor, ...cluster.filter((row) => row !== survivor)].slice(0, MAX_DUPLICATE_GROUP_SIZE));
    }
  }

  // Most recent first: what someone has just imported is what they came to fix.
  return groups.sort((a, b) => pickSurvivor(b).watchedAt.getTime() - pickSurvivor(a).watchedAt.getTime());
};

const CANDIDATE_SELECT = {
  id: true,
  type: true,
  imdbId: true,
  seriesImdbId: true,
  season: true,
  episode: true,
  watchedAt: true,
  dateUnknown: true,
  plays: true,
  note: true,
  traktHistoryId: true,
} as const;

export type DuplicateGroupView = {
  type: WatchEventType;
  imdbId: string;
  season: number | null;
  episode: number | null;
  title: string | null;
  keepId: string;
  events: {
    id: string;
    watchedAt: string;
    dateUnknown: boolean;
    plays: number;
    note: string | null;
    fromTrakt: boolean;
  }[];
};

/** The profile's likely duplicates, ready to show: titles attached, the row a merge would keep named. */
export const listDuplicateGroups = async (profileId: string): Promise<{ groups: DuplicateGroupView[]; truncated: boolean }> => {
  const rows = await prisma.watchEvent.findMany({ where: { profileId }, select: CANDIDATE_SELECT });
  const all = findDuplicateGroups(rows);
  const groups = all.slice(0, MAX_DUPLICATE_GROUPS);
  const titleCounts = new Map<string, number>();
  for (const row of rows) titleCounts.set(titleKey(row), (titleCounts.get(titleKey(row)) ?? 0) + 1);

  const titleIds = (type: WatchEventType) => [
    ...new Set(
      groups.filter((group) => group[0]!.type === type).map((group) => group[0]!.seriesImdbId ?? group[0]!.imdbId)
    ),
  ];
  const items = await prisma.item.findMany({
    where: {
      OR: [
        { type: "movie", imdbId: { in: titleIds(WatchEventType.movie) } },
        { type: "series", imdbId: { in: titleIds(WatchEventType.episode) } },
      ],
    },
    select: { type: true, imdbId: true, title: true },
  });
  const titles = new Map(items.map((item) => [`${item.type}:${item.imdbId}`, item.title]));

  return {
    // More to find on the next scan: groups past the cap, or a group capped at its size.
    truncated:
      all.length > groups.length ||
      groups.some((group) => group.length === MAX_DUPLICATE_GROUP_SIZE && (titleCounts.get(titleKey(group[0]!)) ?? 0) > group.length),
    groups: groups.map((group) => {
      const first = group[0]!;
      const imdbId = first.type === WatchEventType.episode ? (first.seriesImdbId ?? first.imdbId) : first.imdbId;
      return {
        type: first.type,
        imdbId,
        season: first.season,
        episode: first.episode,
        title: titles.get(`${first.type === WatchEventType.episode ? "series" : "movie"}:${imdbId}`) ?? null,
        keepId: pickSurvivor(group).id,
        events: [...group]
          .sort((a, b) => Number(a.dateUnknown) - Number(b.dateUnknown) || a.watchedAt.getTime() - b.watchedAt.getTime())
          .map((row) => ({
            id: row.id,
            watchedAt: row.watchedAt.toISOString(),
            dateUnknown: row.dateUnknown,
            plays: row.plays,
            note: row.note,
            fromTrakt: row.traktHistoryId != null,
          })),
      };
    }),
  };
};

/** Why a requested merge was refused, for the route to report. */
export class DuplicateMergeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DuplicateMergeError";
  }
}

/**
 * Points a series' progress at its latest remaining episode watch, or drops it
 * when none is left. The same rule `DELETE /watch/:eventId` applies.
 */
export const recomputeSeriesProgress = async (
  tx: Prisma.TransactionClient,
  profileId: string,
  seriesImdbId: string
): Promise<void> => {
  const latest = await tx.watchEvent.findFirst({
    where: { profileId, seriesImdbId, type: "episode", season: { not: null }, episode: { not: null } },
    orderBy: { watchedAt: "desc" },
  });
  if (latest && latest.season != null && latest.episode != null) {
    const data = {
      lastSeason: latest.season,
      lastEpisode: latest.episode,
      lastWatchedAt: latest.watchedAt,
      updatedAt: new Date(),
    };
    await tx.seriesProgress.upsert({
      where: { profileId_seriesImdbId: { profileId, seriesImdbId } },
      update: data,
      create: { profileId, seriesImdbId, ...data },
    });
  } else {
    await tx.seriesProgress.deleteMany({ where: { profileId, seriesImdbId } });
  }
};

/**
 * Folds each group of rows into one, in one transaction.
 *
 * The survivor is `pickSurvivor`'s. Its `plays` becomes the largest in the group,
 * not the sum: these are copies of one watch, and adding them would count the
 * copies as rewatches — the thing being undone. It keeps its own note, or takes
 * the first one in the group. A Trakt id on a row being removed moves to the
 * survivor, so Trakt's next poll recognises the play instead of importing it again.
 *
 * Every id must belong to the profile and every group must be one title with at
 * most one Trakt id, or nothing is merged.
 */
export const mergeDuplicateGroups = async (
  profileId: string,
  groups: string[][]
): Promise<{ merged: number; removed: number }> => {
  const ids = groups.flat();
  if (new Set(ids).size !== ids.length) throw new DuplicateMergeError("An event appears in more than one group");

  return prisma.$transaction(async (tx) => {
    // Locked in id order before they are read, so a play recorded on one of them
    // meanwhile waits for the merge and lands on the row kept, instead of being
    // overwritten by a count read before it; the order keeps two merges from
    // deadlocking on each other.
    await tx.$executeRaw`SELECT id FROM "WatchEvent" WHERE "profileId" = ${profileId}::uuid AND id = ANY(${ids}::uuid[]) ORDER BY id FOR UPDATE`;
    const rows = await tx.watchEvent.findMany({ where: { profileId, id: { in: ids } }, select: CANDIDATE_SELECT });
    if (rows.length !== ids.length) throw new DuplicateMergeError("Some of those watch events were not found");
    const byId = new Map(rows.map((row) => [row.id, row]));

    let merged = 0;
    let removed = 0;
    const series = new Set<string>();

    for (const group of groups) {
      const members = group.map((id) => byId.get(id)!);
      if (members.length < 2) continue;
      if (new Set(members.map(titleKey)).size !== 1) {
        throw new DuplicateMergeError("Each group must be watches of one title");
      }
      if (traktIds(members).size > 1) {
        throw new DuplicateMergeError("Two Trakt plays can't be merged; remove one on Trakt instead");
      }

      const keep = pickSurvivor(members);
      const others = members.filter((row) => row.id !== keep.id);
      const plays = Math.max(...members.map((row) => row.plays));
      const note =
        keep.note ??
        [...members].sort((a, b) => a.watchedAt.getTime() - b.watchedAt.getTime()).find((row) => row.note)?.note ??
        null;
      const traktHistoryId = keep.traktHistoryId ?? others.find((row) => row.traktHistoryId != null)?.traktHistoryId ?? null;

      // Removed first: the Trakt id is unique, so it can only move once its row is gone.
      await tx.watchEvent.deleteMany({ where: { profileId, id: { in: others.map((row) => row.id) } } });
      await tx.watchEvent.update({ where: { id: keep.id }, data: { plays, note, traktHistoryId } });

      merged += 1;
      removed += others.length;
      if (keep.type === WatchEventType.episode) series.add(keep.seriesImdbId ?? keep.imdbId);
    }

    for (const seriesImdbId of series) await recomputeSeriesProgress(tx, profileId, seriesImdbId);
    return { merged, removed };
  });
};
