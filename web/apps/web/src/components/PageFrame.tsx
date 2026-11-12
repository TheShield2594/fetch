import type { ReactNode } from "react";
import { PAGE_TITLE } from "./typography";

/*
 * The frame every route sits in.
 *
 * Moving between pages at 1440px used to move the page title: the Shelf's sat
 * at the content column's left edge, Calendar and Stats were centred in a
 * 896px column, History in 768px, Settings narrower still, and Discover and
 * Search had no visible title at all. Each page had chosen its own width and
 * then centred it, so the one thing that should be fixed between routes — where
 * you look to see where you are — was the thing that changed most.
 *
 * Two widths, one left edge:
 *
 *   wide     grids and rails of artwork — the Shelf, Discover, Search, Lists,
 *            Calendar, and Settings with its two panes. The full content column.
 *   reading  lists of rows and forms — History, Stats, Profiles. Capped
 *            for line length, but left-aligned to the same edge as `wide`
 *            rather than centred, so the title does not move.
 *
 * And one header: the title, an optional line under it that says what is on
 * the page, and optional actions on the trailing side.
 */

export type PageWidth = "wide" | "reading";

const WIDTHS: Record<PageWidth, string> = {
  wide: "",
  reading: "max-w-4xl",
};

export function PageFrame({
  width = "wide",
  className = "",
  children,
}: {
  width?: PageWidth | undefined;
  /** Vertical rhythm and the like, which belong to the page. */
  className?: string | undefined;
  children: ReactNode;
}) {
  return <div className={`w-full min-w-0 ${WIDTHS[width]} ${className}`.trim()}>{children}</div>;
}

export function PageHeader({
  title,
  summary,
  actions,
  className = "mb-4",
  titleId,
}: {
  title: ReactNode;
  /** One line describing the whole page — counts, a status, a date range. */
  summary?: ReactNode | undefined;
  /** Controls that act on the whole page, on the trailing side of the title. */
  actions?: ReactNode | undefined;
  /** Spacing below the header. `mb-4` unless the page's own rhythm (a `space-y-*`) spaces it. */
  className?: string | undefined;
  titleId?: string | undefined;
}) {
  return (
    <header className={`flex min-w-0 flex-wrap items-end justify-between gap-x-4 gap-y-3 ${className}`.trimEnd()}>
      <div className="min-w-0">
        <h1 id={titleId} className={PAGE_TITLE} style={{ color: "var(--text)" }}>
          {title}
        </h1>
        {/* 15px, not the 12px caption rank: this is the large title's own
            subtitle, and the only line on the screen that describes the whole
            of what is under it. */}
        {summary && (
          <div className="mt-0.5 text-[0.9375rem] tabular-nums" style={{ color: "var(--text-dim)" }}>
            {summary}
          </div>
        )}
      </div>
      {actions && <div className="flex flex-none flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}
