import { metaRun, PosterCard } from "../PosterCard";
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
  const count =
    events.length > 1 ? (latest.type === "movie" ? `${events.length} plays` : `${events.length} episodes`) : null;
  return (
    <PosterCard
      poster={latest.poster}
      name={name}
      onOpen={onSelect}
      sizes={POSTER_CARD_SIZES}
      className="w-poster-card flex-none"
      // What it was and how much of it, then when — one run, where the badges
      // over the art used to be.
      meta={metaRun(latest.type === "movie" ? "Film" : range || "Show", count, timeAgo(latest.watchedAt))}
    />
  );
}
