import type { FastifyReply } from "fastify";
import { Prisma } from "@prisma/client";
import { prisma } from "./prisma.js";

/**
 * Per-profile JSON documents that the website stores for the TV: its settings
 * and its Home row order.
 *
 * Every write replaces the whole document and has to say which version it was
 * based on, through `If-Match`. A TV that pulled version 3, and a browser that
 * saved version 4 in the meantime, therefore can't push its stale copy over the
 * browser's: the TV gets a 412, pulls, merges and tries again. Version 0 means
 * "there is no document yet", so the first write sends `If-Match: "0"`.
 */

export type ProfileDocumentKind = "tv-settings" | "home-layout";

export type StoredDocument = {
  document: Prisma.JsonValue;
  version: number;
  updatedAt: Date;
};

export const etagFor = (version: number): string => `"${version}"`;

export const getProfileDocument = async (
  profileId: string,
  kind: ProfileDocumentKind
): Promise<StoredDocument | null> => {
  const row = await prisma.profileDocument.findUnique({
    where: { profileId_kind: { profileId, kind } },
    select: { document: true, version: true, updatedAt: true },
  });
  return row;
};

/**
 * The version an `If-Match` header names: `"3"`, `W/"3"` or a bare `3`.
 * `undefined` when the header is absent, `null` when it is there but isn't a
 * version this API issued. `*` is deliberately not accepted — "whatever is
 * there" is exactly the blind overwrite the header exists to prevent.
 */
export const parseIfMatch = (header: string | string[] | undefined): number | null | undefined => {
  const value = Array.isArray(header) ? header[0] : header;
  if (value === undefined) return undefined;
  const match = /^\s*(?:W\/)?"?(\d{1,9})"?\s*$/.exec(value);
  return match ? Number(match[1]) : null;
};

/**
 * Answers the request for a missing or malformed `If-Match`, and returns the
 * version otherwise. A caller replies with whatever this sent when it is null.
 */
export const requireIfMatch = (header: string | string[] | undefined, reply: FastifyReply): number | null => {
  const version = parseIfMatch(header);
  if (version === undefined) {
    void reply.code(428).send({
      error: "If-Match is required: send the version you last read, or \"0\" if there was none",
      code: "if_match_required",
    });
    return null;
  }
  if (version === null) {
    void reply.code(400).send({ error: "If-Match must be a version, like \"3\"" });
    return null;
  }
  return version;
};

export type WriteResult =
  | { ok: true; stored: StoredDocument }
  | { ok: false; current: StoredDocument | null };

/**
 * Stores `document` as the next version, but only if `expectedVersion` is still
 * the current one. The check and the write are one statement — a conditional
 * update, or an insert the primary key refuses a second time — so two writers
 * racing on the same version can't both succeed.
 */
export const writeProfileDocument = async (
  profileId: string,
  kind: ProfileDocumentKind,
  document: Prisma.InputJsonValue,
  expectedVersion: number
): Promise<WriteResult> => {
  const updatedAt = new Date();

  if (expectedVersion === 0) {
    try {
      const created = await prisma.profileDocument.create({
        data: { profileId, kind, document, version: 1, updatedAt },
        select: { document: true, version: true, updatedAt: true },
      });
      return { ok: true, stored: created };
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
        return { ok: false, current: await getProfileDocument(profileId, kind) };
      }
      throw error;
    }
  }

  const nextVersion = expectedVersion + 1;
  const { count } = await prisma.profileDocument.updateMany({
    where: { profileId, kind, version: expectedVersion },
    data: { document, version: nextVersion, updatedAt },
  });
  if (count === 0) return { ok: false, current: await getProfileDocument(profileId, kind) };
  return { ok: true, stored: { document: document as Prisma.JsonValue, version: nextVersion, updatedAt } };
};

/** The 412 a stale write gets, carrying the version to re-read. */
export const sendVersionConflict = (reply: FastifyReply, current: StoredDocument | null) => {
  const version = current?.version ?? 0;
  reply.header("ETag", etagFor(version));
  return reply.code(412).send({
    error: "This was changed somewhere else since you loaded it. Reload to see the latest version.",
    code: "version_conflict",
    version,
  });
};

// ─── Shape checks for documents the API doesn't interpret ───

/** How deep an opaque document may nest; the TV's own settings blob is three. */
const MAX_DEPTH = 12;

const depthOf = (value: unknown, depth = 0): number => {
  if (depth > MAX_DEPTH) return depth;
  if (Array.isArray(value)) return value.reduce<number>((max, entry) => Math.max(max, depthOf(entry, depth + 1)), depth);
  if (typeof value === "object" && value !== null) {
    return Object.values(value).reduce<number>((max, entry) => Math.max(max, depthOf(entry, depth + 1)), depth);
  }
  return depth;
};

/**
 * Why `value` can't be stored as an opaque document, or null when it can: it
 * has to be a JSON object, no larger than `maxBytes` serialized, and nested no
 * deeper than a settings document plausibly would be.
 */
export const opaqueDocumentProblem = (value: unknown, maxBytes: number): string | null => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return "must be a JSON object";
  }
  if (Buffer.byteLength(JSON.stringify(value), "utf8") > maxBytes) {
    return `must be at most ${Math.round(maxBytes / 1024)} KB`;
  }
  if (depthOf(value) > MAX_DEPTH) {
    return `must not nest deeper than ${MAX_DEPTH} levels`;
  }
  return null;
};
