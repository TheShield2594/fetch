import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  TITLE_TINT_COUNT,
  titleTint,
  getInitials,
  useHorizontalScroll,
} from "./carousel-utils";

describe("getInitials", () => {
  it("takes the first letter of the first two words", () => {
    expect(getInitials("Breaking Bad")).toBe("BB");
    expect(getInitials("The Lord of the Rings")).toBe("TL");
  });

  it("uppercases single-word titles", () => {
    expect(getInitials("severance")).toBe("S");
  });

  it("splits on colons and every dash variant", () => {
    expect(getInitials("Star Wars: A New Hope")).toBe("SW");
    expect(getInitials("Spider-Man")).toBe("SM");
    expect(getInitials("Mission–Impossible")).toBe("MI");
    expect(getInitials("Mission—Impossible")).toBe("MI");
  });

  it("ignores separators that would otherwise yield empty initials", () => {
    expect(getInitials("  -  Fargo")).toBe("F");
  });

  it("falls back to '?' for missing names", () => {
    expect(getInitials(null)).toBe("?");
    expect(getInitials(undefined)).toBe("?");
    expect(getInitials("")).toBe("?");
  });
});

describe("titleTint", () => {
  const key = (name: string | null) => {
    const tint = titleTint(name);
    return `${tint.color}@${tint.strength}`;
  };

  it("is built from theme tokens, not from a fixed palette", () => {
    for (const name of ["Dune", "Arrival", "Andor", "Chernobyl", "Fleabag"]) {
      expect(titleTint(name).color).toMatch(/var\(--accent(-2)?-rgb\)/);
    }
  });

  it("is stable for the same name", () => {
    expect(key("Succession")).toBe(key("Succession"));
  });

  it("spreads different names across more than one tint", () => {
    const names = ["Dune", "Arrival", "Andor", "Chernobyl", "Fleabag", "Barry", "Severance"];
    expect(new Set(names.map(key)).size).toBeGreaterThan(1);
  });

  it("can reach every tint it defines", () => {
    const seen = new Set(Array.from({ length: 400 }, (_, i) => key(`Title ${i}`)));
    expect(seen.size).toBe(TITLE_TINT_COUNT);
  });

  it("gives a missing name a tint rather than nothing", () => {
    expect(key(null)).toBe(key(""));
    expect(titleTint(null).strength).toBeGreaterThan(0);
  });

  it("stays in range for names whose hash overflows to a negative int", () => {
    const tint = titleTint("x".repeat(500));
    expect(tint.color).toBeTruthy();
    expect(tint.strength).toBeGreaterThan(0);
  });
});
