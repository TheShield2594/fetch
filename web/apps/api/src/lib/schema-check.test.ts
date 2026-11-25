import { describe, expect, it, vi } from "vitest";
import type { FastifyBaseLogger } from "fastify";

vi.mock("./prisma.js", () => ({ prisma: { $queryRaw: vi.fn() } }));

const { bakedMigrations, compareMigrations, verifySchemaMatchesImage } = await import("./schema-check.js");

const logger = () => {
  const log = { warn: vi.fn(), error: vi.fn(), info: vi.fn() };
  return log as unknown as FastifyBaseLogger & typeof log;
};

describe("schema check", () => {
  it("finds this checkout's migrations", () => {
    const baked = bakedMigrations();
    expect(baked).not.toBeNull();
    expect(baked!.length).toBeGreaterThan(0);
    expect(baked).not.toContain("migration_lock.toml");
  });

  it("splits the difference both ways", () => {
    expect(compareMigrations(["a", "b", "c"], ["a", "b", "z"])).toEqual({ unknownToImage: ["z"], pending: ["c"] });
  });

  it("starts when the database matches", async () => {
    const log = logger();
    const ok = await verifySchemaMatchesImage(log, {}, { baked: () => ["a"], applied: async () => ["a"] });
    expect(ok).toBe(true);
    expect(log.warn).not.toHaveBeenCalled();
  });

  it("warns, and still starts, when migrations are pending", async () => {
    const log = logger();
    const ok = await verifySchemaMatchesImage(log, {}, { baked: () => ["a", "b"], applied: async () => ["a"] });
    expect(ok).toBe(true);
    expect(log.warn).toHaveBeenCalledWith(expect.stringContaining("missing 1 migration"));
  });

  it("refuses to start on a database migrated by a newer image", async () => {
    const log = logger();
    const ok = await verifySchemaMatchesImage(log, {}, { baked: () => ["a"], applied: async () => ["a", "b"] });
    expect(ok).toBe(false);
    expect(log.error).toHaveBeenCalledWith(expect.stringContaining("Refusing to start"));
  });

  it("starts anyway when told to", async () => {
    const log = logger();
    const ok = await verifySchemaMatchesImage(
      log,
      { FETCH_ALLOW_SCHEMA_MISMATCH: "true" },
      { baked: () => ["a"], applied: async () => ["a", "b"] },
    );
    expect(ok).toBe(true);
    expect(log.error).not.toHaveBeenCalled();
  });

  it("never refuses when the check itself can't run", async () => {
    const noFolder = await verifySchemaMatchesImage(logger(), {}, { baked: () => null });
    const noTable = await verifySchemaMatchesImage(logger(), {}, {
      baked: () => ["a"],
      applied: async () => {
        throw new Error('relation "_prisma_migrations" does not exist');
      },
    });
    expect(noFolder).toBe(true);
    expect(noTable).toBe(true);
  });
});
