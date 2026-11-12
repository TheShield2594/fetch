import { Clock, Film, Trash2, Tv } from "lucide-react";
import { Poster } from "../Poster";
import type { WatchEvent } from "../../api";
import { watchEventLabel, watchEventTitle } from "../../utils/watchEvents";
import { KICKER } from "../typography";

/**
 * A title's past watch events, newest first, each row carrying an icon for its
 * media type and a delete affordance. The most recent row animates in so a
 * freshly logged watch reads as arriving; renders a skeleton while loading.
 */
export function WatchHistorySection({
  history,
  loading,
  onDeleteEvent,
}: {
  history: WatchEvent[];
  loading: boolean;
  onDeleteEvent: (event: WatchEvent) => void;
}) {
  return (
    <div>
      {/* No "Log a Watch" button beside the heading: the panel's primary
          action is that button, and two of them a scroll apart read as two
          different things. */}
      <h3 className={`mb-3 flex items-center gap-2 ${KICKER}`} style={{ color: "var(--text-mute)" }}>
        <Clock className="h-3.5 w-3.5" /> Watch history
      </h3>

      {loading ? (
        <div className="space-y-2">{[1, 2, 3].map((i) => <div key={i} className="skeleton h-14 rounded-lg" />)}</div>
      ) : history.length === 0 ? (
        <p
          className="rounded-xl border py-5 text-center text-sm"
          style={{ background: "var(--surface)", borderColor: "var(--border)", color: "var(--text-mute)" }}
        >
          No watch history yet
        </p>
      ) : (
        <div className="space-y-1.5">
          {history.map((event, index) => (
            <div
              key={event.id}
              className={`group flex items-center gap-3 rounded-lg border px-3 py-2 ${index === 0 ? "animate-slide-in" : ""}`}
              style={{ background: "var(--surface)", borderColor: "var(--border)" }}
            >
              <div className="h-10 w-7 shrink-0 overflow-hidden rounded-md" style={{ background: "var(--surface-strong)" }}>
                {event.poster ? (
                  <Poster src={event.poster} alt={watchEventTitle(event)} className="h-full w-full" sizes="28px" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center">
                    {event.type === "episode" ? (
                      <Tv className="h-3.5 w-3.5" style={{ color: "var(--text-mute)" }} />
                    ) : (
                      <Film className="h-3.5 w-3.5" style={{ color: "var(--text-mute)" }} />
                    )}
                  </div>
                )}
              </div>
              <div className="min-w-0 flex-1">
                {event.type === "episode" && event.season != null && event.episode != null ? (
                  <>
                    <p className="truncate text-sm" style={{ color: "var(--text)" }}>{watchEventTitle(event)}</p>
                    <p className="meta-row" style={{ color: "var(--text-mute)" }}>
                      S{String(event.season).padStart(2, "0")}:E{String(event.episode).padStart(2, "0")}
                    </p>
                  </>
                ) : (
                  <p className="truncate text-sm" style={{ color: "var(--text)" }}>{watchEventTitle(event)}</p>
                )}
              </div>
              <time className="meta-row shrink-0" style={{ color: "var(--text-mute)" }}>
                {event.dateUnknown
                  ? "Unknown date"
                  : new Date(event.watchedAt).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}
              </time>
              <button
                type="button"
                onClick={() => onDeleteEvent(event)}
                className="shrink-0 rounded p-1 text-[var(--text-mute)] opacity-100 sm:opacity-60 sm:group-hover:opacity-100 focus-visible:opacity-100 hover:opacity-100 hover:bg-rose-500/10 hover:text-danger transition-[opacity,color,background-color] duration-fast"
                aria-label={`Remove watch of ${watchEventLabel(event)}`}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
