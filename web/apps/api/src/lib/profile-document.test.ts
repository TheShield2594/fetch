import { describe, expect, it, vi } from "vitest";

vi.mock("./prisma.js", () => ({ prisma: {} }));
import { opaqueDocumentProblem, parseIfMatch } from "./profile-document.js";

describe("parseIfMatch", () => {
  it("reads strong, weak and bare versions", () => {
    expect(parseIfMatch('"3"')).toBe(3);
    expect(parseIfMatch('W/"12"')).toBe(12);
    expect(parseIfMatch("0")).toBe(0);
    expect(parseIfMatch(['"5"', '"6"'])).toBe(5);
  });

  it("tells a missing header from one that isn't a version", () => {
    expect(parseIfMatch(undefined)).toBeUndefined();
    expect(parseIfMatch("*")).toBeNull();
    expect(parseIfMatch('"abc"')).toBeNull();
    expect(parseIfMatch('"1", "2"')).toBeNull();
  });
});

describe("opaqueDocumentProblem", () => {
  it("accepts a plain object", () => {
    expect(opaqueDocumentProblem({ version: 1, features: { a: { b: { type: "string", value: "x" } } } }, 1024)).toBeNull();
  });

  it("rejects non-objects, oversized and deeply nested documents", () => {
    expect(opaqueDocumentProblem([], 1024)).toBe("must be a JSON object");
    expect(opaqueDocumentProblem({ big: "x".repeat(2048) }, 1024)).toBe("must be at most 1 KB");
    let deep: Record<string, unknown> = {};
    const root = deep;
    for (let i = 0; i < 20; i++) {
      const next: Record<string, unknown> = {};
      deep.next = next;
      deep = next;
    }
    expect(opaqueDocumentProblem(root, 1024 * 1024)).toContain("nest deeper");
  });
});
