import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type RefObject } from "react";

/**
 * Renders only the rows of a grid near the viewport, once there are enough of
 * them to matter.
 *
 * A library of a few thousand titles drawn in full is a few thousand cards'
 * worth of DOM, and growing the slice on scroll (the way this used to work)
 * only ever added to it. Here the rows above and below the window are padding
 * on the grid instead of cells, so the scrollbar and the page's height are
 * what they would be with everything drawn, and the number of cells stays at
 * a few screens' worth however far down you are.
 *
 * It needs every row to be one height, which the poster grid's are: the art
 * is a fixed ratio and the caption lines are truncated. The columns and the
 * row height are read off the rendered grid, so it follows the breakpoints
 * without restating them. Until that first measure (and in a test, where
 * nothing has a size) it draws the first `initialCount` cells.
 *
 * Below `threshold` items it is off and the whole grid is drawn: a few hundred
 * cells cost little, and a complete grid is one that find-in-page and a
 * screen reader's list of links can see all of.
 */

type Layout = { columns: number; rowHeight: number };

const OVERSCAN_PX = 1200;

export function useWindowedGrid(
  count: number,
  { threshold = 240, initialCount = 60 }: { threshold?: number; initialCount?: number } = {}
): {
  gridRef: RefObject<HTMLDivElement | null>;
  start: number;
  end: number;
  style: CSSProperties | undefined;
} {
  const enabled = count > threshold;
  const gridRef = useRef<HTMLDivElement>(null);
  const [layout, setLayout] = useState<Layout | null>(null);
  const [rows, setRows] = useState<{ first: number; last: number } | null>(null);

  const measure = useCallback(() => {
    const grid = gridRef.current;
    const cell = grid?.firstElementChild as HTMLElement | null | undefined;
    if (!grid || !cell) return;
    const style = getComputedStyle(grid);
    const columns = style.gridTemplateColumns.split(" ").filter(Boolean).length;
    const rowHeight = cell.getBoundingClientRect().height + (parseFloat(style.rowGap) || 0);
    if (columns < 1 || rowHeight <= 0) return;
    setLayout((prev) => (prev && prev.columns === columns && prev.rowHeight === rowHeight ? prev : { columns, rowHeight }));
  }, []);

  const place = useCallback(() => {
    const grid = gridRef.current;
    if (!grid || !layout) return;
    // The grid's top is row 0's top: the rows above the window are padding
    // inside it, each exactly one row tall.
    const top = grid.getBoundingClientRect().top;
    const totalRows = Math.ceil(count / layout.columns);
    const first = Math.max(0, Math.floor((-OVERSCAN_PX - top) / layout.rowHeight));
    const last = Math.min(totalRows, Math.ceil((window.innerHeight + OVERSCAN_PX - top) / layout.rowHeight));
    setRows((prev) => (prev && prev.first === first && prev.last === last ? prev : { first, last }));
  }, [count, layout]);

  useLayoutEffect(() => {
    if (!enabled) return;
    measure();
  }, [enabled, measure, count]);

  useLayoutEffect(() => {
    if (enabled) place();
  }, [enabled, place]);

  useEffect(() => {
    if (!enabled) return;
    let frame = 0;
    const onScroll = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(place);
    };
    const onResize = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        measure();
        place();
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
    };
  }, [enabled, measure, place]);

  if (!enabled) return { gridRef, start: 0, end: count, style: undefined };
  if (!layout || !rows) return { gridRef, start: 0, end: Math.min(count, initialCount), style: undefined };

  const totalRows = Math.ceil(count / layout.columns);
  const start = Math.min(count, rows.first * layout.columns);
  const end = Math.min(count, rows.last * layout.columns);
  return {
    gridRef,
    start,
    end,
    style: {
      paddingTop: rows.first * layout.rowHeight,
      paddingBottom: Math.max(0, totalRows - rows.last) * layout.rowHeight,
    },
  };
}
