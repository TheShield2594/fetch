import type { CSSProperties, ReactNode, Ref } from "react";
import { PosterSizesContext } from "./Poster";

/**
 * The grid a page of posters sits in.
 *
 * The column counts were a class string copied across Search, Lists, the
 * Shelf and Stats, which is that many places for a breakpoint to
 * drift — and they had already drifted into three different densities with
 * nothing recording that any of it was deliberate. The three are kept, and
 * named for what each is actually for.
 */

export type PosterGridDensity = "page" | "library" | "results" | "compact";

const DENSITIES: Record<PosterGridDensity, string> = {
  /** A results or list page: big enough to read a title under the poster. */
  page: "grid-cols-2 gap-5 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5",
  /**
   * The Shelf. Three across on a phone rather than two: a poster is
   * recognisable well below the width two-up gives it, and three is what turns
   * the grid into a *library* — a shelf of them at once instead of a stack you
   * scroll through a pair at a time.
   */
  library:
    "grid-cols-3 gap-x-3 gap-y-4 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 sm:gap-x-4 sm:gap-y-5",
  /**
   * Search results: the library's density from a tablet up, so a page of
   * results is a shelf to scan rather than five large posters a row, but two
   * across on a phone, where each card carries an add menu that needs the room.
   */
  results:
    "grid-cols-2 gap-x-3 gap-y-5 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 sm:gap-x-4",
  /** Inside a panel, where the grid is an illustration rather than the subject. */
  compact: "grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5",
};

/**
 * How wide one cell is, per density, as an `<img sizes>` list.
 *
 * Worked out from the column counts above and the shell around them: the page
 * is capped at 1400px, with a 64px rail and 24px gutters from `sm` up, and
 * 20px gutters below it. Each entry is (content width − gaps) ÷ columns. The
 * attribute is read before any stylesheet, so these have to be literal lengths
 * that restate the layout rather than read it; a stale one costs bytes, not
 * layout. With the rail pinned open the cells are a little narrower than this
 * says, which errs toward the sharper file.
 */
const SIZES: Record<PosterGridDensity, string> = {
  page: [
    "(min-width: 1400px) 242px",
    "(min-width: 1024px) calc((100vw - 192px) / 5)",
    "(min-width: 768px) calc((100vw - 172px) / 4)",
    "(min-width: 640px) calc((100vw - 152px) / 3)",
    "calc((100vw - 60px) / 2)",
  ].join(", "),
  library: [
    "(min-width: 1400px) 170px",
    "(min-width: 1280px) calc((100vw - 208px) / 7)",
    "(min-width: 1024px) calc((100vw - 192px) / 6)",
    "(min-width: 768px) calc((100vw - 176px) / 5)",
    "(min-width: 640px) calc((100vw - 160px) / 4)",
    "calc((100vw - 64px) / 3)",
  ].join(", "),
  results: [
    "(min-width: 1400px) 170px",
    "(min-width: 1280px) calc((100vw - 208px) / 7)",
    "(min-width: 1024px) calc((100vw - 192px) / 6)",
    "(min-width: 768px) calc((100vw - 176px) / 5)",
    "(min-width: 640px) calc((100vw - 160px) / 4)",
    "calc((100vw - 52px) / 2)",
  ].join(", "),
  // Inside a panel with 20px of padding, in a column capped at 896px.
  compact: [
    "(min-width: 1008px) 162px",
    "(min-width: 768px) calc((100vw - 200px) / 5)",
    "(min-width: 640px) calc((100vw - 176px) / 3)",
    "calc((100vw - 92px) / 2)",
  ].join(", "),
};

/** Exported for the test that keeps these in step with the columns. */
export const posterGridSizes = (density: PosterGridDensity): string => SIZES[density];

export function PosterGrid({
  density = "page",
  className = "",
  gridRef,
  style,
  children,
}: {
  density?: PosterGridDensity | undefined;
  /** Layout that belongs to the caller — a top margin, say — not to the grid. */
  className?: string | undefined;
  /** For a caller that measures the grid — the Shelf's windowing. */
  gridRef?: Ref<HTMLDivElement> | undefined;
  style?: CSSProperties | undefined;
  children: ReactNode;
}) {
  return (
    <PosterSizesContext.Provider value={SIZES[density]}>
      <div ref={gridRef} style={style} className={`grid ${DENSITIES[density]} ${className}`.trimEnd()}>{children}</div>
    </PosterSizesContext.Provider>
  );
}
