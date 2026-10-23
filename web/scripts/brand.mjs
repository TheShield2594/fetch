// Shared bits of the Fetch mark, for the two generators that rasterise it
// (generate-icons.mjs and generate-splash.mjs).
//
// The mark lives in apps/web/public/logo.svg — served as-is to browsers that
// prefer an SVG favicon, and the source every committed PNG below is rendered
// from. At ~4 kB it costs nothing to ship, unlike the 1.2 MB raster it replaced.

import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
export const LOGO_SVG = join(repoRoot, "apps/web/public/logo.svg");

/**
 * The mark as an SVG buffer, exactly as browsers get it: the dog on a rounded
 * amber-to-coral tile, the same artwork as the Android TV launcher icon.
 */
export async function logoSvg() {
  return Buffer.from(await readFile(LOGO_SVG, "utf8"));
}

/**
 * The mark for a `maskable` icon: the tile runs to the square's edges, since the
 * platform supplies its own mask shape, and the dog is scaled by `glyphScale`
 * around the centre so it stays inside the 80% safe circle.
 */
export async function maskableSvg(glyphScale) {
  const svg = await readFile(LOGO_SVG, "utf8");
  const offset = 50 * (1 - glyphScale);
  return Buffer.from(
    svg
      .replace('rx="23"', 'rx="0"')
      .replace(
        /(<path fill="#fff"[^>]*\/>)/,
        `<g transform="translate(${offset} ${offset}) scale(${glyphScale})">$1</g>`
      )
  );
}

/** Loads sharp, or explains how to install it for the length of one run. */
export async function loadSharp(script) {
  try {
    return (await import("sharp")).default;
  } catch {
    console.error(
      "sharp is not installed. Run:\n" +
        `  pnpm add -Dw sharp && node scripts/${script} && pnpm remove -w sharp`
    );
    process.exit(1);
  }
}
