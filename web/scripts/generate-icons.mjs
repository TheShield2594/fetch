// Generates the app icons in apps/web/public/ from the mark in
// apps/web/public/logo.svg: the two PWA icons and the .ico the browser tab
// falls back to.
//
// The assets are committed, so this only needs re-running when the mark
// changes. sharp is deliberately not a workspace dependency (a large native
// module that would be pulled into every CI run and Docker build for a script
// nobody runs day to day) — install it just for the run:
//
//   pnpm add -Dw sharp && node scripts/generate-icons.mjs && pnpm remove -w sharp

import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { loadSharp, logoSvg, maskableSvg, repoRoot } from "./brand.mjs";

const PUBLIC_DIR = join(repoRoot, "apps/web/public");

// Every icon but the maskable one is the rounded tile itself, filling the
// canvas edge to edge with transparent corners, the way the Android TV app's
// launcher icon is drawn. icon-512 is the `maskable` entry: Android may crop it
// to anything inside a circle 80% of its width, so it is a full-bleed square
// with the dog scaled down to sit inside that circle. The manifest declares
// `any` and `maskable` separately, so icon-512-full is what is shown whole.
const MASKABLE_GLYPH_SCALE = 0.72;
const TARGETS = [
  { file: "icons/icon-512.png", size: 512, maskable: true },
  { file: "icons/icon-512-full.png", size: 512 },
  { file: "icons/icon-192.png", size: 192 },
];
const ICO_SIZES = [16, 32, 48];

const sharp = await loadSharp("generate-icons.mjs");
const tile = await logoSvg();
const maskable = await maskableSvg(MASKABLE_GLYPH_SCALE);

/**
 * The mark rendered at its final size, as PNG bytes. Rendered from the SVG at
 * each size rather than resized from one raster, so the small icons get their
 * own correctly sampled edges.
 */
async function icon(size, isMaskable = false) {
  return sharp(isMaskable ? maskable : tile, { density: Math.max(72, (size / 100) * 72 * 4) })
    .resize(size, size)
    .png({ compressionLevel: 9, effort: 10 })
    .toBuffer();
}

/**
 * Packs PNGs into an .ico. Every browser that still reads .ico at all accepts
 * PNG-compressed entries, and they are a third the size of the BMP form.
 */
function ico(images) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // 1 = icon
  header.writeUInt16LE(images.length, 4);

  let offset = 6 + images.length * 16;
  const entries = images.map(({ size, data }) => {
    const entry = Buffer.alloc(16);
    entry.writeUInt8(size === 256 ? 0 : size, 0); // 0 means 256
    entry.writeUInt8(size === 256 ? 0 : size, 1);
    entry.writeUInt8(0, 2); // palette size: 0 for truecolour
    entry.writeUInt8(0, 3); // reserved
    entry.writeUInt16LE(1, 4); // colour planes
    entry.writeUInt16LE(32, 6); // bits per pixel
    entry.writeUInt32LE(data.length, 8);
    entry.writeUInt32LE(offset, 12);
    offset += data.length;
    return entry;
  });

  return Buffer.concat([header, ...entries, ...images.map((image) => image.data)]);
}

await mkdir(join(PUBLIC_DIR, "icons"), { recursive: true });

for (const target of TARGETS) {
  await writeFile(join(PUBLIC_DIR, target.file), await icon(target.size, target.maskable));
  console.log(`Wrote apps/web/public/${target.file}`);
}

const frames = [];
for (const size of ICO_SIZES) frames.push({ size, data: await icon(size) });
await writeFile(join(PUBLIC_DIR, "favicon.ico"), ico(frames));
console.log(`Wrote apps/web/public/favicon.ico (${ICO_SIZES.join(", ")}px)`);
