import { Star } from "lucide-react";
import { formatStars, STARS_MAX, toStars } from "../utils/rating";

/**
 * The profile's own rating of something: five stars, filled to the half.
 *
 * One treatment, used wherever a rating is *yours*. The app shows two kinds of
 * score and used to draw both the same way — a number in a ringed amber badge,
 * out of ten in one grid and out of five in the next — so nothing but the
 * heading told a community score from your own. A community score is a number
 * someone else arrived at and stays a number (`8.4`); yours is the row of stars
 * you filled in, in the accent colour, here.
 *
 * Stored ratings are 1–10 and shown as halves of five; see `utils/rating`.
 */
export function MyRating({
  rating,
  size = 12,
  className = "",
}: {
  /** The stored rating, 1–10. */
  rating: number;
  /** Star size in pixels. */
  size?: number;
  className?: string;
}) {
  const label = `Your rating: ${formatStars(rating)} out of ${STARS_MAX}`;
  const filled = Math.max(0, Math.min(100, (toStars(rating) / STARS_MAX) * 100));
  const stars = (fill: boolean) =>
    Array.from({ length: STARS_MAX }, (_, i) => (
      <Star key={i} width={size} height={size} className="flex-none" fill={fill ? "currentColor" : "none"} strokeWidth={fill ? 0 : 1.75} />
    ));

  return (
    <span role="img" aria-label={label} title={label} className={`relative inline-flex align-middle ${className}`.trimEnd()}>
      {/* The empty row sets the width; the filled row is the same five stars
          clipped to the rating, laid over it. */}
      <span aria-hidden="true" className="flex" style={{ color: "var(--border-strong)" }}>
        {stars(false)}
      </span>
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-0 flex overflow-hidden"
        style={{ width: `${filled}%`, color: "var(--accent-text)" }}
      >
        {stars(true)}
      </span>
    </span>
  );
}
