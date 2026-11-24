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
