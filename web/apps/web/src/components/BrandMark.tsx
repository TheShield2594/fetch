import { useId, type CSSProperties } from "react";

/**
 * The Fetch mark: a dog in profile whose ear is a play button, on the
 * Fetch-red tile the Android TV app's launcher icon uses.
 *
 * The tile carries its own colour, so one drawing reads on the light, dark and
 * glass themes alike. Inline rather than `<img src="/logo.svg">` so it paints
 * with the page instead of after a second request.
 *
 * The geometry is duplicated in `public/logo.svg`, which is what the browser
 * tab, the PWA manifest and the icon generators read. Change one, change both.
 */
export function BrandMark({ className, title }: { className?: string; title?: string }) {
  // The gradient is referenced by id, and the mark can be on screen twice (the
  // sidebar and a lockup), so each instance needs its own.
  const gradientId = useId();
  return (
    <svg
      viewBox="0 0 100 100"
      className={className}
      // Every lockup in the app pairs the mark with the word "Fetch", so by
      // default it is decoration a screen reader should skip. `title` is for
      // the places that stand alone.
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      focusable="false"
    >
      {title ? <title>{title}</title> : null}
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#dc2b33" />
          <stop offset="1" stopColor="#96121a" />
        </linearGradient>
      </defs>
      <rect width="100" height="100" rx="23" fill={`url(#${gradientId})`} />
      <path fill="#fff" fillRule="evenodd" d="M43.54 76.83C39.00 76.44 34.98 75.46 29.64 73.43C28.09 72.84 23.54 71.02 23.48 70.97C23.43 70.92 23.90 68.59 24.29 67.00C25.44 62.24 27.21 58.45 29.16 56.57C29.94 55.82 29.75 55.94 36.36 52.02C42.67 48.28 42.27 48.53 42.99 47.76C43.71 47.00 44.11 46.34 44.43 45.43C44.70 44.65 44.67 44.56 45.00 46.92C45.76 52.28 47.58 54.86 51.64 56.34C54.22 57.28 55.56 57.40 62.84 57.36L68.67 57.33L69.58 57.00C72.81 55.84 74.91 52.84 75.38 48.69C75.53 47.38 75.52 47.18 75.30 47.12C72.84 46.35 71.32 43.75 71.32 40.30L71.32 38.96L69.27 38.96C67.02 38.96 66.30 38.88 65.18 38.50C63.45 37.91 61.99 36.76 60.76 35.01C59.63 33.40 58.59 32.44 57.20 31.74C53.00 29.62 47.59 30.70 44.79 34.22C44.21 34.96 43.12 36.93 43.12 37.25C43.12 37.47 43.03 37.43 42.56 37.05C42.32 36.86 40.45 35.68 38.39 34.43C36.32 33.18 33.47 31.45 32.04 30.58C30.61 29.71 29.12 28.81 28.74 28.59C28.35 28.37 28.04 28.14 28.04 28.09C28.03 28.03 28.49 27.54 29.05 27.00C37.12 19.08 49.79 18.98 57.90 26.75C59.24 28.04 60.23 29.24 61.75 31.42C63.42 33.81 64.66 35.02 66.28 35.82C68.18 36.77 68.95 36.89 73.82 36.98C77.62 37.05 77.86 37.06 78.48 37.29C79.73 37.74 80.65 38.84 80.89 40.19C81.17 41.73 80.45 44.10 79.24 45.59L78.71 46.24L78.71 47.05C78.71 52.74 76.02 57.66 71.74 59.82C69.65 60.87 69.25 60.93 63.10 61.01C58.00 61.08 57.82 61.09 57.12 61.32C51.93 63.02 50.50 68.98 53.66 75.71L54.13 76.71L53.44 76.77C52.18 76.88 44.65 76.93 43.54 76.83ZM20.19 57.06C19.01 56.86 17.97 56.05 17.43 54.90L17.16 54.31L17.16 42.86L17.16 31.41L17.39 30.83C17.83 29.76 18.72 28.94 19.81 28.61C21.01 28.26 21.98 28.46 23.55 29.42C24.15 29.78 27.88 32.04 31.83 34.43C40.63 39.75 39.88 39.28 40.46 39.90C42.02 41.57 41.97 44.25 40.34 45.85C39.78 46.40 22.84 56.64 22.07 56.89C21.45 57.10 20.77 57.16 20.19 57.06ZM53.42 42.33C50.60 41.30 50.38 37.52 53.07 36.26C55.67 35.04 58.46 37.89 57.25 40.54C56.57 42.02 54.82 42.84 53.42 42.33Z" />
    </svg>
  );
}

/**
 * FETCH on a 5×7 dot matrix, one letter per glyph: the same letterforms as the
 * Android TV app's wordmark (the Doto face, scripts/brand/generate_brand.py),
 * drawn as circles so the website doesn't ship a 450 kB font for five letters
 * and the dots stay round at the sidebar's 16px.
 */
const DOT_GLYPHS = [
  // F
  ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
  // E
  ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
  // T
  ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
  // C
  [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
  // H
  ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
];
/** Two empty columns between letters: the wordmark is tracked out, as on the TV. */
const DOT_LETTER_STEP = 7;
const DOT_WIDTH = DOT_GLYPHS.length * DOT_LETTER_STEP - 2;
const DOTS = DOT_GLYPHS.flatMap((rows, letter) =>
  rows.flatMap((row, y) =>
    [...row].flatMap((cell, x) => (cell === "#" ? [{ cx: letter * DOT_LETTER_STEP + x + 0.5, cy: y + 0.5 }] : [])),
  ),
);

/**
 * The dot-matrix FETCH wordmark, in the text colour. Size it by height; the
 * width follows. It is the product's name, so it is labelled "Fetch".
 */
export function BrandWordmark({ className, style }: { className?: string; style?: CSSProperties }) {
  return (
    <svg
      viewBox={`0 0 ${DOT_WIDTH} 7`}
      className={className}
      style={style}
      role="img"
      aria-label="Fetch"
      focusable="false"
      fill="currentColor"
    >
      {DOTS.map(({ cx, cy }) => (
        <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r={0.42} />
      ))}
    </svg>
  );
}

/** Carried over from the old logo art, which had it set under the name. */
const TAGLINE = "Track your movies & shows";

/**
 * The stacked lockup, for the two screens that are nothing but brand: first run
 * and the profile picker. Centred, mark above the name, tagline underneath.
 *
 * The tagline is doing structural work as much as it is saying anything — it
 * gives the name a base to sit on, so a tall mark over a single short word
 * stops reading as two things that happen to be near each other.
 */
export function BrandLockup() {
  return (
    <div className="flex flex-col items-center text-center">
      <BrandMark className="h-16 w-16" />
      <BrandWordmark className="mt-4 h-6 w-auto" style={{ color: "var(--text)" }} />
      <span
        className="mt-1.5 text-2xs font-semibold uppercase tracking-[0.2em]"
        style={{ color: "var(--text-mute)" }}
      >
        {TAGLINE}
      </span>
    </div>
  );
}
