import { useEffect, useRef } from "react";
import type { CalendarEntry } from "../../api";
import { CarouselTrack } from "../CarouselTrack";
import { useHorizontalScroll } from "../carousel-utils";
import { Poster } from "../Poster";
import { ScrollArrows } from "../ScrollArrows";
import { calendarEntryDay, calendarEntryKey, calendarEntryLabel, calendarEntryTime } from "../../utils/calendarEntry";
import { openWithTransition } from "../../utils/viewTransition";

/** "Today", "Tomorrow", or the weekday and date. */
function dayLabel(day: Date, now = new Date()): string {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const diff = Math.round((day.getTime() - today.getTime()) / 86_400_000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (Number.isNaN(diff)) return "Date unknown";
  return day.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

function UpcomingCard({ entry, onOpen }: { entry: CalendarEntry; onOpen: () => void }) {
  const artRef = useRef<HTMLDivElement>(null);
  const still = entry.still ?? entry.backdrop;
  const time = calendarEntryTime(entry);
  return (
    <button
      type="button"
      onClick={() => openWithTransition(artRef.current, onOpen)}
      aria-label={`${entry.seriesName}, ${calendarEntryLabel(entry)}. Open details`}
      className="group w-[15rem] flex-none rounded-2xl text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
    >
      {/* The still sits clean — what airs and when are said under it, not
          laid over it. */}
      <div
        ref={artRef}
        className="card-art aspect-video overflow-hidden rounded-2xl transition-transform duration-base group-hover:-translate-y-0.5 group-active:scale-[0.98]"
        style={{ background: "var(--surface)", boxShadow: "0 0 0 1px var(--border)" }}
      >
        <Poster
          src={still ?? entry.poster ?? undefined}
          alt=""
          variant={still ? "backdrop" : "poster"}
          sizes="240px"
          className="h-full w-full"
        />
      </div>
      <p className="mt-2 truncate text-[0.8125rem] font-semibold" style={{ color: "var(--text)" }}>
        {entry.seriesName}
      </p>
      <p className="meta-row mt-0.5 truncate" style={{ color: "var(--text-dim)" }}>
        {calendarEntryLabel(entry)}
        {time ? ` · ${time}` : ""}
      </p>
    </button>
  );
}

/**
 * The next fortnight, as a row of episode cards grouped under the day each
 * airs — a timeline read left to right, rather than a narrow column of rows
 * that looked clickable and weren't. Every card opens its title.
 */
export function UpcomingTimeline({
  entries,
  onOpen,
}: {
  entries: CalendarEntry[];
  onOpen: (entry: CalendarEntry) => void;
}) {
  const scroll = useHorizontalScroll();
  const { checkScroll } = scroll;
  useEffect(() => {
    checkScroll();
  }, [entries.length, checkScroll]);

  // One group per local day, whatever order the entries arrive in: the API
  // sorts by air date, but an entry's local day comes from its air instant
  // where one is known, and the two orders can disagree across a midnight.
  const byDay = new Map<string, { time: number; label: string; entries: CalendarEntry[] }>();
  for (const entry of entries) {
    const day = calendarEntryDay(entry);
    const time = day.getTime();
    const key = Number.isNaN(time) ? "unknown" : day.toDateString();
    const group = byDay.get(key);
    if (group) group.entries.push(entry);
    else byDay.set(key, { time: Number.isNaN(time) ? Infinity : time, label: dayLabel(day), entries: [entry] });
  }
  const days = [...byDay.entries()]
    .map(([key, group]) => ({ key, ...group }))
    .sort((a, b) => a.time - b.time);

  return (
    <div>
      <div className="mb-1 flex justify-end">
        <ScrollArrows canScrollLeft={scroll.canScrollLeft} canScrollRight={scroll.canScrollRight} onScroll={scroll.scroll} />
      </div>
      <CarouselTrack
        scrollRef={scroll.ref}
        canScrollLeft={scroll.canScrollLeft}
        canScrollRight={scroll.canScrollRight}
        className="gap-6"
      >
        {days.map((day) => (
          <div key={day.key} role="group" aria-label={day.label} className="flex-none">
            {/* The day on a rule that runs over its cards — the line is the
                timeline, the label the tick on it. */}
            <div className="mb-2.5 flex items-center gap-2">
              <span
                className={`eyebrow flex-none ${day.label === "Today" ? "text-accent-text" : ""}`}
                style={day.label === "Today" ? undefined : { color: "var(--text-mute)" }}
              >
                {day.label}
              </span>
              <span aria-hidden="true" className="h-px flex-1" style={{ background: "var(--border)" }} />
            </div>
            <div className="flex gap-4">
              {day.entries.map((entry) => (
                <UpcomingCard key={calendarEntryKey(entry)} entry={entry} onOpen={() => onOpen(entry)} />
              ))}
            </div>
          </div>
        ))}
      </CarouselTrack>
    </div>
  );
}
