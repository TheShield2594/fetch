import { ItemType, ListItemType, ListKind, MetadataType, Prisma } from "@prisma/client";
import type { FastifyPluginAsync, FastifyReply } from "fastify";
import { prisma } from "../lib/prisma.js";
import { backfillMissingMetadata, syncMetadata } from "../lib/metadata.js";
import { pushTraktWatchlistChange } from "../lib/trakt-client.js";
import { pushWatchlistAddToTrackers } from "../lib/trackers.js";
import { resolveProfile } from "../lib/profile.js";
import {
  isListLinkProvider,
  linkList,
  ListLinkError,
  listLinkTargets,
  pushListLinkChange,
  pushListLinkRename,
  summarizeListLink,
  syncListLink,
  unlinkList,
} from "../lib/list-links.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

// A page a client asks for is capped rather than rejected: an addon asking for
// more than this gets a smaller page and a cursor, which is the same shape it
// would have handled anyway.
const MAX_LIST_ITEMS_PAGE = 500;

// `addedAt` alone is not a total order — a CSV or Trakt import stamps a whole
// batch with the same timestamp — and a cursor over a non-total order either
// repeats or skips the rows that tie. Falling back to the rest of the unique
// tuple makes the order total, so a page boundary landing inside a tied batch
// resumes at exactly the row it stopped on.
const LIST_ITEMS_ORDER_BY = [
  { addedAt: "desc" },
  { type: "asc" },
  { imdbId: "asc" },
] satisfies Prisma.ListItemOrderByWithRelationInput[];

type ListItemCursor = { type: ListItemType; imdbId: string };

// A description is a line or two on the list's card, not a document.
const MAX_LIST_DESCRIPTION = 280;

// How many titles one bulk remove or one reorder may name. A reorder names the
// whole list, so its cap is the larger of the two; both are well past what the
// web app can show at once.
const MAX_BULK_REMOVE = 500;
const MAX_REORDER_ITEMS = 5000;

// How many posters make a list's cover on the lists index (a 2×2 grid).
const LIST_COVER_POSTERS = 4;

type ListItemRef = { type: ListItemType; imdbId: string };

// `{ items: [{ type, imdbId }] }`, shared by the bulk remove and the reorder.
// Returns the refs, or the message for a 400. Duplicates are refused rather
// than collapsed: a reorder naming one title twice has no single answer for
// where it goes, and a client sending one has lost track of its own list.
const parseItemRefs = (body: unknown, max: number): ListItemRef[] | string => {
  const raw = body && typeof body === "object" ? (body as { items?: unknown }).items : undefined;
  if (!Array.isArray(raw) || raw.length === 0) return "items must be a non-empty array of { type, imdbId }";
  if (raw.length > max) return `items can name at most ${max} titles`;

  const refs: ListItemRef[] = [];
  const seen = new Set<string>();
  for (const entry of raw) {
    const { type, imdbId } = (entry ?? {}) as { type?: unknown; imdbId?: unknown };
    if (!Object.values(ListItemType).includes(type as ListItemType)) {
      return "each item's type must be one of: movie, series";
    }
    if (typeof imdbId !== "string" || !imdbId.trim()) return "each item needs an imdbId";
    const ref = { type: type as ListItemType, imdbId: imdbId.trim() };
    const key = `${ref.type}:${ref.imdbId}`;
    if (seen.has(key)) return `${ref.imdbId} is named more than once`;
    seen.add(key);
    refs.push(ref);
  }
  return refs;
};

// `undefined` leaves the description alone, an empty string clears it, and
// anything else is trimmed and capped.
const parseDescription = (value: unknown): string | null | undefined | { error: string } => {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (typeof value !== "string") return { error: "description must be a string" };
  const trimmed = value.trim();
  if (trimmed.length > MAX_LIST_DESCRIPTION) {
    return { error: `description can be at most ${MAX_LIST_DESCRIPTION} characters` };
  }
  return trimmed || null;
};

// Base64 keeps the cursor opaque: it is this route's ordering in disguise, and
// a client that hand-builds one is a client that breaks when the ordering
// changes.
const encodeListItemCursor = (item: ListItemCursor) =>
  Buffer.from(`${item.type}:${item.imdbId}`, "utf8").toString("base64url");

const decodeListItemCursor = (cursor: string): ListItemCursor | null => {
  // Node's base64url decoder never throws, so garbage arrives here as garbage
  // rather than as an exception — every field is checked below.
  const decoded = Buffer.from(cursor, "base64url").toString("utf8");
  const separator = decoded.indexOf(":");
  if (separator < 1) return null;

  const type = decoded.slice(0, separator);
  const imdbId = decoded.slice(separator + 1);
  if (!imdbId || !Object.values(ListItemType).includes(type as ListItemType)) return null;

  return { type: type as ListItemType, imdbId };
};

const listsRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.post<{ Body: unknown }>("/lists", async (request, reply) => {
    if (!request.body || typeof request.body !== "object") {
      return reply.code(400).send({ error: "name and kind are required" });
    }

    const body = request.body as { name?: unknown; kind?: unknown };
    if (typeof body.name !== "string" || !body.name.trim()) {
      return reply.code(400).send({ error: "name and kind are required" });
    }

    if (!Object.values(ListKind).includes(body.kind as ListKind)) {
      return reply.code(400).send({ error: "kind must be one of: watchlist, custom, collection" });
    }

    // Only `custom` can be created here. The watchlist and the collection are
    // singletons the app bootstraps for each profile — a second one of either
    // is never the default (the lookups take the oldest), so it would be a list
    // that syncs nothing and, since the delete route protects both kinds,
    // couldn't be removed again.
    if (body.kind !== ListKind.custom) {
      return reply.code(409).send({
        error: "Only custom lists can be created; the default watchlist and collection already exist",
      });
    }

    const description = parseDescription((request.body as { description?: unknown }).description);
    if (typeof description === "object" && description !== null) {
      return reply.code(400).send({ error: description.error });
    }

    const list = await prisma.list.create({
      data: {
        name: body.name.trim(),
        kind: body.kind as ListKind,
        profileId: request.profileId!,
        ...(description !== undefined ? { description } : {}),
      },
    });

    return reply.code(201).send({ list: { ...list, itemCount: 0 } });
  });

  app.get("/lists", async (request) => {
    const [lists, coverRows] = await Promise.all([
      prisma.list.findMany({
        where: { profileId: request.profileId! },
        orderBy: [{ createdAt: "asc" }],
        include: { _count: { select: { items: true } }, link: true },
      }),
      // Every list's cover in one query rather than one per list: the first
      // few items with a poster, in the list's manual order (arranged rows by
      // position, then the never-arranged ones newest first — the same order
      // "My order" shows). Titles still waiting on their metadata row have no
      // poster to give and are skipped, so a cover fills from the next ones.
      // The two enums are distinct Postgres types, hence the text casts.
      prisma.$queryRaw<{ listId: string; poster: string }[]>(Prisma.sql`
        SELECT ranked."listId", ranked.poster
        FROM (
          SELECT li."listId", m.poster,
                 ROW_NUMBER() OVER (
                   PARTITION BY li."listId"
                   ORDER BY li.position ASC NULLS LAST, li."addedAt" DESC, li.type ASC, li."imdbId" ASC
                 ) AS rn
          FROM "ListItem" li
          JOIN "List" l ON l.id = li."listId"
          JOIN "Metadata" m ON m."imdbId" = li."imdbId" AND m.type::text = li.type::text
          WHERE l."profileId" = ${request.profileId!}::uuid
            AND m.poster IS NOT NULL
        ) ranked
        WHERE ranked.rn <= ${LIST_COVER_POSTERS}
        ORDER BY ranked."listId", ranked.rn
      `),
    ]);

    const covers = new Map<string, string[]>();
    for (const row of coverRows) {
      const posters = covers.get(row.listId);
      if (posters) posters.push(row.poster);
      else covers.set(row.listId, [row.poster]);
    }

    return {
      lists: lists.map((list) => ({
        id: list.id,
        name: list.name,
        description: list.description ?? null,
        kind: list.kind,
        createdAt: list.createdAt,
        itemCount: list._count.items,
        covers: covers.get(list.id) ?? [],
        link: summarizeListLink(list.link),
      })),
    };
  });

  // ─── Linking a custom list to a Trakt or MDBList list (lib/list-links.ts) ───

  // Errors from the service itself (a Trakt outage, an expired MDBList token)
  // are a bad gateway rather than a 500: nothing is wrong with this server.
  const sendLinkError = (reply: FastifyReply, error: unknown) => {
    if (error instanceof ListLinkError) return reply.code(error.status).send({ error: error.message });
    return reply.code(502).send({ error: error instanceof Error ? error.message : "The service didn't answer" });
  };

  app.get<{ Params: { provider: string } }>("/lists/link-targets/:provider", async (request, reply) => {
    if (!isListLinkProvider(request.params.provider)) {
      return reply.code(400).send({ error: "provider must be one of: trakt, mdblist" });
    }
    try {
      return { lists: await listLinkTargets(request.profileId!, request.params.provider, request.log) };
    } catch (error) {
      return sendLinkError(reply, error);
    }
  });

  app.put<{ Params: { id: string }; Body: unknown }>("/lists/:id/link", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const body = (request.body ?? {}) as { provider?: unknown; remoteId?: unknown };
    if (!isListLinkProvider(body.provider)) {
      return reply.code(400).send({ error: "provider must be one of: trakt, mdblist" });
    }
    if (typeof body.remoteId !== "string" || !body.remoteId.trim()) {
      return reply.code(400).send({ error: 'remoteId is required: a list id, or "new" to create one' });
    }
    try {
      const { link, sync } = await linkList(
        request.profileId!,
        request.params.id,
        body.provider,
        body.remoteId.trim(),
        request.log
      );
      return { link: summarizeListLink(await prisma.listLink.findUnique({ where: { id: link.id } })), sync };
    } catch (error) {
      return sendLinkError(reply, error);
    }
  });

  // Unlinking leaves both lists exactly as they are.
  app.delete<{ Params: { id: string } }>("/lists/:id/link", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const removed = await unlinkList(request.profileId!, request.params.id);
    if (!removed) return reply.code(404).send({ error: "This list isn't linked" });
    return reply.code(204).send();
  });

  app.post<{ Params: { id: string } }>("/lists/:id/link/sync", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const link = await prisma.listLink.findFirst({ where: { listId: request.params.id, profileId: request.profileId! } });
    if (!link) return reply.code(404).send({ error: "This list isn't linked" });
    try {
      const sync = await syncListLink(link, request.log);
      return { link: summarizeListLink(await prisma.listLink.findUnique({ where: { id: link.id } })), sync };
    } catch (error) {
      return sendLinkError(reply, error);
    }
  });

  app.delete<{ Params: { id: string } }>("/lists/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }

    const list = await prisma.list.findFirst({
      where: { id: request.params.id, profileId: request.profileId! },
    });
    if (!list) return reply.code(404).send({ error: "List not found" });

    // The default lists are find-or-create, so losing one doesn't break the app
    // — but ListItem cascades on the list, so the delete takes every item in it
    // and nothing brings those back. The web UI only offers delete on custom
    // lists; this makes a direct API call agree with it.
    //
    // Guarded per row rather than per kind: an install can already hold a second
    // watchlist — from the lookup that used to key on the name too, or from a
    // POST made before that route was closed — and protecting the kind would
    // leave that duplicate permanently stuck, syncing nothing and impossible to
    // remove. The default is the oldest row of its kind, which is what
    // getDefaultWatchlist/getDefaultCollection resolve to; the lookup is
    // repeated here rather than called, since those two create a row when they
    // find none and a delete has no business doing that.
    if (list.kind !== ListKind.custom) {
      const defaultForKind = await prisma.list.findFirst({
        where: { kind: list.kind, profileId: request.profileId! },
        orderBy: { createdAt: "asc" },
      });

      if (defaultForKind?.id === list.id) {
        return reply.code(409).send({
          error:
            list.kind === ListKind.watchlist
              ? "The default watchlist can't be deleted"
              : "The default collection can't be deleted",
        });
      }
    }

    // A linked list's link goes with it (it cascades); the list on Trakt or
    // MDBList is left alone. Deleting on one side never deletes the other.
    await prisma.list.delete({ where: { id: list.id } });

    return reply.code(204).send();
  });

  app.patch<{ Params: { id: string }; Body: unknown }>("/lists/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }

    if (!request.body || typeof request.body !== "object") {
      return reply.code(400).send({ error: "name or description is required" });
    }

    // Either field on its own is an edit; neither is a mistake. A name, when
    // sent, still has to be a real one — a list can't be renamed to nothing.
    const body = request.body as { name?: unknown; description?: unknown };
    if (body.name === undefined && body.description === undefined) {
      return reply.code(400).send({ error: "name or description is required" });
    }
    if (body.name !== undefined && (typeof body.name !== "string" || !body.name.trim())) {
      return reply.code(400).send({ error: "name is required" });
    }
    const description = parseDescription(body.description);
    if (typeof description === "object" && description !== null) {
      return reply.code(400).send({ error: description.error });
    }

    const list = await prisma.list.findFirst({
      where: { id: request.params.id, profileId: request.profileId! },
    });
    if (!list) return reply.code(404).send({ error: "List not found" });

    const name = typeof body.name === "string" ? body.name.trim() : undefined;
    const updated = await prisma.list.update({
      where: { id: list.id },
      data: {
        ...(name !== undefined ? { name } : {}),
        ...(description !== undefined ? { description } : {}),
      },
    });
    // Only a rename travels to a linked list; Trakt and MDBList descriptions
    // are theirs to keep.
    if (list.kind === ListKind.custom && name !== undefined && name !== list.name) {
      void pushListLinkRename(list.id, updated.name, request.log);
    }

    return { list: updated };
  });

  // The list's manual order. The body names items in the order they should
  // appear; they get positions 0..n-1 and every item it leaves out goes back
  // to "never arranged" (NULL), which sorts after them. In practice the web
  // app always sends the whole list. Every named item has to be in the list —
  // a stale client reordering a title someone else removed is told so rather
  // than having its order quietly half-applied.
  //
  // Items added after an arrangement get no position (POST below leaves it
  // NULL), so they land after the arranged ones, newest first, until the list
  // is arranged again.
  app.put<{ Params: { id: string }; Body: unknown }>("/lists/:id/order", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) {
      return reply.code(400).send({ error: "id must be a valid UUID" });
    }
    const refs = parseItemRefs(request.body, MAX_REORDER_ITEMS);
    if (typeof refs === "string") return reply.code(400).send({ error: refs });

    const list = await prisma.list.findFirst({
      where: { id: request.params.id, profileId: request.profileId! },
    });
    if (!list) return reply.code(404).send({ error: "List not found" });

    const present = await prisma.listItem.findMany({
      where: { listId: list.id },
      select: { type: true, imdbId: true },
    });
    const inList = new Set(present.map((item) => `${item.type}:${item.imdbId}`));
    const stranger = refs.find((ref) => !inList.has(`${ref.type}:${ref.imdbId}`));
    if (stranger) {
      return reply.code(400).send({ error: `${stranger.imdbId} (${stranger.type}) isn't in this list` });
    }

    // One UPDATE over a VALUES table rather than one per row: a whole list
    // re-sorted is one round trip, and the clear-then-set pair runs in a
    // transaction so a reader never sees half an order.
    const values = Prisma.join(
      refs.map((ref, position) => Prisma.sql`(${ref.type}, ${ref.imdbId}, ${position}::int)`)
    );
    await prisma.$transaction(async (tx) => {
      await tx.listItem.updateMany({ where: { listId: list.id }, data: { position: null } });
      await tx.$executeRaw(Prisma.sql`
        UPDATE "ListItem" li
        SET position = v.position
        FROM (VALUES ${values}) AS v(type, "imdbId", position)
        WHERE li."listId" = ${list.id}::uuid
          AND li.type::text = v.type
          AND li."imdbId" = v."imdbId"
      `);
    });

    return reply.code(204).send();
  });

  // Several titles out of one list at once. Answers with the ones actually
  // removed — anything already gone is skipped rather than failing the batch,
  // since the end state the caller asked for holds either way. Each removed
  // title goes to Trakt's watchlist or the linked list exactly as a single
  // delete would send it.
  app.post<{ Params: { listId: string }; Body: unknown }>(
    "/lists/:listId/items/remove",
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.listId)) {
        return reply.code(400).send({ error: "listId must be a valid UUID" });
      }
      const refs = parseItemRefs(request.body, MAX_BULK_REMOVE);
      if (typeof refs === "string") return reply.code(400).send({ error: refs });

      const list = await prisma.list.findFirst({
        where: { id: request.params.listId, profileId: request.profileId! },
      });
      if (!list) return reply.code(404).send({ error: "List not found" });

      const where = {
        listId: list.id,
        OR: refs.map((ref) => ({ type: ref.type, imdbId: ref.imdbId })),
      } satisfies Prisma.ListItemWhereInput;
      const existing = await prisma.listItem.findMany({ where, select: { type: true, imdbId: true } });
      if (existing.length > 0) await prisma.listItem.deleteMany({ where });

      const removed = existing.map((item) => ({ type: item.type, imdbId: item.imdbId }));
      if (list.kind === ListKind.watchlist && removed.length > 0) {
        // One at a time in the background: Trakt has no bulk endpoint here
        // that this client speaks, and five hundred awaited calls would hold
        // the response for minutes. A failed push is logged by the helper.
        void (async () => {
          for (const item of removed) {
            await pushTraktWatchlistChange("remove", item, request.log, list.profileId);
          }
        })();
      } else if (list.kind === ListKind.custom) {
        for (const item of removed) void pushListLinkChange(list.id, "remove", item, request.log);
      }

      return { removed };
    }
  );

  app.post<{ Params: { listId: string }; Body: unknown }>(
    "/lists/:listId/items",
    async (request, reply) => {
      if (!request.body || typeof request.body !== "object") {
        return reply.code(400).send({ error: "type and imdbId are required" });
      }

      const body = request.body as { type?: unknown; imdbId?: unknown; title?: unknown };
      if (!Object.values(ListItemType).includes(body.type as ListItemType)) {
        return reply.code(400).send({ error: "type must be one of: movie, series" });
      }

      if (typeof body.imdbId !== "string" || !body.imdbId.trim()) {
        return reply.code(400).send({ error: "type and imdbId are required" });
      }

      if (body.title !== undefined && typeof body.title !== "string") {
        return reply.code(400).send({ error: "title must be a string when provided" });
      }

      if (!UUID_V4_PATTERN.test(request.params.listId)) {
        return reply.code(400).send({ error: "listId must be a valid UUID" });
      }

      const list = await prisma.list.findFirst({
        where: { id: request.params.listId, profileId: request.profileId! },
      });
      if (!list) return reply.code(404).send({ error: "List not found" });

      const imdbId = (body.imdbId as string).trim();
      const type = body.type as ListItemType;
      const itemType = type as ItemType;

      try {
        const listItem = await prisma.$transaction(async (tx) => {
          const title = (body.title as string | undefined)?.trim();
          await tx.item.upsert({
            where: { type_imdbId: { type: itemType, imdbId } },
            create: { type: itemType, imdbId, ...(title ? { title } : {}) },
            update: title ? { title } : {},
          });

          return tx.listItem.create({
            data: { listId: request.params.listId, type, imdbId },
          });
        });

        const metadataTypeForSync = itemType === ItemType.movie ? MetadataType.movie : MetadataType.series;
        void syncMetadata(imdbId, metadataTypeForSync).catch((err) =>
          app.log.warn({ imdbId, type: metadataTypeForSync, err }, "Background metadata sync failed")
        );

        if (list.kind === ListKind.watchlist) {
          await pushTraktWatchlistChange("add", { type, imdbId }, request.log, list.profileId);
          // Simkl's plan-to-watch and MDBList's watchlist, as the TV adds to
          // them. Not awaited: they never fail the add, and shouldn't slow it.
          void pushWatchlistAddToTrackers(list.profileId, { type, imdbId }, request.log);
        } else if (list.kind === ListKind.custom) {
          // To the Trakt or MDBList list it is linked to, if any. Not awaited,
          // and never fails the add: the next sync retries a push that failed.
          void pushListLinkChange(list.id, "add", { type, imdbId }, request.log);
        }

        return reply.code(201).send({ listItem });
      } catch (error) {
        if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
          return reply.code(409).send({ error: "Item already exists in this list" });
        }
        throw error;
      }
    }
  );

  app.delete<{ Params: { listId: string; type: string; imdbId: string } }>(
    "/lists/:listId/items/:type/:imdbId",
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.listId)) {
        return reply.code(400).send({ error: "listId must be a valid UUID" });
      }

      const type = request.params.type;
      if (!Object.values(ListItemType).includes(type as ListItemType)) {
        return reply.code(400).send({ error: "type must be one of: movie, series" });
      }

      const list = await prisma.list.findFirst({
        where: { id: request.params.listId, profileId: request.profileId! },
      });
      if (!list) return reply.code(404).send({ error: "List not found" });

      const removed = await prisma.listItem.deleteMany({
        where: {
          listId: request.params.listId,
          type: type as ListItemType,
          imdbId: request.params.imdbId,
        },
      });

      if (removed.count === 0) return reply.code(404).send({ error: "List item not found" });

      if (list.kind === ListKind.watchlist) {
        const item = { type: type as ListItemType, imdbId: request.params.imdbId };
        await pushTraktWatchlistChange("remove", item, request.log, list.profileId);
      } else if (list.kind === ListKind.custom) {
        const item = { type: type as ListItemType, imdbId: request.params.imdbId };
        void pushListLinkChange(list.id, "remove", item, request.log);
      }

      return reply.code(204).send();
    }
  );

  app.delete<{ Params: { listId: string; imdbId: string }; Querystring: { type?: string } }>(
    "/lists/:listId/items/:imdbId",
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.listId)) {
        return reply.code(400).send({ error: "listId must be a valid UUID" });
      }

      const list = await prisma.list.findFirst({
        where: { id: request.params.listId, profileId: request.profileId! },
      });
      if (!list) return reply.code(404).send({ error: "List not found" });

      const where: { listId: string; imdbId: string; type?: ListItemType } = {
        listId: request.params.listId,
        imdbId: request.params.imdbId,
      };

      if (request.query.type) {
        if (!Object.values(ListItemType).includes(request.query.type as ListItemType)) {
          return reply.code(400).send({ error: "type must be one of: movie, series" });
        }
        where.type = request.query.type as ListItemType;
      } else {
        const [onlyMatch, ...otherMatches] = await prisma.listItem.findMany({ where });
        if (!onlyMatch) return reply.code(404).send({ error: "List item not found" });
        if (otherMatches.length > 0) {
          return reply.code(400).send({
            error: "Multiple items match this imdbId; provide ?type=movie or ?type=series to disambiguate",
          });
        }
        where.type = onlyMatch.type;
      }

      const removed = await prisma.listItem.deleteMany({ where });
      if (removed.count === 0) return reply.code(404).send({ error: "List item not found" });

      if (list.kind === ListKind.watchlist) {
        const item = { type: where.type!, imdbId: request.params.imdbId };
        await pushTraktWatchlistChange("remove", item, request.log, list.profileId);
      } else if (list.kind === ListKind.custom) {
        void pushListLinkChange(list.id, "remove", { type: where.type!, imdbId: request.params.imdbId }, request.log);
      }

      return reply.code(204).send();
    }
  );

  app.get<{
    Params: { listId: string };
    Querystring: { type?: string; limit?: string; cursor?: string };
  }>("/lists/:listId/items", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.listId)) {
      return reply.code(400).send({ error: "listId must be a valid UUID" });
    }

    const typeFilter = request.query.type;
    if (typeFilter !== undefined && !Object.values(ListItemType).includes(typeFilter as ListItemType)) {
      return reply.code(400).send({ error: "type must be one of: movie, series" });
    }

    // Paging is opt-in. The list page in the web app renders a whole list in one
    // request and has no paging UI of its own, so an absent `limit` keeps the
    // original behaviour of returning everything rather than silently truncating
    // every existing caller to a default page.
    let take: number | null = null;
    if (request.query.limit !== undefined) {
      const parsed = Number(request.query.limit);
      if (!Number.isInteger(parsed) || parsed < 1) {
        return reply.code(400).send({ error: "limit must be a positive integer" });
      }
      take = Math.min(parsed, MAX_LIST_ITEMS_PAGE);
    }

    let cursor: ListItemCursor | null = null;
    if (request.query.cursor !== undefined) {
      cursor = decodeListItemCursor(request.query.cursor);
      if (!cursor) return reply.code(400).send({ error: "cursor is not a valid pagination cursor" });
    }

    const list = await prisma.list.findFirst({
      where: { id: request.params.listId, profileId: request.profileId! },
    });
    if (!list) return reply.code(404).send({ error: "List not found" });

    const page = await prisma.listItem.findMany({
      where: {
        listId: request.params.listId,
        ...(typeFilter ? { type: typeFilter as ListItemType } : {}),
      },
      orderBy: LIST_ITEMS_ORDER_BY,
      // One row past the page answers "is there more?" without a second COUNT
      // over the same rows.
      ...(take !== null ? { take: take + 1 } : {}),
      ...(cursor
        ? {
            cursor: { listId_type_imdbId: { listId: request.params.listId, ...cursor } },
            skip: 1,
          }
        : {}),
    });

    const listItems = take !== null && page.length > take ? page.slice(0, take) : page;
    const hasMore = listItems.length < page.length;

    const metadataMap = new Map<
      string,
      { name: string; poster: string | null; year: number | null; genres: string[]; rating: number | null }
    >();

    if (listItems.length > 0) {
      const imdbIdsByType = new Map<MetadataType, string[]>();
      for (const item of listItems) {
        const type = item.type as unknown as MetadataType;
        const ids = imdbIdsByType.get(type);
        if (ids) ids.push(item.imdbId);
        else imdbIdsByType.set(type, [item.imdbId]);
      }

      const metadata = (
        await Promise.all(
          [...imdbIdsByType.entries()].map(([type, imdbIds]) =>
            prisma.metadata.findMany({
              where: { type, imdbId: { in: imdbIds } },
              select: { imdbId: true, type: true, name: true, poster: true, year: true, genres: true, rating: true },
            })
          )
        )
      ).flat();

      for (const m of metadata) {
        metadataMap.set(`${m.imdbId}:${m.type}`, {
          name: m.name,
          poster: m.poster,
          year: m.year,
          genres: m.genres,
          rating: m.rating,
        });
      }
    }

    // Items still waiting on a metadata row fall back to the title captured when
    // they were added, so a list shows real names instead of raw IMDb IDs while
    // the rows are being filled in.
    const titleMap = new Map<string, string>();
    const missing = listItems.filter((item) => !metadataMap.has(`${item.imdbId}:${item.type}`));

    if (missing.length > 0) {
      const rows = await prisma.item.findMany({
        where: { imdbId: { in: [...new Set(missing.map((item) => item.imdbId))] } },
        select: { imdbId: true, type: true, title: true },
      });
      for (const row of rows) {
        if (row.title) titleMap.set(`${row.imdbId}:${row.type}`, row.title);
      }

      backfillMissingMetadata(
        missing.map((item) => ({ imdbId: item.imdbId, type: item.type as unknown as MetadataType })),
        app.log
      );
    }

    const items = listItems.map((item) => {
      const key = `${item.imdbId}:${item.type}`;
      const meta = metadataMap.get(key);
      return { ...item, title: titleMap.get(key) ?? null, metadata: meta ?? null };
    });

    const last = listItems[listItems.length - 1];
    return { items, ...(hasMore && last ? { nextCursor: encodeListItemCursor(last) } : {}) };
  });

  app.get<{ Params: { imdbId: string }; Querystring: { type?: string } }>(
    "/items/:imdbId/lists",
    async (request) => {
      const { imdbId } = request.params;
      const typeFilter = request.query.type;

      const where: { imdbId: string; type?: ListItemType; list: { profileId: string } } = {
        imdbId,
        list: { profileId: request.profileId! },
      };
      if (typeFilter && Object.values(ListItemType).includes(typeFilter as ListItemType)) {
        where.type = typeFilter as ListItemType;
      }

      const listItems = await prisma.listItem.findMany({
        where,
        include: { list: { select: { id: true, name: true, kind: true } } },
      });

      return {
        lists: listItems.map((item) => ({
          listId: item.list.id,
          listName: item.list.name,
          listKind: item.list.kind,
          type: item.type,
          addedAt: item.addedAt,
        })),
      };
    }
  );
};

export default listsRoutes;
