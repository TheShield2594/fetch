import type { CalendarEntry } from "../../api";
import { Poster } from "../Poster";
import { calendarEntryDay, calendarEntryKey, calendarEntryLabel } from "../../utils/calendarEntry";

/** "Today", "Tomorrow", or the weekday and date an episode airs on. */
function airDateLabel(entry: CalendarEntry): { label: string; isToday: boolean; isTomorrow: boolean } {
  // The local day of the air time where one is known. Unreadable stays an
  // Invalid Date, which matches neither today nor tomorrow and falls through to
  // the dated label.
  const date = calendarEntryDay(entry);
  const isToday = date.toDateString() === new Date().toDateString();
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const isTomorrow = date.toDateString() === tomorrow.toDateString();

  return {
    label: isToday
      ? "Today"
      : isTomorrow
        ? "Tomorrow"
        : date.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" }),
    isToday,
    isTomorrow,
  };
}

/** The next fortnight of episodes, premieres and film releases, in Discover's narrow right-hand column. */
export function UpcomingList({ entries }: { entries: CalendarEntry[] }) {
  return (
    <div className="space-y-2">
      {entries.map((entry) => {
        const { label, isToday, isTomorrow } = airDateLabel(entry);
        return (
          <div
            key={calendarEntryKey(entry)}
            className="flex items-center gap-2.5 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-2.5 transition-colors duration-base hover:border-[var(--border-strong)] hover:bg-[var(--surface-strong)]"
          >
            <div className="h-12 w-8 flex-none overflow-hidden rounded-md" style={{ boxShadow: "0 0 0 1px var(--border)" }}>
              <Poster src={entry.poster ?? undefined} alt={entry.seriesName} className="h-full w-full" sizes="32px" />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-semibold" style={{ color: "var(--text)" }}>
                {entry.seriesName}
              </p>
              <p className="meta-row mt-0.5 truncate" style={{ color: "var(--text-dim)" }}>
                {calendarEntryLabel(entry, { withName: false })}
              </p>
            </div>
            <span
              className={`meta-row flex-none rounded-full px-2 py-1 font-semibold ${
                isToday
                  ? "bg-accent-500/15 text-accent-text"
                  : isTomorrow
                    ? "bg-amber-500/15 text-warning"
                    : ""
              }`}
              style={!isToday && !isTomorrow ? { background: "var(--surface-strong)", color: "var(--text-dim)" } : undefined}
            >
              {label}
            </span>
          </div>
        );
      })}
    </div>
  );
}
