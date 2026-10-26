import type { FastifyBaseLogger, FastifyPluginAsync } from "fastify";
import { Prisma, type ProfileAddon } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { resolveProfileParam } from "../lib/profile.js";
import {
  type CachedManifest,
  type ManifestResult,
  configureUrlFor,
  fetchManifest,
  manifestUrlForLog,
  normalizeManifestUrl,
  parseManifest,
  readCachedManifest,
} from "../lib/addon-manifest.js";
import { MAX_URL_LENGTH, NON_BLANK } from "../lib/request-schema.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import { pruneHomeLayoutForAddons } from "../lib/home-layout.js";
import { getProfileDocument, touchAddonList } from "../lib/profile-document.js";

/**
 * Each profile's installed Stremio addons, in the order the TV lists them.
 *
 * The website edits the list one change at a time (add, toggle, reorder,
 * remove); the TV syncs it as a whole with `PUT /profiles/:id/addons`, which is
 * how its `AddonSyncService` already treats the list — a full replace.
 *
 * A manifest URL can carry the addon's credentials, so it is only ever logged
 * through `manifestUrlForLog`, and an error message never repeats it.
 */

/** More than any TV would run; a ceiling so the whole-list replace stays bounded. */
const MAX_ADDONS_PER_PROFILE = 100;
/** How many manifests a whole-list replace fetches at once. */
const MANIFEST_FETCH_CONCURRENCY = 4;

type AddonParams = { id: string; addonId: string };

const toAddonData = (row: ProfileAddon) => {
  const manifest = readCachedManifest(row.manifest);
  return {
    id: row.id,
    manifestUrl: row.manifestUrl,
    position: row.position,
    enabled: row.enabled,
    addonId: row.addonId,
    name: row.name,
    version: row.version,
    description: row.description,
    logo: row.logo,
    types: manifest?.types ?? [],
    resources: manifest?.resources ?? [],
    catalogs: manifest?.catalogs ?? [],
    configurable: manifest?.configurable ?? false,
    configureUrl: manifest?.configurable ? configureUrlFor(row.manifestUrl) : null,
    // False when the manifest has never been fetched: the addon is kept and
    // named by its host, but its catalogs are unknown.
    reachable: manifest !== null,
    manifestFetchedAt: row.manifestFetchedAt?.toISOString() ?? null,
  };
};

const listAddons = (profileId: string) =>
  prisma.profileAddon.findMany({ where: { profileId }, orderBy: [{ position: "asc" }, { createdAt: "asc" }] });

/** The columns a manifest fills in. */
const manifestColumns = (manifest: CachedManifest, fetchedAt: Date) => ({
  addonId: manifest.id,
  name: manifest.name,
  version: manifest.version,
  description: manifest.description,
  logo: manifest.logo,
  manifest: manifest as unknown as Prisma.InputJsonValue,
  manifestFetchedAt: fetchedAt,
});

/** A name for an addon whose manifest couldn't be read: the one given, or its host. */
const fallbackName = (manifestUrl: string, given?: string | null): string => {
  if (given?.trim()) return given.trim().slice(0, 300);
  try {
    return new URL(manifestUrl).host;
  } catch {
    return "Addon";
  }
};

/**
 * The server's own fetch first; a manifest the caller sent is the fallback.
 *
 * The fallback exists for addons this server can't reach but the caller can:
 * the website's own addon on a public URL the API container can't resolve, or
 * an addon the TV reaches over a network the server isn't on. It is parsed by
 * the same rules, so it is never stored as anything but a valid manifest.
 */
const resolveManifest = async (
  manifestUrl: string,
  supplied: unknown,
  log: FastifyBaseLogger
): Promise<ManifestResult> => {
  const fetched = await fetchManifest(manifestUrl);
  if (fetched.ok || supplied === undefined || supplied === null) {
    if (!fetched.ok) log.info({ addon: manifestUrlForLog(manifestUrl), reason: fetched.reason }, "Addon manifest rejected");
    return fetched;
  }
  const parsed = parseManifest(supplied);
  return parsed.ok ? parsed : fetched;
};

const addAddonBodySchema = {
  type: "object",
  required: ["manifestUrl"],
  properties: {
    manifestUrl: { type: "string", pattern: NON_BLANK, maxLength: MAX_URL_LENGTH },
    manifest: { type: "object" },
  },
} as const;

const patchAddonBodySchema = {
  type: "object",
  properties: {
    enabled: { type: "boolean" },
    manifestUrl: { type: "string", pattern: NON_BLANK, maxLength: MAX_URL_LENGTH },
    manifest: { type: "object" },
  },
} as const;

const orderBodySchema = {
  type: "object",
  required: ["ids"],
  properties: {
    ids: { type: "array", maxItems: MAX_ADDONS_PER_PROFILE, items: { type: "string", maxLength: 36 } },
  },
} as const;

const replaceBodySchema = {
  type: "object",
  required: ["addons"],
  properties: {
    addons: {
      type: "array",
      maxItems: MAX_ADDONS_PER_PROFILE,
      items: {
        type: "object",
        required: ["manifestUrl"],
        properties: {
          manifestUrl: { type: "string", pattern: NON_BLANK, maxLength: MAX_URL_LENGTH },
          enabled: { type: "boolean" },
          name: { type: "string", maxLength: 300 },
          manifest: { type: "object" },
        },
      },
    },
  },
} as const;

type ReplaceEntry = { manifestUrl: string; enabled?: boolean; name?: string; manifest?: unknown };

/** Runs `work` over `items`, at most `limit` at a time, keeping their order. */
const mapLimited = async <T, R>(items: T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> => {
  const results = new Array<R>(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await work(items[index]!);
    }
  });
  await Promise.all(workers);
  return results;
};

const profileAddonsRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfileParam);

  app.get<{ Params: { id: string } }>("/profiles/:id/addons", async (request) => {
    const [rows, marker] = await Promise.all([
      listAddons(request.profileId!),
      getProfileDocument(request.profileId!, "addon-list"),
    ]);
    // Null until the list is first changed from anywhere, which is how a TV
    // pairing for the first time knows to hand the website its own addons.
    return { addons: rows.map(toAddonData), updatedAt: marker?.updatedAt.toISOString() ?? null };
  });

  /** Install one addon by manifest URL, at the end of the list. */
  app.post<{ Params: { id: string }; Body: { manifestUrl: string; manifest?: unknown } }>(
    "/profiles/:id/addons",
    { schema: { body: addAddonBodySchema }, config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const profileId = request.profileId!;
      const manifestUrl = normalizeManifestUrl(request.body.manifestUrl);
      if (!manifestUrl) {
        return reply.code(400).send({
          error: "That isn't an addon manifest address. It should start with https:// (or stremio://) and end in /manifest.json.",
        });
      }

      const existing = await prisma.profileAddon.findMany({ where: { profileId }, select: { manifestUrl: true, position: true } });
      if (existing.some((row) => row.manifestUrl === manifestUrl)) {
        return reply.code(409).send({ error: "That addon is already installed on this profile." });
      }
      if (existing.length >= MAX_ADDONS_PER_PROFILE) {
        return reply.code(400).send({ error: `A profile can have at most ${MAX_ADDONS_PER_PROFILE} addons.` });
      }

      const result = await resolveManifest(manifestUrl, request.body.manifest, request.log);
      if (!result.ok) return reply.code(400).send({ error: result.reason, code: "manifest_invalid" });

      const position = existing.reduce((max, row) => Math.max(max, row.position + 1), 0);
      try {
        const created = await prisma.profileAddon.create({
          data: { profileId, manifestUrl, position, enabled: true, ...manifestColumns(result.manifest, new Date()) },
        });
        await touchAddonList(profileId);
        return reply.code(201).send({ addon: toAddonData(created) });
      } catch (error) {
        // Two adds of the same URL racing past the check above.
        if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
          return reply.code(409).send({ error: "That addon is already installed on this profile." });
        }
        throw error;
      }
    }
  );

  /**
   * Turn an addon on or off, or point it at a new manifest URL — what an addon's
   * configure page hands back after its settings change.
   */
  app.patch<{ Params: AddonParams; Body: { enabled?: boolean; manifestUrl?: string; manifest?: unknown } }>(
    "/profiles/:id/addons/:addonId",
    { schema: { body: patchAddonBodySchema } },
    async (request, reply) => {
      const { addonId } = request.params;
      if (!UUID_V4_PATTERN.test(addonId)) return reply.code(400).send({ error: "addonId must be a valid UUID" });
      const row = await prisma.profileAddon.findFirst({ where: { id: addonId, profileId: request.profileId! } });
      if (!row) return reply.code(404).send({ error: "Addon not found" });

      const data: Prisma.ProfileAddonUpdateInput = {};
      if (request.body.enabled !== undefined) data.enabled = request.body.enabled;

      if (request.body.manifestUrl !== undefined) {
        const manifestUrl = normalizeManifestUrl(request.body.manifestUrl);
        if (!manifestUrl) {
          return reply.code(400).send({ error: "That isn't an addon manifest address. It should end in /manifest.json." });
        }
        if (manifestUrl !== row.manifestUrl) {
          const result = await resolveManifest(manifestUrl, request.body.manifest, request.log);
          if (!result.ok) return reply.code(400).send({ error: result.reason, code: "manifest_invalid" });
          Object.assign(data, { manifestUrl, ...manifestColumns(result.manifest, new Date()) });
        }
      }

      if (Object.keys(data).length === 0) {
        return reply.code(400).send({ error: "enabled or manifestUrl must be provided" });
      }

      try {
        const updated = await prisma.profileAddon.update({ where: { id: row.id }, data });
        await touchAddonList(row.profileId);
        return { addon: toAddonData(updated) };
      } catch (error) {
        if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
          return reply.code(409).send({ error: "That addon is already installed on this profile." });
        }
        throw error;
      }
    }
  );

  /** Fetch the manifest again, for a new version or catalogs the addon added. */
  app.post<{ Params: AddonParams }>(
    "/profiles/:id/addons/:addonId/refresh",
    { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const { addonId } = request.params;
      if (!UUID_V4_PATTERN.test(addonId)) return reply.code(400).send({ error: "addonId must be a valid UUID" });
      const row = await prisma.profileAddon.findFirst({ where: { id: addonId, profileId: request.profileId! } });
      if (!row) return reply.code(404).send({ error: "Addon not found" });

      const result = await fetchManifest(row.manifestUrl);
      if (!result.ok) {
        request.log.info({ addon: manifestUrlForLog(row.manifestUrl), reason: result.reason }, "Addon manifest refresh failed");
        return reply.code(502).send({ error: result.reason, code: "manifest_unreachable" });
      }
      const updated = await prisma.profileAddon.update({
        where: { id: row.id },
        data: manifestColumns(result.manifest, new Date()),
      });
      return { addon: toAddonData(updated) };
    }
  );

  app.delete<{ Params: AddonParams }>("/profiles/:id/addons/:addonId", async (request, reply) => {
    const { addonId } = request.params;
    if (!UUID_V4_PATTERN.test(addonId)) return reply.code(400).send({ error: "addonId must be a valid UUID" });
    const profileId = request.profileId!;
    const { count } = await prisma.profileAddon.deleteMany({ where: { id: addonId, profileId } });
    if (count === 0) return reply.code(404).send({ error: "Addon not found" });
    await touchAddonList(profileId);
    // The removed addon's rows leave the Home layout with it, so the TV doesn't
    // keep a gap where they were.
    await pruneHomeLayoutForAddons(profileId);
    return reply.code(204).send();
  });

  /** A new order for the installed addons: every id, once each. */
  app.put<{ Params: { id: string }; Body: { ids: string[] } }>(
    "/profiles/:id/addons/order",
    { schema: { body: orderBodySchema } },
    async (request, reply) => {
      const profileId = request.profileId!;
      const { ids } = request.body;
      const rows = await prisma.profileAddon.findMany({ where: { profileId }, select: { id: true } });
      const known = new Set(rows.map((row) => row.id));
      if (new Set(ids).size !== ids.length || ids.length !== known.size || ids.some((id) => !known.has(id))) {
        return reply.code(400).send({ error: "ids must list every installed addon exactly once" });
      }

      await prisma.$transaction(
        ids.map((id, position) => prisma.profileAddon.update({ where: { id }, data: { position } }))
      );
      await touchAddonList(profileId);
      return { addons: (await listAddons(profileId)).map(toAddonData) };
    }
  );

  /**
   * Replace the whole list, in order — the TV's sync. Addons already installed
   * keep their cached manifest; new ones are fetched (or read from the
   * `manifest` the TV sent), and one that can't be read is still kept, named by
   * the `name` sent or its host, so a TV's list survives the round trip intact.
   */
  app.put<{ Params: { id: string }; Body: { addons: ReplaceEntry[] } }>(
    "/profiles/:id/addons",
    { schema: { body: replaceBodySchema }, config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const profileId = request.profileId!;

      const entries: (ReplaceEntry & { manifestUrl: string })[] = [];
      const seen = new Set<string>();
      for (const [index, entry] of request.body.addons.entries()) {
        const manifestUrl = normalizeManifestUrl(entry.manifestUrl);
        if (!manifestUrl) {
          return reply.code(400).send({ error: `addons.${index}.manifestUrl isn't an addon manifest address` });
        }
        // A list naming the same addon twice keeps its first position, as the
        // TV's own list does.
        if (seen.has(manifestUrl)) continue;
        seen.add(manifestUrl);
        entries.push({ ...entry, manifestUrl });
      }

      const existing = await prisma.profileAddon.findMany({ where: { profileId } });
      const byUrl = new Map(existing.map((row) => [row.manifestUrl, row]));
      const fetchedAt = new Date();

      const fresh = await mapLimited(
        entries.filter((entry) => !byUrl.has(entry.manifestUrl)),
        MANIFEST_FETCH_CONCURRENCY,
        async (entry) => [entry.manifestUrl, await resolveManifest(entry.manifestUrl, entry.manifest, request.log)] as const
      );
      const manifestsByUrl = new Map(fresh);

      await prisma.$transaction([
        prisma.profileAddon.deleteMany({ where: { profileId, manifestUrl: { notIn: entries.map((e) => e.manifestUrl) } } }),
        ...entries.map((entry, position) => {
          const enabled = entry.enabled ?? true;
          const current = byUrl.get(entry.manifestUrl);
          if (current) {
            return prisma.profileAddon.update({ where: { id: current.id }, data: { position, enabled } });
          }
          const result = manifestsByUrl.get(entry.manifestUrl);
          const columns = result?.ok
            ? manifestColumns(result.manifest, fetchedAt)
            : { name: fallbackName(entry.manifestUrl, entry.name) };
          return prisma.profileAddon.create({
            data: { profileId, manifestUrl: entry.manifestUrl, position, enabled, ...columns },
          });
        }),
      ]);

      await touchAddonList(profileId);
      await pruneHomeLayoutForAddons(profileId);
      return { addons: (await listAddons(profileId)).map(toAddonData) };
    }
  );
};

export default profileAddonsRoutes;
