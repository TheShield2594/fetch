import type { FastifyPluginAsync } from "fastify";
import type { Prisma } from "@prisma/client";
import { resolveProfileParam } from "../lib/profile.js";
import {
  etagFor,
  getProfileDocument,
  opaqueDocumentProblem,
  requireIfMatch,
  sendVersionConflict,
  writeProfileDocument,
} from "../lib/profile-document.js";
import {
  MAX_HOME_ROWS,
  MAX_ROW_KEY_LENGTH,
  isValidRowKey,
  loadHomeRowContext,
  mergeHomeLayout,
  pruneRows,
  readStoredRows,
  type StoredRow,
} from "../lib/home-layout.js";

/**
 * The two per-profile documents the TV syncs as a whole: its settings and its
 * Home layout. Both are read with a version and written with `If-Match` naming
 * it — see lib/profile-document.ts for why.
 */

/**
 * The TV's settings blob is a few KB today; this leaves room for it to grow
 * without letting a document the size of a database dump through.
 */
const MAX_TV_SETTINGS_BYTES = 256 * 1024;

const tvSettingsBodySchema = {
  type: "object",
  required: ["settings"],
  properties: { settings: { type: "object" } },
} as const;

const homeLayoutBodySchema = {
  type: "object",
  required: ["rows"],
  properties: {
    rows: {
      type: "array",
      maxItems: MAX_HOME_ROWS,
      items: {
        type: "object",
        required: ["key"],
        properties: {
          key: { type: "string", maxLength: MAX_ROW_KEY_LENGTH },
          hidden: { type: "boolean" },
          title: { type: "string", maxLength: 300 },
        },
      },
    },
  },
} as const;

const profileDocumentsRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfileParam);

  // ─── TV settings ───
  //
  // An opaque document: validated for size and shape, never interpreted. The
  // TV app owns its schema (today `{ version, features: { <feature>: { <key>:
  // { type, value } } } }`), so a TV on a newer version can add settings
  // without this server learning about them, and the website's forms edit the
  // keys they know while carrying the rest through untouched.

  app.get<{ Params: { id: string } }>("/profiles/:id/settings/tv", async (request, reply) => {
    const stored = await getProfileDocument(request.profileId!, "tv-settings");
    const version = stored?.version ?? 0;
    reply.header("ETag", etagFor(version)).header("Cache-Control", "no-store");
    return { settings: stored?.document ?? {}, version, updatedAt: stored?.updatedAt.toISOString() ?? null };
  });

  app.put<{ Params: { id: string }; Body: { settings: Record<string, unknown> } }>(
    "/profiles/:id/settings/tv",
    { schema: { body: tvSettingsBodySchema } },
    async (request, reply) => {
      const expected = requireIfMatch(request.headers["if-match"], reply);
      if (expected === null) return reply;

      const problem = opaqueDocumentProblem(request.body.settings, MAX_TV_SETTINGS_BYTES);
      if (problem) return reply.code(400).send({ error: `settings ${problem}` });

      const result = await writeProfileDocument(
        request.profileId!,
        "tv-settings",
        request.body.settings as Prisma.InputJsonValue,
        expected
      );
      if (!result.ok) return sendVersionConflict(reply, result.current);

      reply.header("ETag", etagFor(result.stored.version));
      return {
        settings: result.stored.document,
        version: result.stored.version,
        updatedAt: result.stored.updatedAt.toISOString(),
      };
    }
  );

  // ─── Home layout ───

  app.get<{ Params: { id: string } }>("/profiles/:id/home-layout", async (request, reply) => {
    const profileId = request.profileId!;
    const [stored, context] = await Promise.all([
      getProfileDocument(profileId, "home-layout"),
      loadHomeRowContext(profileId),
    ]);
    const version = stored?.version ?? 0;
    reply.header("ETag", etagFor(version)).header("Cache-Control", "no-store");
    return {
      rows: mergeHomeLayout(readStoredRows(stored?.document), context),
      version,
      updatedAt: stored?.updatedAt.toISOString() ?? null,
    };
  });

  /**
   * The whole order, as it should appear. Rows left out aren't hidden — they go
   * back to being "not placed yet" and reappear at the end — so send every row
   * with `hidden` set to hide one.
   */
  app.put<{ Params: { id: string }; Body: { rows: StoredRow[] } }>(
    "/profiles/:id/home-layout",
    { schema: { body: homeLayoutBodySchema } },
    async (request, reply) => {
      const expected = requireIfMatch(request.headers["if-match"], reply);
      if (expected === null) return reply;

      const invalid = request.body.rows.findIndex((row) => !isValidRowKey(row.key));
      if (invalid !== -1) {
        return reply.code(400).send({ error: `rows.${invalid}.key isn't a row key, like "addon:<id>:<type>:<catalog>" or "list:<id>"` });
      }

      const profileId = request.profileId!;
      const context = await loadHomeRowContext(profileId);
      const rows = pruneRows(readStoredRows({ rows: request.body.rows }), context);

      const result = await writeProfileDocument(
        profileId,
        "home-layout",
        { rows },
        expected
      );
      if (!result.ok) return sendVersionConflict(reply, result.current);

      reply.header("ETag", etagFor(result.stored.version));
      return {
        rows: mergeHomeLayout(rows, context),
        version: result.stored.version,
        updatedAt: result.stored.updatedAt.toISOString(),
      };
    }
  );
};

export default profileDocumentsRoutes;
