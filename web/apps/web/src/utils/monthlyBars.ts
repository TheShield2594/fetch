/**
 * Geometry for the Stats page's Monthly Activity chart.
 *
 * Films and episodes are two series drawn in two bands, each scaled against its
 * own tallest month. They used to be one stacked bar on one scale, and the
 * scale was whatever the busiest month's episode count was: with 142 episodes
 * in March, three films in June was a two-pixel line under the episode bar and
 * a month with *only* films was a sliver. A film and an episode are not the
 * same unit, so there was never a reason for them to share an axis.
 *
 * The height is a percentage of the band, not pixels, so the band can be sized
 * in CSS and the value label — positioned from the same number — stays on its
 * bar.
 */
export type SeriesBar = {
  value: number;
  /** Percentage of the band. An empty month gets a placeholder, not a bar. */
  height: number;
};

/**
 * A month with any activity gets at least 6% so a single watch is still a
 * visible mark rather than a hairline; an empty month gets 2%, which the chart
 * draws as a dashed placeholder instead of a bar.
 */
const MIN_BAR_HEIGHT = 6;
const EMPTY_BAR_HEIGHT = 2;

export function seriesBar(value: number, seriesMax: number): SeriesBar {
  if (value <= 0) return { value: 0, height: EMPTY_BAR_HEIGHT };
  // Clamped at both ends. A 0 maximum would otherwise divide to Infinity, and
  // a maximum below the month's own value would draw a bar taller than the
  // band — taking the value label, which is positioned from this same number,
  // out of the chart with it.
  const height = Math.min(Math.max((value / Math.max(seriesMax, 1)) * 100, MIN_BAR_HEIGHT), 100);
  return { value, height };
}

/** The tallest value in a series, never less than 1 so it is safe to divide by. */
export const seriesMax = (values: readonly number[]): number => Math.max(1, ...values);
