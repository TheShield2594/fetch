/*
 * TMDB's poster width ladder, and the two operations both readers of it need.
 *
 * Two places have to agree on this list:
 *
 *  - `components/Poster.tsx`, which turns it into a `srcset` so a 112px slot
 *    downloads a 112px-ish file instead of the w500 the API stored.
 *  - `sw.js`, which caches what that `srcset` asks for, and has to recognise
 *    two widths of one poster as two views of the same picture.
 *
 * Kept apart they drifted in the way that matters: the worker's poster cache is
 * capped at 400 entries, and a title shown as a 28px history thumbnail, a 192px
 * card and a 304px shelf hero occupies three of them. The cap counts URLs, so
 * the number of *titles* that survive offline is a fraction of what it reads.
 */

/**
 * TMDB's actual poster steps, ascending.
 *
 * Starts at w92 because several surfaces render posters around 112px wide — at
 * 1x those were being served a w185 and downscaled, roughly four times the
 * bytes for no visible difference.
 */
export const TMDB_SRCSET_WIDTHS = [92, 154, 185, 342, 500, 780] as const;

/**
 * The ladder for a backdrop: TMDB's landscape sizes, ascending.
 *
 * A separate list because the poster ladder stops at w780, and a hero that is
 * a full column wide on a 1440px window is 1300px of picture — it was being
 * served the stored w500 and upscaled 2.6×. w300 is the phone's 1x thumbnail
 * of a resume card; w1280 is the widest TMDB serves short of `original`.
 */
export const TMDB_BACKDROP_WIDTHS = [300, 780, 1280] as const;

/**
 * Every width either ladder asks for, ascending — what the service worker has
 * to recognise as renditions of one picture.
 */
export const TMDB_CACHED_WIDTHS: readonly number[] = [
  ...new Set<number>([...TMDB_SRCSET_WIDTHS, ...TMDB_BACKDROP_WIDTHS]),
].sort((a, b) => a - b);

/**
 * TMDB serves the same image at fixed widths under `/t/p/<size>/<path>`, so the
 * size segment is the only part that differs between two renditions of one
 * poster.
 */
const TMDB_IMAGE_RE = /^(https:\/\/image\.tmdb\.org\/t\/p\/)w(\d+)(\/.+)$/;

/** A TMDB image URL split at its width segment. */
export type SizedTmdbImage = {
  /** Everything up to and including `/t/p/`. */
  prefix: string;
  /** The width the URL asks for, in pixels. */
  width: number;
  /** The path from the slash after the size segment onward. */
  path: string;
};

/** Splits a TMDB image URL at its width segment, or `null` if it isn't one. */
export function parseSizedTmdbImage(href: string): SizedTmdbImage | null {
  const match = href.match(TMDB_IMAGE_RE);
  if (!match) return null;
  // All three groups are required by the pattern; the check is what says so to
  // the checker, and keeps saying it if the pattern is ever edited.
  const [, prefix, width, path] = match;
  if (prefix === undefined || width === undefined || path === undefined) return null;
  return { prefix, width: Number(width), path };
}

/** The same picture at another rung of the ladder. */
export function tmdbImageAtWidth(image: SizedTmdbImage, width: number): string {
  return `${image.prefix}w${width}${image.path}`;
}
