import type { FastifyPluginAsync } from "fastify";
import { MetadataType, Prisma } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { resolveProfile } from "../lib/profile.js";
import { validateCollections, validateSource } from "../lib/collections.js";
import { getTmdb } from "../lib/tmdb-client.js";
import { getTraktClient, isTraktConnected } from "../lib/trakt-client.js";
import { ownerForProfile } from "../lib/integration-owner.js";
import { buildMetasFromIds, getCustomListMetas } from "../lib/stremio-helpers.js";
import type { TmdbCollectionSourceQuery } from "../tmdb.js";
import type { StremioMetaPreview } from "../lib/types.js";

const PREVIEW_LIMIT = 20;

type PreviewSource = Record<string, unknown> & { provider?: string };

const stremioTypeOf = (mediaType: unknown): "movie" | "series" =>
  typeof mediaType === "string" && mediaType.toUpperCase() === "TV" ? "series" : "movie";

const collectionsRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/collections", async (request) => {
    const row = await prisma.profileCollections.findUnique({ where: { profileId: request.profileId! } });
    return {
      collections: (row?.collections as unknown[] | undefined) ?? [],
      updatedAt: row?.updatedAt.toISOString() ?? null,
    };
  });

  // Replaces the whole list, as saving does on the TV. A client that wants to
  // avoid overwriting a newer save sends the `updatedAt` it last read as
  // `baseUpdatedAt`; a mismatch is a 409 carrying what is there now.
  app.put<{ Body: unknown }>("/collections", async (request, reply) => {
    const body = (request.body ?? {}) as { collections?: unknown; baseUpdatedAt?: unknown };
    const result = validateCollections(body.collections);
    if (!result.ok) return reply.code(400).send({ error: result.error });

    const profileId = request.profileId!;
    if (typeof body.baseUpdatedAt === "string" || body.baseUpdatedAt === null) {
      const current = await prisma.profileCollections.findUnique({ where: { profileId } });
      const currentStamp = current?.updatedAt.toISOString() ?? null;
      if (currentStamp !== body.baseUpdatedAt) {
        return reply.code(409).send({
          error: "These collections were changed somewhere else. Reload to see the latest.",
          code: "collections_conflict",
          collections: (current?.collections as unknown[] | undefined) ?? [],
          updatedAt: currentStamp,
        });
      }
    }

    const updatedAt = new Date();
    const collections = result.value as unknown as Prisma.InputJsonValue;
    await prisma.profileCollections.upsert({
      where: { profileId },
      create: { profileId, collections, updatedAt },
      update: { collections, updatedAt },
    });
    return { collections: result.value, updatedAt: updatedAt.toISOString() };
  });

  // The first page of one source, so the website's builder can show what a row
  // will hold before it is saved. Addon catalogs are previewed by the browser
  // itself, straight from the addon — the server doesn't know the TV's addons.
  app.post<{ Body: unknown }>(
    "/collections/preview",
    { config: { rateLimit: { max: 60, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const source = ((request.body ?? {}) as { source?: unknown }).source as PreviewSource | undefined;
      const invalid = validateSource(source, "source");
      if (invalid || !source) return reply.code(400).send({ error: invalid ?? "source is required" });

      const provider = (source.provider ?? "addon").toLowerCase();
      const profileId = request.profileId!;

      if (provider === "fetch") {
        const list = await prisma.list.findFirst({
          where: { id: source.listId as string, profileId },
          select: { id: true },
        });
        if (!list) return reply.code(404).send({ error: "List not found" });
        const types: ("movie" | "series")[] =
          source.mediaType === undefined || source.mediaType === null ? ["movie", "series"] : [stremioTypeOf(source.mediaType)];
        const metas = (await Promise.all(types.map((type) => getCustomListMetas(list.id, type, PREVIEW_LIMIT)))).flat();
        return { metas: metas.slice(0, PREVIEW_LIMIT) };
      }

      if (provider === "tmdb") {
        let tmdb;
        try {
          tmdb = await getTmdb();
        } catch {
          return reply.code(409).send({ error: "Add a TMDB API key in Settings to preview TMDB sources.", code: "tmdb_not_configured" });
        }
        const query: TmdbCollectionSourceQuery = {
          sourceType: String(source.tmdbSourceType).toUpperCase() as TmdbCollectionSourceQuery["sourceType"],
          tmdbId: typeof source.tmdbId === "number" ? source.tmdbId : null,
          mediaType: typeof source.mediaType === "string" && source.mediaType.toUpperCase() === "TV" ? "TV" : "MOVIE",
          sortBy: typeof source.sortBy === "string" ? source.sortBy : null,
          filters: (source.filters as TmdbCollectionSourceQuery["filters"]) ?? null,
        };
        const results = await tmdb.collectionSourcePage(query);
        const metas: StremioMetaPreview[] = results.slice(0, PREVIEW_LIMIT).map((item) => ({
          id: item.imdbId,
          type: item.type === MetadataType.movie ? "movie" : "series",
          name: item.name,
          poster: item.poster ?? undefined,
          year: item.year ?? undefined,
          description: item.description ?? undefined,
          genres: item.genres,
          rating: item.rating ?? undefined,
        }));
        return { metas };
      }

      if (provider === "trakt") {
        // The profile's own account's Trakt: a list preview reads from it.
        const traktOwner = await ownerForProfile(request.profileId!);
        if (!traktOwner || !(await isTraktConnected(traktOwner))) {
          return reply.code(409).send({ error: "Connect Trakt in Settings to preview Trakt lists.", code: "trakt_not_connected" });
        }
        const type = stremioTypeOf(source.mediaType);
        const client = await getTraktClient(traktOwner);
        const items = await client.fetchListItemsPage(
          source.traktListId as number,
          type === "movie" ? "movie" : "show",
          request.log
        );
        const ids = items
          .map((item) => (type === "movie" ? item.movie : item.show)?.ids?.imdb)
          .filter((id): id is string => typeof id === "string" && id.startsWith("tt"))
          .slice(0, PREVIEW_LIMIT);
        return { metas: await buildMetasFromIds(ids, type) };
      }

      return reply.code(422).send({
        error: "Addon catalogs are previewed from the addon itself.",
        code: "preview_in_browser",
      });
    }
  );
};

export default collectionsRoutes;
