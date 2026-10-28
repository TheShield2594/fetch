import { ItemType, ListItemType, ListKind, Prisma, type ListLink } from "@prisma/client";
import type { FastifyBaseLogger } from "fastify";
import type { TraktWatchlistMutationPayload } from "../trakt.js";
import { prisma } from "./prisma.js";
import { getTraktClient, isTraktConnected } from "./trakt-client.js";
import {
  changeMdbListListItems,
  createMdbListList,
  fetchMdbListListItems,
  fetchMdbListUserLists,
  getMdbListStatus,
  renameMdbListList,
} from "./mdblist.js";

/**
 * A custom Fetch list kept in step with one of the same profile's own lists on
 * Trakt or MDBList, as the TV manages them (TraktLibraryService,
 * MdbListLibraryWriter). Adds and removes made in Fetch are pushed straight
 * out; changes made on the service come in on the scheduled sync, which also
 * retries any push that failed.
 *
 * Deleting is where this is careful, in the spirit of
 * TRAKT_WATCHLIST_MIRROR_DELETES:
 * - deleting the Fetch list drops the link and leaves the service's list alone;
 * - a list deleted on the service leaves the Fetch list and its items alone and
 *   marks the link broken, for the person to unlink or relink;
 * - a side that has gone entirely empty since the last sync is treated as a
 *   fault, not as "remove everything", and nothing is removed from the other.
 * Removing a single item on either side does carry over: that is what linking
 * a list asks for.
 */

export const LIST_LINK_PROVIDERS = ["trakt", "mdblist"] as const;
export type ListLinkProvider = (typeof LIST_LINK_PROVIDERS)[number];

export const isListLinkProvider = (value: unknown): value is ListLinkProvider =>
  typeof value === "string" && (LIST_LINK_PROVIDERS as readonly string[]).includes(value);

export type ListItemRef = { type: "movie" | "series"; imdbId: string };
export type RemoteList = { id: string; name: string; itemCount: number | null };

const PROVIDER_NAMES: Record<ListLinkProvider, string> = { trakt: "Trakt", mdblist: "MDBList" };

/** Items per add/remove request, so a large first merge isn't one huge body. */
const PUSH_BATCH_SIZE = 100;

export class ListLinkError extends Error {
  constructor(
    message: string,
    readonly status: 400 | 404 | 409 | 502 = 400
  ) {
    super(message);
  }
}

// ─── The two services ───

type ProviderAdapter = {
  connected: (profileId: string) => Promise<boolean>;
  lists: (profileId: string, logger: FastifyBaseLogger) => Promise<RemoteList[]>;
  items: (profileId: string, remoteId: string, logger: FastifyBaseLogger) => Promise<ListItemRef[]>;
  change: (
    profileId: string,
    remoteId: string,
    action: "add" | "remove",
    items: ListItemRef[],
    logger: FastifyBaseLogger
  ) => Promise<void>;
  create: (profileId: string, name: string, logger: FastifyBaseLogger) => Promise<RemoteList>;
  rename: (profileId: string, remoteId: string, name: string, logger: FastifyBaseLogger) => Promise<void>;
};

const traktListId = (remoteId: string): number => {
  const id = Number(remoteId);
  if (!Number.isInteger(id) || id <= 0) throw new ListLinkError("Not a Trakt list id");
  return id;
};

const traktPayload = (items: ListItemRef[]): TraktWatchlistMutationPayload => {
  const movies = items.filter((item) => item.type === "movie").map((item) => ({ ids: { imdb: item.imdbId } }));
  const shows = items.filter((item) => item.type === "series").map((item) => ({ ids: { imdb: item.imdbId } }));
  return { ...(movies.length ? { movies } : {}), ...(shows.length ? { shows } : {}) };
};

const adapters: Record<ListLinkProvider, ProviderAdapter> = {
  trakt: {
    connected: (profileId) => isTraktConnected(profileId),
    lists: async (profileId, logger) => {
      const lists = await (await getTraktClient(profileId)).fetchPersonalLists(logger);
      return lists.flatMap((list) => {
        const id = list.ids?.trakt;
        const name = list.name?.trim();
        return id !== undefined && name ? [{ id: String(id), name, itemCount: list.item_count ?? null }] : [];
      });
    },
    items: async (profileId, remoteId, logger) => {
      const items = await (await getTraktClient(profileId)).fetchPersonalListItems(traktListId(remoteId), logger, {
        requireComplete: true,
      });
      return items.flatMap((item): ListItemRef[] => {
        if (item.movie?.ids?.imdb) return [{ type: "movie", imdbId: item.movie.ids.imdb }];
        if (item.show?.ids?.imdb) return [{ type: "series", imdbId: item.show.ids.imdb }];
        return [];
      });
    },
    change: async (profileId, remoteId, action, items, logger) => {
      const client = await getTraktClient(profileId);
      const payload = traktPayload(items);
      if (action === "add") await client.addPersonalListItems(traktListId(remoteId), payload, logger);
      else await client.removePersonalListItems(traktListId(remoteId), payload, logger);
    },
    create: async (profileId, name, logger) => {
      const created = await (await getTraktClient(profileId)).createPersonalList(name, logger);
      const id = created.ids?.trakt;
      if (id === undefined) throw new ListLinkError("Trakt didn't return the new list", 502);
      return { id: String(id), name: created.name?.trim() || name, itemCount: 0 };
    },
    rename: async (profileId, remoteId, name, logger) => {
      await (await getTraktClient(profileId)).renamePersonalList(traktListId(remoteId), name, logger);
    },
  },
  mdblist: {
    connected: async (profileId) => (await getMdbListStatus(profileId)).connected,
    lists: async (profileId) =>
      (await fetchMdbListUserLists(profileId)).map(({ id, name, itemCount }) => ({ id, name, itemCount })),
    items: (profileId, remoteId) => fetchMdbListListItems(profileId, remoteId),
    change: (profileId, remoteId, action, items) => changeMdbListListItems(profileId, remoteId, action, items),
    create: async (profileId, name) => {
      const { id, name: created, itemCount } = await createMdbListList(profileId, name);
      return { id, name: created, itemCount };
    },
    rename: (profileId, remoteId, name) => renameMdbListList(profileId, remoteId, name),
  },
};

// ─── Deciding what to do ───

const keyOf = (item: ListItemRef) => `${item.type}:${item.imdbId}`;

const refOf = (key: string): ListItemRef => {
  const separator = key.indexOf(":");
  return { type: key.slice(0, separator) as ListItemRef["type"], imdbId: key.slice(separator + 1) };
};

export type ListLinkPlan = {
  addLocal: string[];
  removeLocal: string[];
  pushAdd: string[];
  pushRemove: string[];
  /** Removals held back because the other side went entirely empty. */
  kept: string[];
  remoteWentEmpty: boolean;
  localWentEmpty: boolean;
};

/**
 * What one sync does, from the two sides and the keys both agreed on last
 * time. Exported for tests.
 *
 * With no snapshot (the first sync after linking) nothing is removed: both
 * sides are merged. After that, a key on one side only is an add there if the
 * snapshot doesn't have it, and a removal on the other side if it does.
 */
export const planListLinkSync = (
  remote: Set<string>,
  local: Set<string>,
  snapshot: Set<string> | null
): ListLinkPlan => {
  const plan: ListLinkPlan = {
    addLocal: [],
    removeLocal: [],
    pushAdd: [],
    pushRemove: [],
    kept: [],
    remoteWentEmpty: !!snapshot && snapshot.size > 0 && remote.size === 0,
    localWentEmpty: !!snapshot && snapshot.size > 0 && local.size === 0,
  };

  for (const key of remote) {
    if (local.has(key)) continue;
    if (!snapshot?.has(key)) plan.addLocal.push(key);
    else if (plan.localWentEmpty) plan.kept.push(key);
    else plan.pushRemove.push(key);
  }
  for (const key of local) {
    if (remote.has(key)) continue;
    if (!snapshot?.has(key)) plan.pushAdd.push(key);
    else if (plan.remoteWentEmpty) plan.kept.push(key);
    else plan.removeLocal.push(key);
  }
  return plan;
};

// ─── Running a sync ───

export type ListLinkSyncResult = {
  status: "ok" | "partial" | "missing" | "disconnected";
  addedLocally: number;
  removedLocally: number;
  pushedAdds: number;
  pushedRemovals: number;
  kept: number;
  error: string | null;
};

const emptyResult = (status: ListLinkSyncResult["status"], error: string | null): ListLinkSyncResult => ({
  status,
  addedLocally: 0,
  removedLocally: 0,
  pushedAdds: 0,
  pushedRemovals: 0,
  kept: 0,
  error,
});

const chunk = <T>(items: T[], size: number): T[][] => {
  const chunks: T[][] = [];
  for (let i = 0; i < items.length; i += size) chunks.push(items.slice(i, i + size));
  return chunks;
};

const errorMessage = (error: unknown) => (error instanceof Error ? error.message : String(error));

const readSnapshot = (value: Prisma.JsonValue | null): Set<string> | null =>
  Array.isArray(value) ? new Set(value.filter((key): key is string => typeof key === "string")) : null;

const addLocalItems = async (listId: string, items: ListItemRef[]): Promise<number> => {
  let added = 0;
  for (const batch of chunk(items, 1_000)) {
    await prisma.item.createMany({
      data: batch.map((item) => ({ type: item.type === "movie" ? ItemType.movie : ItemType.series, imdbId: item.imdbId })),
      skipDuplicates: true,
    });
    const { count } = await prisma.listItem.createMany({
      data: batch.map((item) => ({ listId, type: item.type, imdbId: item.imdbId })),
      skipDuplicates: true,
    });
    added += count;
  }
  return added;
};

const removeLocalItems = async (listId: string, items: ListItemRef[]): Promise<number> => {
  let removed = 0;
  for (const type of [ListItemType.movie, ListItemType.series]) {
    const imdbIds = items.filter((item) => item.type === type).map((item) => item.imdbId);
    for (const batch of chunk(imdbIds, 1_000)) {
      const { count } = await prisma.listItem.deleteMany({ where: { listId, type, imdbId: { in: batch } } });
      removed += count;
    }
  }
  return removed;
};

/** Pushes in batches; returns the keys that made it and the first error, if any. */
const pushBatches = async (
  adapter: ProviderAdapter,
  link: ListLink,
  action: "add" | "remove",
  keys: string[],
  logger: FastifyBaseLogger
): Promise<{ done: string[]; error: string | null }> => {
  const done: string[] = [];
  let error: string | null = null;
  for (const batch of chunk(keys, PUSH_BATCH_SIZE)) {
    try {
      await adapter.change(link.profileId, link.remoteId, action, batch.map(refOf), logger);
      done.push(...batch);
    } catch (pushError) {
      error ??= errorMessage(pushError);
      logger.warn({ error: pushError, listId: link.listId, action }, "Linked list push failed; retrying next sync");
    }
  }
  return { done, error };
};

const runSync = async (link: ListLink, logger: FastifyBaseLogger): Promise<ListLinkSyncResult> => {
  const provider = link.provider as ListLinkProvider;
  const adapter = adapters[provider];
  const service = PROVIDER_NAMES[provider] ?? link.provider;
  if (!adapter) return emptyResult("disconnected", `Unknown service "${link.provider}"`);

  const record = (data: Prisma.ListLinkUpdateInput) => prisma.listLink.update({ where: { id: link.id }, data });

  if (!(await adapter.connected(link.profileId))) {
    const error = `${service} is not connected for this profile. Reconnect it in Settings, or unlink the list.`;
    await record({ lastError: error });
    return emptyResult("disconnected", error);
  }

  // A list gone from the service is never read as an empty one: the Fetch
  // list keeps everything, and the link waits to be unlinked or relinked.
  const remoteList = (await adapter.lists(link.profileId, logger)).find((list) => list.id === link.remoteId);
  if (!remoteList) {
    const error = `The linked ${service} list no longer exists. The Fetch list is kept as it is; unlink it or link another list.`;
    await record({ lastError: error });
    return emptyResult("missing", error);
  }

  // A Fetch rename the service refused earlier is sent again until it lands.
  let remoteName = remoteList.name;
  let renameError: string | null = null;
  if (link.pendingName !== null && link.pendingName !== remoteList.name) {
    try {
      await adapter.rename(link.profileId, link.remoteId, link.pendingName, logger);
      remoteName = link.pendingName;
    } catch (error) {
      renameError = errorMessage(error);
      logger.warn({ error, listId: link.listId }, "Linked list rename failed; retrying next sync");
    }
  }

  const remoteItems = await adapter.items(link.profileId, link.remoteId, logger);
  const localItems = await prisma.listItem.findMany({ where: { listId: link.listId }, select: { type: true, imdbId: true } });

  const remote = new Set(remoteItems.map(keyOf));
  const local = new Set(localItems.map((item) => keyOf({ type: item.type, imdbId: item.imdbId })));
  const plan = planListLinkSync(remote, local, readSnapshot(link.snapshot));
  const emptied = plan.remoteWentEmpty || plan.localWentEmpty;
  if (emptied) {
    logger.warn(
      { listId: link.listId, provider, remoteWentEmpty: plan.remoteWentEmpty, localWentEmpty: plan.localWentEmpty },
      "A linked list went entirely empty since the last sync — not removing anything from the other side"
    );
  }

  const addedLocally = await addLocalItems(link.listId, plan.addLocal.map(refOf));
  const removedLocally = await removeLocalItems(link.listId, plan.removeLocal.map(refOf));
  const adds = await pushBatches(adapter, link, "add", plan.pushAdd, logger);
  const removals = await pushBatches(adapter, link, "remove", plan.pushRemove, logger);

  // What both sides now hold. A failed add stays out, so the next sync reads it
  // as a local add and pushes it again; a failed removal stays in, so the next
  // sync reads it as a local removal rather than as an add on the service.
  const removedRemotely = new Set(removals.done);
  const next = new Set<string>();
  for (const key of remote) if (local.has(key)) next.add(key);
  for (const key of plan.addLocal) next.add(key);
  for (const key of adds.done) next.add(key);
  for (const key of plan.pushRemove) if (!removedRemotely.has(key)) next.add(key);
  for (const key of plan.kept) next.add(key);

  // An emptied side is a fault to look at, not a clean sync: the two lists
  // still differ, and every sync keeps them so until someone decides.
  const emptiedError = emptied
    ? `The ${plan.remoteWentEmpty ? `${service} list` : "Fetch list"} went empty since the last sync, so nothing was removed from the other side. Remove the items there too, or unlink the list.`
    : null;
  const error = adds.error ?? removals.error ?? renameError ?? emptiedError;
  await record({
    snapshot: [...next],
    remoteName,
    ...(renameError === null ? { pendingName: null } : {}),
    lastSyncedAt: new Date(),
    lastError: error,
  });

  return {
    status: error ? "partial" : "ok",
    addedLocally,
    removedLocally,
    pushedAdds: adds.done.length,
    pushedRemovals: removals.done.length,
    kept: plan.kept.length,
    error,
  };
};

// Everything that reads and rewrites a link (syncs, relinks, unlinks, rename
// retries) runs one at a time per Fetch list, so a relink can't land in the
// middle of a sync of the old target, and a "Sync now" on top of the scheduled
// pass waits for it rather than reconciling from the same stale snapshot.
const lanes = new Map<string, Promise<unknown>>();

const inLane = <T>(listId: string, work: () => Promise<T>): Promise<T> => {
  const run = (lanes.get(listId) ?? Promise.resolve()).then(work, work);
  const tail = run.catch(() => undefined);
  lanes.set(listId, tail);
  void tail.then(() => {
    if (lanes.get(listId) === tail) lanes.delete(listId);
  });
  return run;
};

// A sync asked for while one is already waiting its turn joins that one.
const queuedSyncs = new Map<string, Promise<ListLinkSyncResult>>();

/** Syncs the list's current link, read afresh once its turn comes. */
export const syncListLink = (link: ListLink, logger: FastifyBaseLogger): Promise<ListLinkSyncResult> => {
  const queued = queuedSyncs.get(link.listId);
  if (queued) return queued;
  const run = inLane(link.listId, async () => {
    queuedSyncs.delete(link.listId);
    const current = await prisma.listLink.findUnique({ where: { listId: link.listId } });
    if (!current) return emptyResult("missing", "This list is no longer linked");
    return runSync(current, logger);
  });
  queuedSyncs.set(link.listId, run);
  return run;
};

/**
 * Every list the profile linked to one service, for the scheduled sync. One
 * failing list doesn't stop the others; the job fails afterwards if any did,
 * so Settings → Sync Status shows it.
 */
export const syncProfileListLinks = async (
  profileId: string,
  provider: ListLinkProvider,
  logger: FastifyBaseLogger
): Promise<ListLinkSyncResult[]> => {
  const links = await prisma.listLink.findMany({ where: { profileId, provider }, orderBy: { createdAt: "asc" } });
  const results: ListLinkSyncResult[] = [];
  const failures: string[] = [];
  for (const link of links) {
    try {
      results.push(await syncListLink(link, logger));
    } catch (error) {
      const message = errorMessage(error);
      failures.push(message);
      await prisma.listLink.update({ where: { id: link.id }, data: { lastError: message } }).catch(() => undefined);
    }
  }
  if (failures.length > 0) {
    throw new Error(`${failures.length} linked ${PROVIDER_NAMES[provider]} list(s) failed to sync: ${failures[0]}`);
  }
  return results;
};

/**
 * Profiles with a list linked to the service other than `except`, so the
 * scheduled sync also reaches links whose profile has since disconnected it
 * and marks them so.
 */
export const listOtherLinkedProfiles = async (provider: ListLinkProvider, except: string[]): Promise<string[]> =>
  (
    await prisma.listLink.findMany({
      where: { provider, profileId: { notIn: except } },
      distinct: ["profileId"],
      select: { profileId: true },
    })
  ).map((link) => link.profileId);

// ─── Linking ───

/** The profile's own lists on the service, to pick one to link. */
export const listLinkTargets = async (
  profileId: string,
  provider: ListLinkProvider,
  logger: FastifyBaseLogger
): Promise<RemoteList[]> => {
  const adapter = adapters[provider];
  if (!(await adapter.connected(profileId))) {
    throw new ListLinkError(`${PROVIDER_NAMES[provider]} is not connected for this profile`, 409);
  }
  return adapter.lists(profileId, logger);
};

/**
 * Links a custom list to one of the profile's lists on the service, or to a
 * new one named after it when `remoteId` is "new", then runs the first sync,
 * which merges both sides. Relinking replaces the previous link and starts
 * over from a merge.
 */
export const linkList = async (
  profileId: string,
  listId: string,
  provider: ListLinkProvider,
  remoteId: string,
  logger: FastifyBaseLogger
): Promise<{ link: ListLink; sync: ListLinkSyncResult }> => {
  const list = await prisma.list.findFirst({ where: { id: listId, profileId } });
  if (!list) throw new ListLinkError("List not found", 404);
  if (list.kind !== ListKind.custom) {
    throw new ListLinkError(
      "Only custom lists can be linked; the watchlist already syncs with Trakt, Simkl and MDBList",
      409
    );
  }

  const adapter = adapters[provider];
  if (!(await adapter.connected(profileId))) {
    throw new ListLinkError(`${PROVIDER_NAMES[provider]} is not connected for this profile`, 409);
  }

  let target: RemoteList | undefined;
  // A list created here and then not linked (the save below failing) is left
  // on the service: there's no delete to undo it with, and it's harmless.
  if (remoteId === "new") {
    target = await adapter.create(profileId, list.name, logger);
  } else {
    target = (await adapter.lists(profileId, logger)).find((candidate) => candidate.id === remoteId);
    if (!target) throw new ListLinkError(`That ${PROVIDER_NAMES[provider]} list wasn't found on this account`, 404);
  }

  const chosen = target;
  return inLane(listId, async () => {
    // Linking the list it's already linked to keeps what both sides agreed on;
    // a different list starts over from a merge.
    const existing = await prisma.listLink.findUnique({ where: { listId } });
    const sameTarget = existing?.provider === provider && existing.remoteId === chosen.id;
    let link: ListLink;
    try {
      link = await prisma.listLink.upsert({
        where: { listId },
        create: { listId, profileId, provider, remoteId: chosen.id, remoteName: chosen.name },
        update: sameTarget
          ? { remoteName: chosen.name }
          : {
              provider,
              remoteId: chosen.id,
              remoteName: chosen.name,
              pendingName: null,
              snapshot: Prisma.DbNull,
              lastSyncedAt: null,
              lastError: null,
            },
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
        throw new ListLinkError(`That ${PROVIDER_NAMES[provider]} list is already linked to another Fetch list`, 409);
      }
      throw error;
    }
    return { link, sync: await runSync(link, logger) };
  });
};

/** Forgets the link. Neither list nor any item in either is touched. */
export const unlinkList = (profileId: string, listId: string): Promise<boolean> =>
  inLane(listId, async () => {
    const { count } = await prisma.listLink.deleteMany({ where: { listId, profileId } });
    return count > 0;
  });

// ─── Pushing a change as it happens ───

/**
 * Sends one add or remove made in Fetch to the linked list, if there is one.
 * Best-effort and never throws: a push that fails is retried by the next
 * scheduled sync, which sees the item on one side only.
 */
export const pushListLinkChange = async (
  listId: string,
  action: "add" | "remove",
  item: ListItemRef,
  logger: FastifyBaseLogger
): Promise<void> => {
  try {
    const link = await prisma.listLink.findUnique({ where: { listId } });
    if (!link || !isListLinkProvider(link.provider)) return;
    await adapters[link.provider].change(link.profileId, link.remoteId, action, [item], logger);
  } catch (error) {
    logger.warn({ error, listId, action }, "Linked list push failed; the next sync retries it");
  }
};

/**
 * Renames the linked list along with the Fetch one. Never throws: the name is
 * kept as pending first, so a rename the service refuses is retried by the
 * next sync.
 */
export const pushListLinkRename = (listId: string, name: string, logger: FastifyBaseLogger): Promise<void> =>
  inLane(listId, async () => {
    const link = await prisma.listLink.findUnique({ where: { listId } });
    if (!link || !isListLinkProvider(link.provider)) return;
    await prisma.listLink.update({ where: { id: link.id }, data: { pendingName: name } });
    await adapters[link.provider].rename(link.profileId, link.remoteId, name, logger);
    await prisma.listLink.update({ where: { id: link.id }, data: { remoteName: name, pendingName: null } });
  }).catch((error) => {
    logger.warn({ error, listId }, "Linked list rename failed; the next sync retries it");
  });

export type ListLinkSummary = {
  provider: ListLinkProvider;
  remoteId: string;
  remoteName: string;
  lastSyncedAt: Date | null;
  lastError: string | null;
};

export const summarizeListLink = (link: ListLink | null): ListLinkSummary | null =>
  link && isListLinkProvider(link.provider)
    ? {
        provider: link.provider,
        remoteId: link.remoteId,
        remoteName: link.remoteName,
        lastSyncedAt: link.lastSyncedAt,
        lastError: link.lastError,
      }
    : null;
