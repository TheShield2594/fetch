import { STREAMING_PROVIDERS } from "../tmdb.js";
import type { FastifyPluginAsync } from "fastify";
import { getTmdb } from "../lib/tmdb-client.js";
import { getRegionSetting } from "../lib/settings.js";
import { ownerForRequest } from "../lib/integration-owner.js";
import { upsertMetadata } from "../lib/metadata.js";
import { getRpdbApiKey, applyRpdbToMetaList } from "../lib/rpdb.js";
import { trendingCacheGet, trendingCacheSet } from "../lib/cache.js";
import { getMetadataType } from "../lib/types.js";
import type { StremioMetaPreview, StremioMetaType } from "../lib/types.js";

const REGION_PATTERN = /^[A-Z]{2}$/;

const streamingRoutes: FastifyPluginAsync = async (app) => {
  app.get<{ Querystring: { type?: string; provider?: string; region?: string } }>(
    "/streaming",
    async (request, reply) => {
      const rawType = request.query.type ?? "movie";
      const type = getMetadataType(rawType);
      if (!type) return reply.code(400).send({ error: "type must be one of: movie, series" });

      const providerKey = request.query.provider?.toLowerCase();
      if (!providerKey || !STREAMING_PROVIDERS[providerKey]) {
        return reply.code(400).send({
          error: "provider is required",
          available: Object.entries(STREAMING_PROVIDERS).map(([key, val]) => ({ key, ...val })),
        });
      }

      // Part of the cache key, so it is held to what a region is: free text
      // here would let any caller fill the shared catalog cache with junk keys
      // and push everyone's real entries out.
      let region: string;
      if (request.query.region !== undefined) {
        region = request.query.region.trim().toUpperCase();
        if (!REGION_PATTERN.test(region)) {
          return reply.code(400).send({ error: "region must be a two-letter country code (e.g., 'US', 'GB')" });
        }
      } else {
        region = await getRegionSetting(await ownerForRequest(request));
      }
      const provider = STREAMING_PROVIDERS[providerKey];
      const cacheKey = `streaming:${providerKey}:${rawType}:${region}`;

      const [cached, rpdbKey] = await Promise.all([
        Promise.resolve(trendingCacheGet(cacheKey)),
        getRpdbApiKey(),
      ]);
      if (cached) return { metas: applyRpdbToMetaList(cached.data, rpdbKey), provider: provider.name };

      try {
        const tmdb = await getTmdb();
        const results = await tmdb.discoverByProvider(type, provider.id, region);
        await Promise.all(results.map((r) => upsertMetadata(r)));
        const metas: StremioMetaPreview[] = results.map((r) => ({
          id: r.imdbId,
          type: rawType as StremioMetaType,
          name: r.name,
          poster: r.poster ?? undefined,
          year: r.year ?? undefined,
          description: r.description ?? undefined,
          genres: r.genres,
          rating: r.rating ?? undefined,
        }));
        trendingCacheSet(cacheKey, { data: metas });
        return { metas: applyRpdbToMetaList(metas, rpdbKey), provider: provider.name };
      } catch (error) {
        request.log.error(error, "Streaming catalog fetch failed");
        return reply.code(500).send({ error: "Failed to fetch streaming catalog" });
      }
    }
  );

  app.get("/streaming/providers", async () => ({
    providers: Object.entries(STREAMING_PROVIDERS).map(([key, val]) => ({ key, ...val })),
  }));

  app.get<{ Querystring: { type?: string } }>("/anime", async (request, reply) => {
    const rawType = request.query.type ?? "series";
    const type = getMetadataType(rawType);
    if (!type) return reply.code(400).send({ error: "type must be one of: movie, series" });

    const cacheKey = `anime:${rawType}`;
    const [cached, rpdbKey] = await Promise.all([
      Promise.resolve(trendingCacheGet(cacheKey)),
      getRpdbApiKey(),
    ]);
    if (cached) return { metas: applyRpdbToMetaList(cached.data, rpdbKey) };

    try {
      const tmdb = await getTmdb();
      const results = await tmdb.discoverAnime(type);
      await Promise.all(results.map((r) => upsertMetadata(r)));
      const metas: StremioMetaPreview[] = results.map((r) => ({
        id: r.imdbId,
        type: rawType as StremioMetaType,
        name: r.name,
        poster: r.poster ?? undefined,
        year: r.year ?? undefined,
        description: r.description ?? undefined,
        genres: r.genres,
        rating: r.rating ?? undefined,
      }));
      trendingCacheSet(cacheKey, { data: metas });
      return { metas: applyRpdbToMetaList(metas, rpdbKey) };
    } catch (error) {
      request.log.error(error, "Anime catalog fetch failed");
      return reply.code(500).send({ error: "Failed to fetch anime catalog" });
    }
  });
};

export default streamingRoutes;
