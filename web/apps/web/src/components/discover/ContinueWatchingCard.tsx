import { Check, Play, RotateCw } from "lucide-react";
import type { SeriesProgress } from "../../api";
import { POSTER_CARD_SIZES } from "../Poster";
import { PosterCard } from "../PosterCard";
import { ProgressRuler } from "../ProgressRuler";
import { computeProgressSummary, seasonCountSuffix } from "./series-progress";

/**
 * One in-progress series in Discover's Continue Watching rail: the shared
 * poster card with its progress slot and its quick action filled.
 *
 * The quick action is the same white disc the Shelf's Up Next uses, for the
 * same reason — it sits on artwork of unknown colour, and white is the only
 * fill legible over all of it.
 */
export function ContinueWatchingCard({
  s,
  eager,
  isMarking,
  isDone,
  onMarkNext,
  onSelect,
}: {
  s: SeriesProgress;
  eager: boolean;
  isMarking: boolean;
  isDone: boolean;
  onMarkNext: () => void;
  onSelect: () => void;
}) {
  const summary = computeProgressSummary(s);
  const next = `S${s.nextSeason}:E${s.nextEpisode}`;
  const actionLabel = isMarking ? "Marking" : isDone ? "Marked" : `Mark ${next}`;
  return (
    // One card, two controls — grouped and labelled so a screen reader announces
    // them as belonging to this series rather than as loose buttons in a row.
    <div role="group" aria-label={s.name} className="w-poster-card flex-none">
      <PosterCard
        poster={s.poster}
        name={s.name}
        onOpen={onSelect}
        eager={eager}
        sizes={POSTER_CARD_SIZES}
        meta={`S${s.lastSeason}:E${s.lastEpisode}${seasonCountSuffix(s.totalSeasons)}`}
        progress={
          summary ? (
            <ProgressRuler
              value={summary.watched}
              total={summary.total}
              discrete
              onArt
              label={`${summary.label}: ${summary.watched} of ${summary.total} episodes watched`}
              // Short of the frame's width so it never runs under the action.
              className="max-w-[calc(100%-3rem)]"
            />
          ) : undefined
        }
        quickAction={
          <button
            type="button"
            disabled={isMarking || isDone}
            onClick={onMarkNext}
            aria-label={actionLabel}
            title={actionLabel}
            className="pointer-events-auto flex h-9 w-9 items-center justify-center rounded-full transition-transform duration-fast active:scale-95 disabled:opacity-70 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            style={{ background: "rgba(255,255,255,0.92)", color: "#000" }}
          >
            {isDone ? (
              <Check className="h-4 w-4" strokeWidth={2.5} />
            ) : isMarking ? (
              <RotateCw className="h-4 w-4 animate-spin" />
            ) : (
              <Play className="h-4 w-4 translate-x-[1px] fill-current" />
            )}
          </button>
        }
      />
    </div>
  );
}
