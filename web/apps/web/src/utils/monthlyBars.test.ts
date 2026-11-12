import { describe, it, expect } from "vitest";
import { seriesBar, seriesMax } from "./monthlyBars";

describe("seriesBar", () => {
  it("gives the tallest month the full band", () => {
    expect(seriesBar(20, 20)).toEqual({ value: 20, height: 100 });
  });

  it("scales a short month against the tallest one in its own series", () => {
    expect(seriesBar(4, 20).height).toBe(20);
  });

  // #521: with one 142-episode month on a shared scale, a month of three films
  // was a two-pixel line. On the films' own scale it is the tallest bar there.
  it("reads a films-only month at full height, whatever the episodes did", () => {
    const episodes = [142, 0, 3];
    const films = [0, 3, 1];
    expect(seriesBar(films[1]!, seriesMax(films)).height).toBe(100);
    expect(seriesBar(episodes[1]!, seriesMax(episodes)).height).toBeLessThan(5);
  });

  it("keeps a single watch visible", () => {
    expect(seriesBar(1, 142).height).toBe(6);
  });

  it("gives an empty month a placeholder and no value", () => {
    expect(seriesBar(0, 20)).toEqual({ value: 0, height: 2 });
  });

  it("never draws a bar taller than its band", () => {
    expect(seriesBar(30, 20).height).toBe(100);
    expect(seriesBar(5, 0).height).toBe(100);
  });
});

describe("seriesMax", () => {
  it("is safe to divide by for an empty or all-zero series", () => {
    expect(seriesMax([])).toBe(1);
    expect(seriesMax([0, 0])).toBe(1);
    expect(seriesMax([3, 142, 7])).toBe(142);
  });
});
