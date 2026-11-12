import { describe, expect, it } from "vitest";
import { posterGridSizes, type PosterGridDensity } from "./PosterGrid";
import { TMDB_SRCSET_WIDTHS } from "../poster-widths";

/**
 * What a browser does with `sizes`: take the first entry whose media condition
 * matches, resolve its length against the viewport, multiply by the device
 * pixel ratio, and pick the smallest candidate at least that wide.
 */
function slotWidth(sizes: string, viewport: number): number {
  for (const entry of sizes.split(", ")) {
    const match = entry.match(/^(?:\(min-width: (\d+)px\) )?(.+)$/);
    if (!match) throw new Error(`unreadable sizes entry: ${entry}`);
    const [, minWidth, length] = match;
    if (minWidth !== undefined && viewport < Number(minWidth)) continue;
    const px = length!.match(/^(\d+)px$/);
    if (px) return Number(px[1]);
    const calc = length!.match(/^calc\(\(100vw - (\d+)px\) \/ (\d+)\)$/);
    if (calc) return (viewport - Number(calc[1])) / Number(calc[2]);
    throw new Error(`unreadable length: ${length}`);
  }
  throw new Error("no entry matched");
}

const requested = (slot: number, dpr: number): number =>
  TMDB_SRCSET_WIDTHS.find((w) => w >= slot * dpr) ?? TMDB_SRCSET_WIDTHS[TMDB_SRCSET_WIDTHS.length - 1]!;

describe("poster grid sizes", () => {
  // #506: the Shelf at 1440 wide asked for w342 in a ~170px cell.
  it("asks for w185, not w342, on the Shelf at a desktop width", () => {
    const slot = slotWidth(posterGridSizes("library"), 1440);
    expect(slot).toBe(170);
    expect(requested(slot, 1)).toBe(185);
  });

  // …and on a 3x phone the 45vw hint selected w780 for a ~115px slot.
  it("describes a third of a phone, not half of one", () => {
    const slot = slotWidth(posterGridSizes("library"), 390);
    expect(slot).toBeCloseTo((390 - 64) / 3);
    expect(requested(slot, 3)).toBe(342);
  });

  it.each(["page", "library", "compact"] as PosterGridDensity[])(
    "never asks for more than twice the %s slot at 1x",
    (density) => {
      for (const viewport of [320, 390, 640, 768, 1024, 1280, 1440, 1920]) {
        const slot = slotWidth(posterGridSizes(density), viewport);
        expect(slot).toBeGreaterThan(60);
        expect(requested(slot, 1)).toBeLessThanOrEqual(slot * 2);
      }
    }
  );
});
