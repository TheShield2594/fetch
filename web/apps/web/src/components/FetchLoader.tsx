import type { CSSProperties } from "react";
import { DOT_RADIUS, dotMatrix } from "./dotMatrix";

/*
 * The wait, drawn in the wordmark's own dots.
 *
 * FETCH sits on its 5×7 matrix at rest in a faint tint of the text colour, and
 * a band of red sweeps across it column by column: the dots light as the band
 * reaches them and settle back behind it, so the name resolves left to right
 * and then does it again. Red because red is "something is happening here" in
 * this product, and it is the only red on a screen that is otherwise waiting.
 *
 * It replaced a Pac-Man ghost borrowed from a snippet site. The wait is the
 * first thing a cold start shows, and it should be Fetch's, not a character
 * from another game.
 *
 * Under reduced motion the sweep is dropped outright and the wordmark is drawn
 * still at full strength (see `.fetch-loader` in index.css) — a word, not a
 * frozen frame of an animation.
 */

const { dots: DOTS, width: WIDTH } = dotMatrix("FETCH");

export function FetchLoader({
  label = "Loading…",
  className = "",
  style,
}: {
  /** Announced to screen readers; the drawing itself is decorative. */
  label?: string | undefined;
  /** Applied to the wrapper, for positioning the loader in its container. */
  className?: string | undefined;
  style?: CSSProperties | undefined;
}) {
  return (
    <div role="status" aria-live="polite" className={`flex justify-center ${className}`} style={style}>
      <svg
        viewBox={`0 0 ${WIDTH} 7`}
        className="fetch-loader h-5 w-auto"
        aria-hidden="true"
        focusable="false"
      >
        {DOTS.map(({ cx, cy, column }) => (
          <circle
            key={`${cx}-${cy}`}
            cx={cx}
            cy={cy}
            r={DOT_RADIUS}
            // The sweep's timing is the column, nothing else: every dot in a
            // column lights together, which is what makes it read as a band
            // moving across the word rather than as dots twinkling.
            style={{ "--dot-column": column } as CSSProperties}
          />
        ))}
      </svg>
      <span className="sr-only">{label}</span>
    </div>
  );
}
