import { describe, expect, it } from "vitest";
import { encodeQr, qrSvgPath } from "./qrCode";

// Decoding needs a scanner; these check the structure every scanner relies on.
// (The encoder was checked against zxing-cpp for versions 1 to 9 and every mask.)

const finderAt = (grid: boolean[][], x: number, y: number) => {
  // A dark 7×7 ring, a light ring inside it, and a dark 3×3 core.
  for (let dy = 0; dy < 7; dy += 1) {
    for (let dx = 0; dx < 7; dx += 1) {
      const ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
      if (grid[y + dy]![x + dx] !== (ring !== 2)) return false;
    }
  }
  return true;
};

describe("encodeQr", () => {
  it("picks the smallest version that holds the text", () => {
    expect(encodeQr("hi")).toHaveLength(21);
    const uri = "otpauth://totp/Fetch:alex?secret=JBSWY3DPEHPK3PXP&issuer=Fetch&algorithm=SHA1&digits=6&period=30";
    const size = encodeQr(uri).length;
    expect((size - 17) % 4).toBe(0);
    expect(size).toBeGreaterThan(21);
  });

  it("draws the three finder patterns and the timing lines", () => {
    const grid = encodeQr("otpauth://totp/Fetch:alex?secret=JBSWY3DPEHPK3PXP");
    const n = grid.length;
    expect(finderAt(grid, 0, 0)).toBe(true);
    expect(finderAt(grid, n - 7, 0)).toBe(true);
    expect(finderAt(grid, 0, n - 7)).toBe(true);
    for (let i = 8; i < n - 8; i += 1) {
      expect(grid[6]![i]).toBe(i % 2 === 0);
      expect(grid[i]![6]).toBe(i % 2 === 0);
    }
    // The dark module beside the bottom-left finder.
    expect(grid[n - 8]![8]).toBe(true);
  });

  it("is deterministic, and refuses what it can't hold", () => {
    expect(encodeQr("same text")).toEqual(encodeQr("same text"));
    expect(() => encodeQr("x".repeat(400))).toThrow();
  });
});

describe("qrSvgPath", () => {
  it("leaves a four-module quiet zone", () => {
    const { path, size } = qrSvgPath([[true]]);
    expect(size).toBe(9);
    expect(path).toBe("M4 4h1v1h-1z");
  });
});
