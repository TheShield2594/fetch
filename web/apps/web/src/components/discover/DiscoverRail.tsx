import { useEffect, type ReactNode } from "react";
import type { TrendingMeta } from "../../api";
import { CarouselTrack } from "../CarouselTrack";
import { useHorizontalScroll } from "../carousel-utils";
import { ScrollArrows } from "../ScrollArrows";
import { SectionError } from "../SectionError";
import { SECTION_TITLE } from "../typography";
import { CarouselSkeleton } from "./CarouselSkeleton";
import { DiscoveryCard, type DiscoveryItem } from "./DiscoveryCard";

const noop = () => {};

/**
 * One row of Discover, with the reason it is there said under its title.
 *
 * A rail of posters with no stated reason is a shelf in a shop: the viewer has
 * to guess why these titles and not others. "Because you watched Severance" is
 * the whole pitch in four words, and it is what makes the page read as
 * curated rather than as whatever an endpoint returned.
 *
 * A rail with nothing in it and nothing to report draws nothing at all — an
 * empty "Because you watched…" is not news.
 */
export function DiscoverRail({
  title,
  reason,
  items,
  loading = false,
  failed = false,
  onRetry,
  reasons,
  marker,
  action,
  empty,
  onSelect,
}: {
  title: string;
  reason: ReactNode;
  items: TrendingMeta[];
  loading?: boolean | undefined;
  failed?: boolean | undefined;
  onRetry?: (() => void) | undefined;
  /** A line under each card — an AI pick's reason. */
  reasons?: Record<string, string> | undefined;
  marker?: ReactNode | undefined;
  /** A link on the title line — "See all". */
  action?: ReactNode | undefined;
  /** What to say when the rail loaded and came back empty; omitted hides the rail. */
  empty?: ReactNode | undefined;
  onSelect: (item: DiscoveryItem) => void;
}) {
  const scroll = useHorizontalScroll();
  const { checkScroll } = scroll;
  useEffect(() => {
    checkScroll();
  }, [items.length, loading, checkScroll]);

  if (!loading && !failed && items.length === 0 && !empty) return null;

  return (
    <section>
      <div className="mb-3 flex items-end justify-between gap-4">
        <div className="min-w-0">
          <h2 className={SECTION_TITLE} style={{ color: "var(--text)" }}>
            {title}
          </h2>
          <p className="mt-0.5 truncate text-sm" style={{ color: "var(--text-dim)" }}>
            {reason}
          </p>
        </div>
        <div className="flex flex-none items-center gap-3">
          {action}
          {!loading && !failed && items.length > 0 && (
            <ScrollArrows canScrollLeft={scroll.canScrollLeft} canScrollRight={scroll.canScrollRight} onScroll={scroll.scroll} />
          )}
        </div>
      </div>
      {loading ? (
        <CarouselSkeleton />
      ) : failed ? (
        <SectionError message={`Couldn't load ${title.toLowerCase()}.`} onRetry={onRetry ?? noop} />
      ) : items.length === 0 ? (
        empty
      ) : (
        <CarouselTrack
          scrollRef={scroll.ref}
          canScrollLeft={scroll.canScrollLeft}
          canScrollRight={scroll.canScrollRight}
          className="content-in gap-4"
        >
          {items.map((item, index) => (
            <DiscoveryCard
              key={item.id}
              item={item}
              eager={index < 4}
              reason={reasons?.[item.id]}
              marker={marker}
              onSelect={onSelect}
            />
          ))}
        </CarouselTrack>
      )}
    </section>
  );
}
