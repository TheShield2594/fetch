import { Film } from "lucide-react";
import { PosterCard } from "../PosterCard";
import { POSTER_CARD_SIZES } from "../Poster";
import { timeAgo } from "../../utils/timeAgo";
import { watchEventTitle } from "../../utils/watchEvents";
import { episodeRangeLabel, type RecentTitle } from "../../pages/history-groups";

/**
 * One title in the Recently Watched rail: what it was, how much of it, and how
 * long ago.
 *
 * A card per title, not per watch. Six episodes of one show used to be six
 * identical posters side by side, told apart only by the code in the corner;
 * `recentTitles` folds them, and the badge carries the range and the count.
 */
export function RecentlyWatchedCard({ title, onSelect }: { title: RecentTitle; onSelect: () => void }) {
  const { latest, events } = title;
  const name = watchEventTitle(latest);
  const range = latest.type === "episode" ? episodeRangeLabel(events) : "";
  return (
    <PosterCard
      poster={latest.poster}
      name={name}
      onOpen={onSelect}
      sizes={POSTER_CARD_SIZES}
      className="w-poster-card flex-none"
      overlay={
        <div className="absolute inset-x-0 bottom-0 flex flex-wrap items-center gap-1.5 bg-gradient-to-t from-black via-black/70 to-transparent px-3 pb-3 pt-12">
          {latest.type === "episode" && range ? (
            <span className="inline-block rounded px-2 py-0.5 text-xs font-semibold text-white backdrop-blur-sm" style={{ background: "var(--surface-strong)" }}>
              {range}
            </span>
          ) : latest.type === "movie" ? (
            <span className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-semibold text-claw-300 backdrop-blur-sm bg-claw-500/20">
              <Film className="h-3 w-3" /> Movie
            </span>
          ) : null}
          {events.length > 1 && (
            <span className="inline-block rounded px-2 py-0.5 text-xs font-semibold text-white backdrop-blur-sm" style={{ background: "var(--surface-strong)" }}>
              {latest.type === "movie" ? `${events.length} plays` : `${events.length} episodes`}
            </span>
          )}
        </div>
      }
    >
      <p className="mt-2.5 truncate text-sm font-semibold text-[var(--text)] transition-colors group-hover:text-claw-text">
        {name}
      </p>
      <p className="meta-row" style={{ color: "var(--text-dim)" }}>
        {timeAgo(latest.watchedAt)}
      </p>
    </PosterCard>
  );
}
