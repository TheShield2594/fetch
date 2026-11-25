import type { FastifyPluginAsync } from "fastify";
import type { MetadataType } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { resolveProfile } from "../lib/profile.js";
import {
  deriveShareKey,
  isLiveGeneration,
  nextGeneration,
  retiredGeneration,
  shareKeyMatches,
} from "../lib/share-keys.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

/*
 * A list as a read-only page for someone with no account here: a friend sent a
 * link to "Films to watch together". The link is its own credential
 * (lib/share-keys.ts) and shows that one list and nothing else — its name,
 * its description and its titles. Not whose it is, not what they have watched,
 * and nothing that can be changed.
 */

/** Far more than a list anyone reads top to bottom, and a bound on what an anonymous request can ask for. */
const MAX_SHARED_ITEMS = 1000;

export const listSharePath = (listId: string, generation: number | null | undefined): string | null => {
  if (!isLiveGeneration(generation)) return null;
  const key = deriveShareKey("list", listId, generation);
  return key ? `/shared/lists/${listId}/${key}` : null;
};

const sharedListRoutes: FastifyPluginAsync = async (app) => {
  // ─── Sharing and unsharing: the list's own profile ───

  const ownList = async (profileId: string, id: string) =>
    UUID_V4_PATTERN.test(id)
      ? prisma.list.findFirst({ where: { id, profileId }, select: { id: true, shareGeneration: true } })
      : null;

  // Shares the list, or replaces the link when it is already shared: the old
  // link stops working, which is how one that went further than intended is
  // taken back without unsharing.
  app.post<{ Params: { id: string } }>("/lists/:id/share", { preHandler: resolveProfile }, async (request, reply) => {
    const list = await ownList(request.profileId!, request.params.id);
    if (!list) return reply.code(404).send({ error: "List not found" });

    const shareGeneration = nextGeneration(list.shareGeneration);
    await prisma.list.update({ where: { id: list.id }, data: { shareGeneration } });
    return { sharePath: listSharePath(list.id, shareGeneration) };
  });

  app.delete<{ Params: { id: string } }>("/lists/:id/share", { preHandler: resolveProfile }, async (request, reply) => {
    const list = await ownList(request.profileId!, request.params.id);
    if (!list) return reply.code(404).send({ error: "List not found" });

    if (isLiveGeneration(list.shareGeneration)) {
      await prisma.list.update({
        where: { id: list.id },
        data: { shareGeneration: retiredGeneration(list.shareGeneration) },
      });
    }
    return reply.code(204).send();
  });

  // ─── The shared page's data: public, the key in the path is the credential ───

  app.get<{ Params: { listId: string; key: string } }>(
    "/shared/lists/:listId/:key",
    { config: { rateLimit: { max: 60, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const { listId, key } = request.params;
      // One answer for every way of being wrong: the route says nothing about
      // which lists exist or were ever shared.
      const notFound = () => reply.code(404).send({ error: "This list isn't shared, or the link is out of date." });
      if (!UUID_V4_PATTERN.test(listId)) return notFound();

      const list = await prisma.list.findUnique({
        where: { id: listId },
        select: { id: true, name: true, description: true, shareGeneration: true, _count: { select: { items: true } } },
      });
      if (!list || !shareKeyMatches(key, "list", listId, list.shareGeneration)) return notFound();

      // The list's manual order, as its owner sees it under "My order".
      const items = await prisma.listItem.findMany({
        where: { listId },
        orderBy: [{ position: { sort: "asc", nulls: "last" } }, { addedAt: "desc" }, { type: "asc" }, { imdbId: "asc" }],
        take: MAX_SHARED_ITEMS,
        select: { type: true, imdbId: true },
      });

      const idsByType = new Map<MetadataType, string[]>();
      for (const item of items) {
        const type = item.type as unknown as MetadataType;
        idsByType.set(type, [...(idsByType.get(type) ?? []), item.imdbId]);
      }
      const metadata = (
        await Promise.all(
          [...idsByType].map(([type, imdbIds]) =>
            prisma.metadata.findMany({
              where: { type, imdbId: { in: imdbIds } },
              select: { imdbId: true, type: true, name: true, poster: true, year: true, genres: true, rating: true },
            })
          )
        )
      ).flat();
      const metaByKey = new Map(metadata.map((meta) => [`${meta.type}:${meta.imdbId}`, meta]));

      reply.header("Cache-Control", "private, max-age=60");
      return {
        list: { name: list.name, description: list.description ?? null, itemCount: list._count.items },
        items: items.map((item) => {
          const meta = metaByKey.get(`${item.type}:${item.imdbId}`);
          return {
            type: item.type,
            imdbId: item.imdbId,
            // A title still waiting on its metadata row is listed by its id.
            name: meta?.name ?? item.imdbId,
            year: meta?.year ?? null,
            // TMDB's own poster, never the RPDB one the signed-in app shows:
            // an RPDB poster's address carries the server's RPDB key, and this
            // response goes to whoever holds the link.
            poster: meta?.poster ?? null,
            genres: meta?.genres ?? [],
            rating: meta?.rating ?? null,
          };
        }),
      };
    }
  );
};

export default sharedListRoutes;
