import { ListItemType, ListKind } from "@prisma/client";
import type { FastifyPluginAsync } from "fastify";
import { prisma } from "../lib/prisma.js";
import { upsertMetadata } from "../lib/metadata.js";
import { getRpdbApiKey, withRpdbPoster } from "../lib/rpdb.js";
import { getMetadataType } from "../lib/types.js";
import { getTmdb } from "../lib/tmdb-client.js";
import { BROWSE_GENRES, type BrowseSort } from "../tmdb.js";
import { resolveProfile } from "../lib/profile.js";

type Decoratable = Awaited<ReturnType<Awaited<ReturnType<typeof getTmdb>>["search"]>>[number];

/**
 * Search results as the web client draws them: stored as metadata rows (so a
 * title added from Search has one), with the profile's list membership and the
 * RPDB poster when that is configured.
 */
async function decorate(results: Decoratable[], profileId: string) {
  await Promise.allSettled(results.map((result) => upsertMetadata(result)));

  const imdbIds = results.map((r) => r.imdbId);
  const resultTypes = [...new Set(results.map((r) => r.type as ListItemType))];

  const [listItems, rpdbKey] = await Promise.all([
    results.length === 0
      ? Promise.resolve([])
      : prisma.listItem.findMany({
          where: {
            imdbId: { in: imdbIds },
            type: { in: resultTypes },
            list: { profileId },
          },
          include: { list: { select: { id: true, name: true, kind: true } } },
        }),
    getRpdbApiKey(),
  ]);

  const listInfoKey = (imdbId: string, mediaType: string) => `${mediaType}:${imdbId}`;
  const listInfoByKey = new Map<string, { inWatchlist: boolean; inCollection: boolean; lists: string[] }>();
  for (const item of listItems) {
    const key = listInfoKey(item.imdbId, item.type);
    let info = listInfoByKey.get(key);
    if (!info) {
      info = { inWatchlist: false, inCollection: false, lists: [] };
      listInfoByKey.set(key, info);
    }
    if (item.list.kind === ListKind.watchlist) info.inWatchlist = true;
    if (item.list.kind === ListKind.collection) info.inCollection = true;
    info.lists.push(item.list.id);
  }

  return results.map((result) => {
    const info = listInfoByKey.get(listInfoKey(result.imdbId, result.type));
    return {
      imdbId: result.imdbId,
      type: result.type,
      name: result.name,
      year: result.year,
      poster: withRpdbPoster(result.imdbId, result.poster, rpdbKey),
      description: result.description,
      genres: result.genres,
      rating: result.rating,
      inWatchlist: info?.inWatchlist ?? false,
      inCollection: info?.inCollection ?? false,
      lists: info?.lists ?? [],
    };
  });
}

const BROWSE_SORTS: readonly BrowseSort[] = ["relevance", "rating", "year_desc", "year_asc", "title"];

/** A finite number from a query-string value, or null for absent, empty or junk. */
const numberParam = (raw: string | undefined): number | null => {
  if (raw === undefined || raw.trim() === "") return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
};

const searchRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get<{ Querystring: { type?: string; query?: string; q?: string } }>(
    "/search",
    async (request, reply) => {
      const rawType = request.query.type ?? "all";
      const isAll = rawType === "all";
      const type = isAll ? null : getMetadataType(rawType);
      if (!isAll && !type) {
        return reply.code(400).send({ error: "type must be one of: movie, series, all" });
      }

      const query = (request.query.q ?? request.query.query)?.trim();
      if (!query) {
        return reply.code(400).send({ error: "q or query is required" });
      }

      let tmdb: Awaited<ReturnType<typeof getTmdb>>;
      try {
        tmdb = await getTmdb();
      } catch (error) {
        request.log.error(error, "TMDB client initialization failed");
        return reply.code(500).send({ error: "TMDB integration is not configured" });
      }

      let allResults;
      try {
        allResults = isAll
          ? await tmdb.searchMulti(query)
          : await tmdb.search(type!, query);
      } catch (error) {
        request.log.error(error, "TMDB search failed");
        return reply.code(502).send({ error: "TMDB search failed" });
      }

      const results = allResults.slice(0, 20);
      return decorate(results, request.profileId!);
    }
  );
  /**
   * One page of Search, with its filters applied by TMDB rather than to what
   * one page happened to return. See `TmdbClient.browse` for how a query, a
   * filter set, or both are answered. One media type per call: the client
   * asks for films and shows side by side and merges them.
   */
  app.get<{
    Querystring: {
      type?: string;
      q?: string;
      genre?: string;
      yearMin?: string;
      yearMax?: string;
      ratingMin?: string;
      sort?: string;
      page?: string;
    };
  }>("/search/browse", async (request, reply) => {
    const type = getMetadataType(request.query.type ?? "");
    if (!type) return reply.code(400).send({ error: "type must be one of: movie, series" });

    const query = request.query.q?.trim() || null;
    const genre = request.query.genre?.trim() || null;
    if (genre && !(genre in BROWSE_GENRES)) return reply.code(400).send({ error: `Unknown genre: ${genre}` });
    const sort = (request.query.sort ?? "relevance") as BrowseSort;
    if (!BROWSE_SORTS.includes(sort)) return reply.code(400).send({ error: "Unknown sort" });

    const yearMin = numberParam(request.query.yearMin);
    const yearMax = numberParam(request.query.yearMax);
    const ratingMin = numberParam(request.query.ratingMin);
    const page = numberParam(request.query.page) ?? 1;

    let tmdb: Awaited<ReturnType<typeof getTmdb>>;
    try {
      tmdb = await getTmdb();
    } catch (error) {
      request.log.error(error, "TMDB client initialization failed");
      return reply.code(500).send({ error: "TMDB integration is not configured" });
    }

    let found;
    try {
      found = await tmdb.browse(type, { query, genre, yearMin, yearMax, ratingMin, sort, page });
    } catch (error) {
      request.log.error(error, "TMDB browse failed");
      return reply.code(502).send({ error: "TMDB search failed" });
    }

    return {
      results: await decorate(found.results, request.profileId!),
      page: found.page,
      nextPage: found.nextPage,
    };
  });
};

export default searchRoutes;
