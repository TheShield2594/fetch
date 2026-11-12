import type { ShelfEntry } from "../pages/shelf";
import { LandscapeCard, type LandscapeAction } from "./LandscapeCard";
import { ProgressRuler } from "./ProgressRuler";

/*
 * The in-progress card, wherever a title you are part-way through is drawn —
 * the Shelf's Up next, and Discover's pick-up strip. One card, one ruler (a
 * tick per episode of the season you are in) and one action, so the same show
 * reads the same on both pages.
 */

/**
 * One card in Up Next: what it is, and the one thing its action does.
 *
 * Continue Watching and Up Next were two rows of the same card a heading
 * apart, and the difference between them — a resume point versus the next
 * episode — is the action, not the section. So they are one ordered row: what
 * you paused comes first, newest pause first, then the shows whose next
 * episode is waiting, in the order the API ranks them.
 */
export type UpNextItem = {
  key: string;
  entry: ShelfEntry;
  action?: LandscapeAction | undefined;
  dismiss?: { label: string; onClick: () => void } | undefined;
};

export function UpNextCard({
  item,
  lead,
  eager,
  onOpen,
  className,
}: {
  item: UpNextItem;
  lead: boolean;
  eager: boolean;
  onOpen: () => void;
  /** Width in the row it sits in; the Shelf's lead and rail sizes by default. */
  className?: string | undefined;
}) {
  const { entry } = item;
  return (
    <LandscapeCard
      // The landscape still if the record carries one, the poster if not. A
      // poster in a 16:9 frame is cropped to its middle, which is a worse
      // picture than the still and a much better one than a grey box.
      art={entry.item?.background ?? entry.art}
      artIsBackdrop={!!entry.item?.background}
      title={entry.title}
      eyebrow={entry.meta}
      progress={
        entry.ruler ? (
          <ProgressRuler
            value={entry.ruler.value}
            total={entry.ruler.total}
            partial={entry.ruler.partial}
            discrete={entry.ruler.discrete}
            // Nothing on the card writes the count out in words — the ticks
            // are the count — so this is the only thing that can say "4 of 10
            // episodes watched" to someone who can't see them.
            label={entry.ruler.label}
            onArt
          />
        ) : undefined
      }
      onOpen={onOpen}
      openLabel={`Open details for ${entry.title}`}
      action={item.action}
      dismiss={item.dismiss}
      size={lead ? "lead" : "rail"}
      eager={eager}
      className={className ?? (lead ? "w-full" : "w-[15rem] flex-none snap-start")}
    />
  );
}
