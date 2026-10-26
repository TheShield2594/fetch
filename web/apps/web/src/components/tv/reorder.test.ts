import { describe, expect, it } from "vitest";
import { ApiError } from "../../api";
import { isVersionConflict, moveItem } from "./reorder";

describe("moveItem", () => {
  it("moves an entry up or down without touching the input", () => {
    const items = ["a", "b", "c", "d"];
    expect(moveItem(items, 0, 2)).toEqual(["b", "c", "a", "d"]);
    expect(moveItem(items, 3, 1)).toEqual(["a", "d", "b", "c"]);
    expect(items).toEqual(["a", "b", "c", "d"]);
  });

  it("returns the list unchanged for a move off either end", () => {
    expect(moveItem(["a", "b"], 0, -1)).toEqual(["a", "b"]);
    expect(moveItem(["a", "b"], 1, 2)).toEqual(["a", "b"]);
  });
});

describe("isVersionConflict", () => {
  it("is true only for the 412 a stale save gets", () => {
    expect(isVersionConflict(new ApiError("changed", 412, "version_conflict"))).toBe(true);
    expect(isVersionConflict(new ApiError("bad", 400))).toBe(false);
    expect(isVersionConflict(new Error("x"))).toBe(false);
  });
});
