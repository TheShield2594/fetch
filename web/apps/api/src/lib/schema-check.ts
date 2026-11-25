import { readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { FastifyBaseLogger } from "fastify";
import { prisma } from "./prisma.js";

/**
 * Compares the database's applied migrations with the ones baked into this
 * image (#639).
 *
 * Two mismatches matter, and they are not equally bad:
 *
 * - **The database is ahead of the image**: it has migrations this build has
 *   never heard of. That is an image rolled back without restoring the
 *   database. The code reads and writes a schema it wasn't written for — a
 *   dropped column it still selects, a NOT NULL it doesn't fill — and the
 *   failures surface as unrelated 500s. The API refuses to start.
 * - **The image is ahead of the database**: migrations that haven't been
 *   applied yet. The `migrate` service normally runs first, so this is a
 *   startup race or a skipped migrate step. Logged loudly, not fatal, because
 *   `prisma migrate deploy` may be running right now.
 *
 * `FETCH_ALLOW_SCHEMA_MISMATCH=true` turns the refusal into a warning for the
 * operator who knows what they are doing (a hand-applied hotfix, say).
 */

export type SchemaCheck = {
  /** Applied in the database but unknown to this image. */
  unknownToImage: string[];
  /** In this image but not applied (or not finished) in the database. */
  pending: string[];
};

const MIGRATIONS_DIR = fileURLToPath(new URL("../../prisma/migrations", import.meta.url));

/** The migration folders this image carries, or null when the folder isn't there. */
export const bakedMigrations = (dir: string = MIGRATIONS_DIR): string[] | null => {
  try {
    return readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort();
  } catch {
    return null;
  }
};

export const compareMigrations = (baked: string[], applied: string[]): SchemaCheck => {
  const bakedSet = new Set(baked);
  const appliedSet = new Set(applied);
  return {
    unknownToImage: applied.filter((name) => !bakedSet.has(name)).sort(),
    pending: baked.filter((name) => !appliedSet.has(name)).sort(),
  };
};

type MigrationRow = { migration_name: string };

/** Finished, not rolled back: what `prisma migrate deploy` counts as applied. */
const appliedMigrations = async (): Promise<string[]> => {
  const rows = await prisma.$queryRaw<MigrationRow[]>`
    SELECT migration_name FROM "_prisma_migrations"
    WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL
  `;
  return rows.map((row) => row.migration_name);
};

const MAX_LISTED = 5;
const list = (names: string[]): string =>
  names.length > MAX_LISTED ? `${names.slice(0, MAX_LISTED).join(", ")} and ${names.length - MAX_LISTED} more` : names.join(", ");

/**
 * Runs the comparison and logs it. Resolves to `false` when the API should not
 * start. Anything that stops the check itself running (no migrations folder in
 * a dev checkout, no `_prisma_migrations` table on a `db push` database) is a
 * warning, never a refusal.
 */
export const verifySchemaMatchesImage = async (
  log: FastifyBaseLogger,
  env: NodeJS.ProcessEnv = process.env,
  deps: { baked?: () => string[] | null; applied?: () => Promise<string[]> } = {},
): Promise<boolean> => {
  const baked = (deps.baked ?? bakedMigrations)();
  if (!baked) {
    log.warn("Skipping the schema check: this build has no prisma/migrations folder");
    return true;
  }

  let applied: string[];
  try {
    applied = await (deps.applied ?? appliedMigrations)();
  } catch (error) {
    log.warn({ err: error }, "Skipping the schema check: couldn't read _prisma_migrations");
    return true;
  }

  const { unknownToImage, pending } = compareMigrations(baked, applied);

  if (pending.length > 0) {
    log.warn(
      `The database is missing ${pending.length} migration(s) this image expects (${list(pending)}). ` +
        "Run the migrate service (`docker compose run --rm migrate`) or `prisma migrate deploy`.",
    );
  }

  if (unknownToImage.length > 0) {
    const message =
      `The database has ${unknownToImage.length} migration(s) this image doesn't know (${list(unknownToImage)}): ` +
      "it was migrated by a newer Fetch. Put FETCH_IMAGE_TAG back to that version, or restore the database " +
      "backup taken before the upgrade.";
    if (env.FETCH_ALLOW_SCHEMA_MISMATCH?.trim().toLowerCase() === "true") {
      log.warn(`${message} Starting anyway because FETCH_ALLOW_SCHEMA_MISMATCH=true.`);
      return true;
    }
    log.error(`Refusing to start: ${message} (Set FETCH_ALLOW_SCHEMA_MISMATCH=true to start anyway.)`);
    return false;
  }

  return true;
};
