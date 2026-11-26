import { afterEach, describe, expect, it, vi } from "vitest";
import { deriveServiceToken, parseAddonKey } from "@fetch/shared";

// `addon-key` touches no database, but the CLI imports Prisma for the others.
vi.mock("./lib/prisma.js", () => ({ prisma: { $disconnect: vi.fn() } }));

describe("fetch addon-key", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it("prints FETCH_ADDON_KEY alone on stdout, derived from API_TOKEN", async () => {
    vi.stubEnv("API_TOKEN", "b".repeat(64));
    vi.spyOn(console, "error").mockImplementation(() => {});
    const { run } = await import("./cli.js");
    const lines: string[] = [];
    expect(await run(["addon-key"], (line) => lines.push(line))).toBe(0);
    expect(lines).toHaveLength(1);
    expect(parseAddonKey(lines[0])?.serviceToken).toBe(deriveServiceToken("b".repeat(64)));
    expect(lines[0]).not.toContain("b".repeat(64));
  });

  it("refuses without API_TOKEN", async () => {
    vi.stubEnv("API_TOKEN", "");
    vi.spyOn(console, "error").mockImplementation(() => {});
    const { run } = await import("./cli.js");
    expect(await run(["addon-key"], () => {})).toBe(2);
  });
});

describe("fetch rotate-key (#638)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  const refused = async (argv: string[]) => {
    const errors: string[] = [];
    vi.spyOn(console, "error").mockImplementation((line: unknown) => void errors.push(String(line)));
    const { run } = await import("./cli.js");
    expect(await run(argv, () => {})).toBe(2);
    return errors.join("\n");
  };

  it("needs both keys", async () => {
    expect(await refused(["rotate-key", "--old", "a".repeat(64)])).toContain("needs both --old and --new");
  });

  it("refuses the same key twice, a short new key, and API_TOKEN as the new key", async () => {
    vi.stubEnv("API_TOKEN", "t".repeat(64));
    expect(await refused(["rotate-key", "--old", "a".repeat(64), "--new", "a".repeat(64)])).toContain("the same key");
    expect(await refused(["rotate-key", "--old", "a".repeat(64), "--new", "short"])).toContain("at least 32");
    expect(await refused(["rotate-key", "--old", "a".repeat(64), "--new", "t".repeat(64)])).toContain("--new is API_TOKEN");
  });
});
