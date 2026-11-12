import { createContext, useContext, useRef, useState, type CSSProperties } from "react";
import { getInitials, titleTint } from "./carousel-utils";
import { parseSizedTmdbImage, tmdbImageAtWidth, TMDB_SRCSET_WIDTHS } from "../poster-widths";

/**
 * `sizes` for a poster whose slot nobody has described: about a fifth of a
 * desktop column, or half a phone. `PosterGrid` replaces it with the real
 * figure for the grid it lays out — see `PosterSizesContext`.
 */
export const POSTER_GRID_SIZES = "(min-width: 640px) 220px, 45vw";

/**
 * The `sizes` a poster inherits from the grid it is in.
 *
 * One hint used to serve every grid, and it was wrong for most of them: the
 * Shelf's cells are about 170px wide at seven columns and were told 220px, so
 * the browser fetched w342 for all of them; on a phone they are a third of the
 * screen and were told 45vw, which at 3x selects w780 for a 115px slot. The
 * grid knows its own column widths, so it is the grid that says — and a card
 * placed in one picks the answer up without each page restating it.
 */
export const PosterSizesContext = createContext<string>(POSTER_GRID_SIZES);

/** The smallest rung of TMDB's ladder: what the blurred placeholder is made of. */
const PLACEHOLDER_WIDTH = TMDB_SRCSET_WIDTHS[0];

/*
 * `sizes` for the dashboard's poster cards. These have to be literal lengths —
 * the attribute is read by the preload scanner before any stylesheet, so it
 * can't reference --poster-card-w. Keep both in step with that token (12rem =
 * 192px); a stale hint only costs bytes, not layout.
 */
/** A card in one of the carousels: the token width at every breakpoint. */
export const POSTER_CARD_SIZES = "192px";
/** A card in the Trending block, which is two-up and fluid below `sm`. */
export const POSTER_CARD_FILL_SIZES = "(min-width: 640px) 192px, 45vw";

/**
 * Exported for the pages that render their own `<img>` rather than this
 * component — their placeholder markup differs, but the bytes on the wire
 * shouldn't. Without a srcset they pull whatever width the API stored (usually
 * w500) into a slot around 180px wide.
 *
 * The ladder it offers lives in poster-widths.ts rather than here, because the
 * service worker's poster cache has to recognise the URLs this produces as
 * renditions of one picture — see the note there.
 */
export function buildTmdbSrcSet(src: string): string | undefined {
  const image = parseSizedTmdbImage(src);
  if (!image) return undefined;
  return TMDB_SRCSET_WIDTHS.map((w) => `${tmdbImageAtWidth(image, w)} ${w}w`).join(", ");
}

export function Poster({
  src,
  alt,
  className = "",
  eager = false,
  sizes: sizesProp,
}: {
  src?: string | undefined;
  alt: string | null | undefined;
  className?: string | undefined;
  eager?: boolean | undefined;
  /** `sizes` attribute matching this poster's actual rendered width, so the browser picks the right srcset entry. */
  sizes?: string | undefined;
}) {
  const inheritedSizes = useContext(PosterSizesContext);
  const sizes = sizesProp ?? inheritedSizes;
  const [loadFailed, setLoadFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);

  /*
   * Reset when the src changes — during render rather than in an effect,
   * because an effect runs *after* the ref callback below.
   *
   * The ref exists to mark an already-decoded image as loaded in the commit
   * that mounts it (see its own note). A mount effect then cleared that flag
   * on the very next tick, so the optimisation never actually applied — and
   * for an image whose `load` had already fired before React attached
   * `onLoad`, nothing was left to set it again and the poster stayed at
   * opacity 0 permanently. That is every image a browser can complete
   * synchronously on assignment: a `data:` URI, and a memory-cached one on
   * some paths.
   *
   * The same shape as the key change in `useCachedState`, and for the same
   * reason: state derived from a prop has to settle before the first paint,
   * not one commit after it.
   */
  const renderedSrcRef = useRef(src);
  if (renderedSrcRef.current !== src) {
    renderedSrcRef.current = src;
    setLoadFailed(false);
    setLoaded(false);
  }

  // The slot's own colour, under everything else — see `titleTint`.
  const tint = titleTint(alt);
  const tintStyle = {
    "--poster-tint": tint.color,
    "--poster-tint-mix": `${tint.strength}%`,
  } as CSSProperties;

  if (!src || loadFailed) {
    /*
     * No artwork: the title itself, on the title's tint.
     *
     * The slot is a container, and which of the two renderings shows is a
     * container query in index.css: the full title, set in the interface face,
     * wherever the slot is wide enough to hold one, and the initials in the
     * thumbnails that are not. The title is drawn from `data-title` rather than
     * as text, so a card that also captions the title underneath does not say
     * it twice to a screen reader or to anything searching the page.
     */
    return (
      <div className={`poster-art poster-fallback ${className}`} style={tintStyle}>
        <span className="poster-fallback-initials select-none">{getInitials(alt)}</span>
        {alt && <span className="poster-fallback-title" data-title={alt} aria-hidden="true" />}
      </div>
    );
  }

  const srcSet = buildTmdbSrcSet(src);
  const sized = parseSizedTmdbImage(src);
  // Only where the URL is one of TMDB's: another artwork provider has no small
  // rendition to ask for, and gets the tint alone.
  const placeholder = sized ? tmdbImageAtWidth(sized, PLACEHOLDER_WIDTH) : undefined;

  /*
   * Three layers in one slot, bottom to top: the title's tint, a w92 rendition
   * of the same picture blurred to fill it, and the picture.
   *
   * It used to be a grey shimmer that the image faded in over, so a grid on a
   * slow connection was a wall of identical boxes for as long as the artwork
   * took. The tint is there from the first frame and is different per title;
   * the w92 is a few kilobytes and lands well ahead of the real file, so the
   * slot has the picture's own colours before it has the picture.
   *
   * The placeholder is an `<img loading="lazy">` rather than a background
   * image, because a background is fetched for every slot in the document and
   * this one should only be fetched for the slots near the viewport — the same
   * rule the real image follows. It is dropped once the picture has loaded.
   */
  return (
    <div className={`poster-art ${loaded ? "" : "poster-loading"} ${className}`} style={tintStyle}>
      {placeholder && !loaded && (
        <img
          src={placeholder}
          alt=""
          aria-hidden="true"
          className="poster-blur"
          loading={eager ? "eager" : "lazy"}
          decoding="async"
        />
      )}
      <img
        src={src}
        srcSet={srcSet}
        sizes={srcSet ? sizes : undefined}
        alt={alt ?? "Poster"}
        className={`poster-image transition-opacity duration-slow ${loaded ? "opacity-100" : "opacity-0"}`}
        loading={eager ? "eager" : "lazy"}
        decoding={eager ? "sync" : "async"}
        // Above-fold artwork is what the page looks like — it should outrank the
        // lazy posters further down the row that the browser would otherwise treat
        // as equals. `low` on the rest keeps them behind the data requests.
        fetchPriority={eager ? "high" : "low"}
        // A memory-cached image can be `complete` in the same commit that sets
        // `src` — marking it loaded here paints it opaque on the first frame,
        // so re-renders and back-navigations skip the fade instead of making
        // an already-seen poster blink.
        ref={(el) => {
          if (el?.complete && el.naturalWidth > 0) setLoaded(true);
        }}
        onLoad={() => setLoaded(true)}
        onError={() => setLoadFailed(true)}
      />
    </div>
  );
}
