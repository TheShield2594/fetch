import type { FastifyPluginAsync } from "fastify";
import { MetadataType, Prisma } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { resolveProfile } from "../lib/profile.js";
import { validateCollections, validateSource } from "../lib/collections.js";
import { etagFor, parseIfMatch } from "../lib/profile-document.js";
import { getTmdb } from "../lib/tmdb-client.js";
import { getTraktClient, isTraktConnected } from "../lib/trakt-client.js";
import { buildMetasFromIds, getCustomListMetas } from "../lib/stremio-helpers.js";
import { TMDB_ENTITY_KINDS, type TmdbCollectionSourceQuery, type TmdbEntityKind } from "../tmdb.js";
import type { StremioMetaPreview } from "../lib/types.js";
import { outboundScopeForProfile } from "../lib/integration-owner.js";
import { fetchManifest, manifestUrlForLog, normalizeManifestUrl } from "../lib/addon-manifest.js";
import { addonBase, fetchAddonCatalog } from "../lib/addon-catalog.js";

const PREVIEW_LIMIT = 20;

type PreviewSource = Record<string, unknown> & { provider?: string };

const stremioTypeOf = (mediaType: unknown): "movie" | "series" =>
  typeof mediaType === "string" && mediaType.toUpperCase() === "TV" ? "series" : "movie";

/**
 * Stores `collections` as the version after `expected`, but only while
 * `expected` is still the one stored. Returns the new version, or null when it
 * isn't. One statement either way — a conditional update, or an insert the
 * primary key refuses a second time — as `writeProfileDocument` does it.
 */
const writeCollectionsAtVersion = async (
  profileId: string,
  collections: Prisma.InputJsonValue,
  expected: number,
  updatedAt: Date
): Promise<number | null> => {
  if (expected === 0) {
    try {
      await prisma.profileCollections.create({ data: { profileId, collections, updatedAt, version: 1 } });
      return 1;
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") return null;
      throw error;
    }
  }
  const { count } = await prisma.profileCollections.updateMany({
    where: { profileId, version: expected },
    data: { collections, updatedAt, version: expected + 1 },
  });
  return count === 1 ? expected + 1 : null;
};

const collectionsRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/collections", async (request, reply) => {
    const row = await prisma.profileCollections.findUnique({ where: { profileId: request.profileId! } });
    const version = row?.version ?? 0;
    reply.header("ETag", etagFor(version)).header("Cache-Control", "no-store");
    return {
      collections: (row?.collections as unknown[] | undefined) ?? [],
      updatedAt: row?.updatedAt.toISOString() ?? null,
      version,
    };
  });

  // Replaces the whole list, as saving does on the TV. Every save is the next
  // version.
  //
  // A client that must not overwrite a save it hasn't seen sends `If-Match`
  // with the version it last read ("0" when there was none): a mismatch is a
  // 412 carrying what is there now, for it to merge with and try again. The
  // check and the write are one statement, so two TVs racing on one version
  // can't both win. That is what the TV and desktop apps do.
  //
  // Without the header the save goes through whatever is stored, which is what
  // an app from before versions existed still sends. `baseUpdatedAt` is the
  // older guard the website's own editor uses: a 409 on a mismatch.
  app.put<{ Body: unknown }>("/collections", async (request, reply) => {
    const body = (request.body ?? {}) as { collections?: unknown; baseUpdatedAt?: unknown };
    const result = validateCollections(body.collections);
    if (!result.ok) return reply.code(400).send({ error: result.error });

    const profileId = request.profileId!;
    const updatedAt = new Date();
    const collections = result.value as unknown as Prisma.InputJsonValue;
    const saved = (version: number) => {
      reply.header("ETag", etagFor(version));
      return { collections: result.value, updatedAt: updatedAt.toISOString(), version };
    };

    const ifMatch = parseIfMatch(request.headers["if-match"]);
    if (ifMatch === null) return reply.code(400).send({ error: "If-Match must be a version, like \"3\"" });
    if (ifMatch !== undefined) {
      const stored = await writeCollectionsAtVersion(profileId, collections, ifMatch, updatedAt);
      if (stored !== null) return saved(stored);

      const current = await prisma.profileCollections.findUnique({ where: { profileId } });
      const version = current?.version ?? 0;
      reply.header("ETag", etagFor(version));
      return reply.code(412).send({
        error: "These collections were changed somewhere else since you loaded them. Reload to see the latest.",
        code: "version_conflict",
        version,
        collections: (current?.collections as unknown[] | undefined) ?? [],
        updatedAt: current?.updatedAt.toISOString() ?? null,
      });
    }

    if (typeof body.baseUpdatedAt === "string" || body.baseUpdatedAt === null) {
      const current = await prisma.profileCollections.findUnique({ where: { profileId } });
      const currentStamp = current?.updatedAt.toISOString() ?? null;
      if (currentStamp !== body.baseUpdatedAt) {
        return reply.code(409).send({
          error: "These collections were changed somewhere else. Reload to see the latest.",
          code: "collections_conflict",
          collections: (current?.collections as unknown[] | undefined) ?? [],
          updatedAt: currentStamp,
          version: current?.version ?? 0,
        });
      }
    }

    const row = await prisma.profileCollections.upsert({
      where: { profileId },
      create: { profileId, collections, updatedAt, version: 1 },
      update: { collections, updatedAt, version: { increment: 1 } },
      select: { version: true },
    });
    return saved(row.version);
  });

  // People, studios and film collections by name, so the builder can pick a
  // TMDB source instead of asking for its numeric id.
  app.get<{ Querystring: { kind?: string; q?: string } }>(
    "/collections/tmdb-search",
    { config: { rateLimit: { max: 60, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const kind = request.query.kind as TmdbEntityKind | undefined;
      if (!kind || !TMDB_ENTITY_KINDS.includes(kind)) {
        return reply.code(400).send({ error: "kind must be one of: person, company, collection" });
      }
      const q = request.query.q?.trim() ?? "";
      if (!q || q.length > 100) return reply.code(400).send({ error: "q is required (at most 100 characters)" });
      // No key surfaces as 503 `tmdb_not_configured` from the app's error handler.
      const tmdb = await getTmdb();
      try {
        return { results: await tmdb.searchEntities(kind, q) };
      } catch (error) {
        request.log.warn({ err: error, kind }, "TMDB entity search failed");
        return reply.code(502).send({ error: "TMDB didn't answer. Try again in a moment." });
      }
    }
  );

  // An addon's manifest, for the builder's "Addon catalog" picker. Read here
  // rather than by the browser, whose Content-Security-Policy can't name every
  // host a community addon lives on; installing an addon on the TV list
  // (`POST /profiles/:id/addons`) reads it the same way, under the same rules.
  app.post<{ Body: unknown }>(
    "/collections/addon-manifest",
    { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const raw = ((request.body ?? {}) as { manifestUrl?: unknown }).manifestUrl;
      const base = typeof raw === "string" && raw.length <= 2048 ? addonBase(raw) : null;
      const manifestUrl = base ? normalizeManifestUrl(`${base}/manifest.json`) : null;
      if (!base || !manifestUrl) {
        return reply.code(400).send({ error: "That isn't an addon address. It should start with https:// (or stremio://)." });
      }
      const result = await fetchManifest(manifestUrl, await outboundScopeForProfile(request.profileId!));
      if (!result.ok) {
        if (result.detail) request.log.info({ addon: manifestUrlForLog(manifestUrl), detail: result.detail }, "Addon manifest refused");
        return reply.code(400).send({ error: result.reason, code: "manifest_invalid" });
      }
      const { id, name, catalogs } = result.manifest;
      return { manifest: { id, name, transportUrl: base, catalogs } };
    }
  );

  // The first page of one source, so the website's builder can show what a row
  // will hold before it is saved. An addon catalog is read from the address the
  // source carries (`addonUrl`): the server doesn't know the TV's addons.
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
        // No key surfaces as 503 `tmdb_not_configured` from the app's error handler.
        const tmdb = await getTmdb();
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
        // The profile's own Trakt: a list preview reads from it.
        const traktOwner = request.profileId!;
        if (!(await isTraktConnected(traktOwner))) {
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

      if (provider === "local") {
        return reply.code(422).send({ error: "This list is kept on the TV, so only the TV can show it.", code: "preview_on_tv" });
      }

      const base = typeof source.addonUrl === "string" ? addonBase(source.addonUrl) : null;
      if (!base) {
        return reply.code(422).send({
          error: "Enter the addon's manifest address to preview its catalog.",
          code: "addon_url_required",
        });
      }
      const result = await fetchAddonCatalog(
        base,
        {
          type: source.type as string,
          catalogId: source.catalogId as string,
          genre: typeof source.genre === "string" ? source.genre : null,
        },
        await outboundScopeForProfile(profileId),
        PREVIEW_LIMIT
      );
      if (!result.ok) {
        if (result.detail) {
          request.log.info({ addon: manifestUrlForLog(`${base}/manifest.json`), detail: result.detail }, "Addon catalog preview failed");
        }
        return reply.code(502).send({ error: result.reason, code: "addon_unreachable" });
      }
      return { metas: result.metas };
    }
  );
};

export default collectionsRoutes;
