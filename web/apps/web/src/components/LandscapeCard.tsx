import type { ReactNode } from "react";
import { Check, Play, RotateCw, X } from "lucide-react";
import { Poster } from "./Poster";

/**
 * The landscape card: the other half of the card system, beside `PosterCard`.
 *
 * Where a poster card is a title in a library, this is a title you are part-way
 * through — so the still is the subject, the caption sits on it, and the
 * progress runs along the bottom where a scrubber would. Its slots mirror the
 * poster card's: `eyebrow` and `title` for the caption, `progress`, one
 * `action`, and a `dismiss` in the corner.
 *
 * Two sizes. `rail` is the compact card a row scrolls through; `lead` is the
 * one item a page opens on, drawn large with the same parts at a larger scale
 * rather than a different design.
 *
 * Two buttons rather than one: the card body opens the title, and the trailing
 * disc does the action. They cannot be nested, so the body's hit area is an
 * absolutely positioned button under the caption and the caption is
 * `pointer-events-none` above it, with the action opting back in.
 */

export type LandscapeAction = {
  label: string;
  onClick: () => void;
  busy?: boolean | undefined;
  /**
   * What the action does, drawn. Starting the next episode is a play glyph;
   * finishing the title you are on is a check — a play button there would
   * promise playback the web app can't give.
   */
  icon: "play" | "check";
};

export function LandscapeCard({
  art,
  artIsBackdrop,
  title,
  eyebrow,
  progress,
  onOpen,
  openLabel,
  action,
  dismiss,
  size = "rail",
  eager = false,
  className = "",
}: {
  art: string | null | undefined;
  /** A real 16:9 still takes the backdrop ladder; a poster standing in for one keeps the poster ladder. */
  artIsBackdrop: boolean;
  title: string;
  eyebrow?: ReactNode | undefined;
  /** A `ProgressRuler` with `onArt` set. */
  progress?: ReactNode | undefined;
  onOpen: () => void;
  openLabel: string;
  action?: LandscapeAction | undefined;
  dismiss?: { label: string; onClick: () => void } | undefined;
  size?: "rail" | "lead";
  eager?: boolean | undefined;
  /** Width and layout, which belong to the row the card sits in. */
  className?: string | undefined;
}) {
  const lead = size === "lead";
  const busy = action?.busy ?? false;

  return (
    <div
      className={`relative isolate overflow-hidden rounded-2xl ${className}`.trimEnd()}
      style={{ aspectRatio: "16 / 9", background: "var(--surface)" }}
    >
      <Poster
        src={art ?? undefined}
        alt=""
        className="absolute inset-0 h-full w-full"
        eager={eager}
        sizes={lead ? "(min-width: 1024px) 480px, 100vw" : "240px"}
        variant={artIsBackdrop ? "backdrop" : "poster"}
      />
      {/* The scrim starts part-way down rather than at the top: a caption needs
          a floor under it, and darkening the whole frame to get one takes the
          picture down with it. The lead's caption is taller, so its scrim
          starts higher. */}
      <div
        aria-hidden="true"
        className="absolute inset-0"
        style={{
          background: `linear-gradient(180deg, rgba(0,0,0,0) ${lead ? 25 : 35}%, rgba(0,0,0,0.8) 100%)`,
        }}
      />
      <button
        type="button"
        onClick={onOpen}
        aria-label={openLabel}
        className="absolute inset-0 h-full w-full rounded-2xl focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-inset"
      />
      <div
        className={`pointer-events-none absolute flex items-end ${lead ? "inset-x-5 bottom-5 gap-4" : "inset-x-3 bottom-3 gap-2.5"}`}
      >
        <div className="min-w-0 flex-1">
          {eyebrow && (
            <p className="eyebrow truncate" style={{ color: "rgba(255,255,255,0.72)" }}>
              {eyebrow}
            </p>
          )}
          <p
            className={`truncate font-bold text-white ${lead ? "mt-1 text-[1.5rem] leading-tight" : "mt-0.5 text-[0.9375rem]"}`}
          >
            {title}
          </p>
          {progress && <div className={lead ? "mt-3 max-w-[16rem]" : "mt-2 max-w-[10rem]"}>{progress}</div>}
        </div>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            disabled={busy}
            aria-label={action.label}
            title={action.label}
            className={`pointer-events-auto flex flex-none items-center justify-center rounded-full transition-transform duration-fast active:scale-95 disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus ${lead ? "h-12 w-12" : "h-9 w-9"}`}
            // White rather than the accent: it sits on artwork of unknown
            // colour, and white is the only fill legible over all of it.
            style={{ background: "rgba(255,255,255,0.92)", color: "#000" }}
          >
            {busy ? (
              <RotateCw className={`${lead ? "h-5 w-5" : "h-4 w-4"} animate-spin`} />
            ) : action.icon === "check" ? (
              <Check className={lead ? "h-5 w-5" : "h-4 w-4"} strokeWidth={2.5} />
            ) : (
              // Nudged right by a pixel: a triangle's optical centre sits left
              // of its bounding box.
              <Play className={`${lead ? "h-5 w-5" : "h-4 w-4"} translate-x-[1px] fill-current`} />
            )}
          </button>
        )}
      </div>
      {dismiss && (
        <button
          type="button"
          onClick={dismiss.onClick}
          disabled={busy}
          aria-label={dismiss.label}
          title={dismiss.label}
          // Top-right and quiet: the action nobody should hit by accident sits
          // away from the one they came for.
          className="absolute right-2.5 top-2.5 flex h-8 w-8 items-center justify-center rounded-full text-white transition-colors duration-fast hover:bg-black/70 disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          style={{ background: "rgba(0,0,0,0.55)" }}
        >
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  );
}
