/**
 * Pac-Man ghost loading indicator. The shape is 27 coloured blocks placed on
 * the 14x14 grid defined by `.fetch-ghost__body` in index.css, plus two eyes that
 * track side to side. All of the paint comes from the active theme's accent —
 * see the block comment beside `.fetch-ghost` for the colour reasoning.
 *
 * Cells carry their `grid-area` inline rather than through a class each: the
 * name is the only thing that varies between them, so a rule per cell would be
 * 27 lines of stylesheet expressing what these three arrays already say.
 */

/** Always painted: the dome and the fringe's fixed teeth. */
const SOLID_AREAS = [
  "top0", "top1", "top2", "top3", "top4",
  "st0", "st1", "st2", "st3", "st4", "st5",
];

/** Fringe cells lit for the first half of the cycle. */
const FLICKER_A_AREAS = ["an1", "an6", "an7", "an8", "an11", "an12", "an13", "an18"];

/** Fringe cells lit for the second half — the antiphase of the set above. */
const FLICKER_B_AREAS = ["an2", "an3", "an4", "an9", "an10", "an15", "an16", "an17"];

type GhostLoaderProps = {
  /** Announced to screen readers; the ghost itself is decorative. */
  label?: string;
  /**
   * Painted size as a multiple of 140px. The element reserves 140px square
   * whatever this is, so values far from 1 leave slack around the ghost.
   */
  scale?: number;
  /** Applied to the wrapper, for positioning the loader in its container. */
  className?: string;
};

export function GhostLoader({ label = "Loading…", scale, className = "" }: GhostLoaderProps) {
  return (
    <div role="status" aria-live="polite" className={`flex justify-center ${className}`}>
      <div
        className="fetch-ghost"
        aria-hidden="true"
        style={scale === undefined ? undefined : { "--fetch-ghost-scale": scale } as React.CSSProperties}
      >
        <div className="fetch-ghost__body">
          {SOLID_AREAS.map((area) => (
            <div key={area} className="fetch-ghost__cell" style={{ gridArea: area }} />
          ))}
          {FLICKER_A_AREAS.map((area) => (
            <div
              key={area}
              className="fetch-ghost__cell fetch-ghost__cell--flicker-a"
              style={{ gridArea: area }}
            />
          ))}
          {FLICKER_B_AREAS.map((area) => (
            <div
              key={area}
              className="fetch-ghost__cell fetch-ghost__cell--flicker-b"
              style={{ gridArea: area }}
            />
          ))}
          <div className="fetch-ghost__eye" />
          <div className="fetch-ghost__eye fetch-ghost__eye--right" />
          <div className="fetch-ghost__pupil" />
          <div className="fetch-ghost__pupil fetch-ghost__pupil--right" />
        </div>
        <div className="fetch-ghost__shadow" />
      </div>
      <span className="sr-only">{label}</span>
    </div>
  );
}
