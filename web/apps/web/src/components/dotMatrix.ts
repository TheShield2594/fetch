/*
 * The dot-matrix face the FETCH wordmark is set in, as data.
 *
 * Same 5×7 letterforms as the Android TV app's wordmark (the Doto face,
 * scripts/brand/generate_brand.py), drawn as circles so the website doesn't
 * ship a 450 kB font for a handful of characters and the dots stay round at the
 * rail's 16px.
 *
 * Only the characters the product actually sets are here: the five letters of
 * the name and the ten digits the 404 and the stat numerals use. The face is a
 * display accent — an echo of the wordmark — not a text face, and a glyph set
 * that covered the alphabet would be an invitation to set sentences in it.
 */

const GLYPHS: Record<string, readonly string[]> = {
  F: ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
  E: ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
  T: ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
  C: [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
  H: ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
  "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
  "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
  "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
  "3": ["#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."],
  "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
  "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
  "6": ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
  "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
  "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
  "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."],
};

/** A glyph's width in dots, and the matrix's height. */
const GLYPH_WIDTH = 5;
export const DOT_ROWS = 7;

/** Two empty columns between characters: the wordmark is tracked out, as on the TV. */
export const DOT_PITCH = GLYPH_WIDTH + 2;

/** The radius each dot is drawn at, in the one-unit cell it sits in. */
export const DOT_RADIUS = 0.42;

export type Dot = {
  cx: number;
  cy: number;
  /** The dot's column across the whole run, from 0 — what a sweep is timed off. */
  column: number;
};

export function hasDotGlyphs(text: string): boolean {
  return [...text.toUpperCase()].every((char) => char in GLYPHS);
}

/**
 * The dots for `text`, and the width of the box they sit in.
 *
 * Characters outside the set throw rather than drawing a gap: a missing glyph
 * is a bug at the call site, and a numeral that silently lost a digit is a
 * wrong number on screen.
 */
export function dotMatrix(text: string): { dots: Dot[]; width: number } {
  const chars = [...text.toUpperCase()];
  const dots = chars.flatMap((char, index) => {
    const rows = GLYPHS[char];
    if (!rows) throw new Error(`No dot-matrix glyph for "${char}"`);
    return rows.flatMap((row, y) =>
      [...row].flatMap((cell, x) => {
        if (cell !== "#") return [];
        const column = index * DOT_PITCH + x;
        return [{ cx: column + 0.5, cy: y + 0.5, column }];
      }),
    );
  });
  return { dots, width: Math.max(0, chars.length * DOT_PITCH - 2) };
}
