import { ChevronLeft, ChevronRight, Sparkles } from "lucide-react";
import { kindWord, metaRun, PosterCard, PosterCardSkeleton } from "../PosterCard";
import { CarouselTrack } from "../CarouselTrack";
import { useHorizontalScroll } from "../carousel-utils";
import type { TrendingMeta } from "../../api";
import { KICKER } from "../typography";

/**
 * A horizontally-scrolling rail of recommended titles inside the detail panel,
 * drawn as poster cards with scroll arrows that appear only when the row
 * overflows. Renders a skeleton while loading and nothing when there is nothing
 * to recommend.
 */
export function RecommendationsSection({
  items, loading, onSelect,
}: {
  items: TrendingMeta[];
  loading: boolean;
  onSelect: (item: TrendingMeta) => void;
}) {
  const { ref, canScrollLeft, canScrollRight, scroll } = useHorizontalScroll();

  if (loading) {
    return (
      <div>
        <h3 className={`mb-3 flex items-center gap-2 ${KICKER}`} style={{ color: "var(--text-mute)" }}>
          <Sparkles className="h-3.5 w-3.5" /> More like this
        </h3>
        <div className="flex gap-3 overflow-hidden">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="w-28 flex-none">
              <PosterCardSkeleton />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (items.length === 0) return null;

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <h3 className={`flex items-center gap-2 ${KICKER}`} style={{ color: "var(--text-mute)" }}>
          <Sparkles className="h-3.5 w-3.5" /> More like this
        </h3>
        {/* Mounted even when the row fits, so the cluster fades with layout
            changes instead of blinking; disabled buttons keep it untabbable. */}
        <div
          className={`flex items-center gap-1 transition-opacity duration-slow ${canScrollLeft || canScrollRight ? "" : "pointer-events-none opacity-0"}`}
          aria-hidden={!canScrollLeft && !canScrollRight}
        >
          <button
            type="button"
            onClick={() => scroll("left")}
            disabled={!canScrollLeft}
            className="flex h-6 w-6 items-center justify-center rounded-full transition-[transform,opacity] duration-base disabled:opacity-30 disabled:cursor-default active:scale-95"
            style={{ border: "1px solid var(--border-strong)", background: "var(--bg-1)", color: "var(--text-dim)" }}
            aria-label="Scroll recommendations left"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            onClick={() => scroll("right")}
            disabled={!canScrollRight}
            className="flex h-6 w-6 items-center justify-center rounded-full transition-[transform,opacity] duration-base disabled:opacity-30 disabled:cursor-default active:scale-95"
            style={{ border: "1px solid var(--border-strong)", background: "var(--bg-1)", color: "var(--text-dim)" }}
            aria-label="Scroll recommendations right"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
      <CarouselTrack
        scrollRef={ref}
        canScrollLeft={canScrollLeft}
        canScrollRight={canScrollRight}
        className="gap-3"
      >
        {items.map((item) => (
          // The shared card at 7rem: the same frame, radius, focus ring and
          // caption as every other poster, only smaller.
          <PosterCard
            key={`${item.type}:${item.id}`}
            poster={item.poster}
            name={item.name}
            onOpen={() => onSelect(item)}
            sizes="112px"
            className="w-28 flex-none"
            meta={metaRun(kindWord(item.type), item.year)}
          />
        ))}
      </CarouselTrack>
    </div>
  );
}
