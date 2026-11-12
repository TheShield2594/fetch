import type { ReactNode } from "react";
import { MarkGlyph } from "./BrandMark";

/*
 * The one empty state.
 *
 * There were two: Search and Lists drew a large lucide icon in a ringed circle
 * under a section-size title, while Calendar, History and Stats drew a bordered
 * card with a 40px icon and small text. Neither was Fetch's — a stock icon in a
 * circle is every app's empty state — and having both meant the same moment
 * ("there is nothing here yet") looked different depending on which page you
 * met it on.
 *
 * Now: the mark's dog, small and quiet in the muted text colour, a short title
 * that says what is missing, one plain sentence on why or what fills it, and at
 * most one way forward. No card and no border — an empty state is the page
 * being quiet, and a box around nothing is still a box.
 *
 * `size` is the only variant. "page" is the state of a whole route; "section"
 * is one row of a page that has others (Discover's rails), where the same
 * thing at page height would push the rest of the page off the screen.
 */

/** The link style an empty state's way forward takes, for callers passing a <Link>. */
export const EMPTY_STATE_ACTION =
  "inline-block py-1 text-sm font-medium text-accent-text underline-offset-2 transition-colors hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset rounded";

export function EmptyState({
  title,
  children,
  action,
  size = "page",
  className = "",
}: {
  /** What is missing, in a few words: "Nothing on the shelf yet". */
  title: string;
  /** One sentence on why, or on what fills it. */
  children?: ReactNode | undefined;
  /** At most one way forward — a link or a button. */
  action?: ReactNode | undefined;
  size?: "page" | "section" | undefined;
  className?: string | undefined;
}) {
  const page = size === "page";
  return (
    <div
      className={`flex flex-col items-center text-center ${page ? "py-16" : "py-8"} ${className}`.trimEnd()}
      data-empty-state=""
    >
      <MarkGlyph
        className={page ? "h-10 w-auto" : "h-7 w-auto"}
        style={{ color: "var(--text-mute)", opacity: 0.7 }}
      />
      <p className={`${page ? "mt-4 text-base" : "mt-3 text-sm"} font-semibold`} style={{ color: "var(--text)" }}>
        {title}
      </p>
      {children && (
        <div className="mt-1 max-w-sm text-sm" style={{ color: "var(--text-dim)" }}>
          {children}
        </div>
      )}
      {action && <div className="mt-3 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  );
}
