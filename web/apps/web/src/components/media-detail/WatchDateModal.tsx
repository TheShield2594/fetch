import { useEffect, useState } from "react";
import { X } from "lucide-react";
import type { WatchLogTarget, WatchWhen } from "./detailPanelUtils";
import { useFocusTrap } from "../../hooks/useFocusTrap";
import { useScrollLock } from "../../hooks/useScrollLock";
import { useEscapeKey } from "../../hooks/useEscapeKey";
import { useExitAnimation } from "../../hooks/useExitAnimation";
import { useVisualViewport } from "../../hooks/useVisualViewport";
import { SECTION_TITLE } from "../typography";
import { localDateFromIsoDate, utcNoonIsoFromIsoDate } from "../../utils/calendarDate";
import { clampEpisode, EpisodePicker, type PickerSeason } from "./EpisodePicker";

const NO_SEASONS: readonly PickerSeason[] = [];

/** `d` as a `datetime-local` value: local wall-clock time to the minute. */
export function toDateTimeLocalValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * A `datetime-local` value as an ISO instant, or null when it is not one.
 *
 * The field has no zone, and the person filling it in means the clock on their
 * wall — so it is read as local time, which is what `new Date("YYYY-MM-DDTHH:mm")`
 * does.
 */
export function isoFromDateTimeLocal(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** True when a `YYYY-MM-DD` day had begun, where the reader is, by `now`. */
function hasAired(isoDate: string, now: number): boolean {
  const day = localDateFromIsoDate(isoDate);
  return day !== null && day.getTime() <= now;
}

export function WatchDateModal({
  target,
  seasons = NO_SEASONS,
  onLog,
  onWatchNow,
  loadAirDate,
  onClose,
}: {
  target: WatchLogTarget;
  /** The show's seasons, for an episode target. Without them the pickers fall back to typed numbers. */
  seasons?: readonly PickerSeason[];
  onLog: (when: WatchWhen, episode?: { season: number; episode: number }) => Promise<void>;
  /**
   * "Now watching": checks in rather than logging, and the watch is logged on
   * check-out. Left out where checking in is not on offer (one is already
   * running, or the target is a whole season).
   */
  onWatchNow?: ((episode?: { season: number; episode: number }) => Promise<void>) | undefined;
  /**
   * The air date of an episode chosen in the pickers, for "Release date". The
   * dialog only knows the date of a fixed episode it was handed.
   */
  loadAirDate?: ((season: number, episode: number, signal: AbortSignal) => Promise<string | null | undefined>) | undefined;
  onClose: () => void;
}) {
  // When the dialog opened: the latest a watch can be, and the day a release
  // has to have come by. Read once, so rendering stays pure.
  const [openedAt] = useState(() => Date.now());
  const [mode, setMode] = useState<"quick" | "custom">("quick");
  const [customDate, setCustomDate] = useState(() => toDateTimeLocalValue(new Date(openedAt)));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);
  const viewportStyle = useVisualViewport();

  useScrollLock();
  useEscapeKey(requestClose);

  // The episode being logged. It opens on the target — the next one up — pulled
  // inside the real season data, since that target is worked out from history
  // and can name an episode past the end of a season nobody told it the length of.
  // Clamped on every render, not once in the initial state, so the selection
  // stays inside the season data even if that data changes while this is open.
  const [chosen, setPicked] = useState(
    target.kind === "episode" ? { season: target.season, episode: target.episode } : { season: 1, episode: 1 }
  );
  const pickable = target.kind === "episode" && !target.fixed;
  const picked = pickable ? clampEpisode(seasons, chosen) : chosen;

  // The air date of the episode in the pickers, looked up as the pick changes.
  // Keyed by the pick it answers, so a stale answer reads as "still looking".
  const pickKey = `${picked.season}:${picked.episode}`;
  const [airDateAnswer, setAirDateAnswer] = useState<{ key: string; date: string | null } | null>(null);
  useEffect(() => {
    if (!pickable || !loadAirDate) return;
    const controller = new AbortController();
    const key = `${picked.season}:${picked.episode}`;
    loadAirDate(picked.season, picked.episode, controller.signal)
      .then((date) => { if (!controller.signal.aborted) setAirDateAnswer({ key, date: date ?? null }); })
      .catch(() => { if (!controller.signal.aborted) setAirDateAnswer({ key, date: null }); });
    return () => controller.abort();
  }, [pickable, loadAirDate, picked.season, picked.episode]);
  const pickedAirDate = airDateAnswer?.key === pickKey ? airDateAnswer.date : undefined;

  const releaseDate =
    target.kind === "movie" ? target.releaseDate
      : target.kind === "episode" ? (target.fixed ? target.airDate : pickedAirDate)
        : null;
  // A release still to come is not a date anyone watched it on.
  const releaseIso = releaseDate && hasAired(releaseDate, openedAt) ? utcNoonIsoFromIsoDate(releaseDate) : null;

  const episodeInfo = target.kind === "episode" ? picked : undefined;

  const run = async (action: () => Promise<void>, failure: string) => {
    setSaving(true);
    setError(null);
    try {
      await action();
      requestClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : failure);
      setSaving(false);
    }
  };
  const submit = (when: WatchWhen) => run(() => onLog(when, episodeInfo), "Failed to log watch");

  const heading =
    target.kind === "season"
      ? `When did you watch ${target.name}?`
      : target.kind === "episode" && target.fixed
        ? `When did you watch S${target.season} E${target.episode}?`
        : "When did you watch this?";

  const maxCustom = toDateTimeLocalValue(new Date(openedAt));
  const customIso = isoFromDateTimeLocal(customDate);
  // Same format, so the strings compare as the times do.
  const customInFuture = customIso !== null && customDate.slice(0, 16) > maxCustom;

  /*
   * `viewportStyle` pins this to the part of the screen the keyboard leaves
   * behind — the date field below opens one (as do the season and episode
   * fields, when there is no season data to pick from), and without it
   * this dialog is laid out against the full layout viewport and sits under
   * it. Same fix ef2e068 applied to the command palette and the three search
   * modals; these two were missed.
   *
   * `items-start` under `sm` for the same reason those four use it: once the
   * visible band is shorter than the dialog, centring puts the header above
   * the top of the scroll range, where nothing can reach it. Desktop, which
   * has the room, keeps the centring it had.
   */
  return (
    <div
      className={`overlay-scrim overlay-fade fixed inset-0 z-[60] flex items-start justify-center overflow-y-auto p-4 sm:items-center ${exiting ? "overlay-exit" : ""}`}
      style={viewportStyle}
      onClick={requestClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="watch-date-modal-title"
        tabIndex={-1}
        className={`glass-surface overlay-dialog w-full max-w-sm rounded-3xl border p-6 shadow-e3 ${exiting ? "overlay-exit" : ""}`}
        style={{ borderColor: "var(--border)", background: "var(--bg-0)" }}
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={onExitAnimationEnd}
      >
        <div className="mb-5 flex items-center justify-between gap-3">
          <h3 id="watch-date-modal-title" className={SECTION_TITLE} style={{ color: "var(--text)" }}>{heading}</h3>
          <button type="button" onClick={requestClose} aria-label="Close" className="flex-none rounded-lg p-1 hover:opacity-80" style={{ color: "var(--text-mute)" }}>
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Which episode, bounded by the seasons the show has. */}
        {pickable && (
          <div className="mb-4">
            <EpisodePicker
              idPrefix="watch-date"
              seasons={seasons}
              season={picked.season}
              episode={picked.episode}
              onChange={setPicked}
            />
          </div>
        )}
        {target.kind === "episode" && target.fixed && target.name && (
          <p className="-mt-3 mb-4 truncate text-sm" style={{ color: "var(--text-mute)" }}>{target.name}</p>
        )}

        {mode === "quick" ? (
          <div className="grid grid-cols-2 gap-2">
            {onWatchNow && (
              <button
                type="button"
                disabled={saving}
                onClick={() => void run(() => onWatchNow(episodeInfo), "Could not check in")}
                title="Checks in now; the watch is logged when you check out"
                className="btn-secondary col-span-2 flex-col items-start justify-center gap-0.5 py-3 text-left"
              >
                <span className="text-sm font-semibold">Now watching</span>
                <span className="text-2xs" style={{ color: "var(--text-mute)" }}>Check in, and log it when you finish</span>
              </button>
            )}
            <button
              type="button"
              disabled={saving}
              onClick={() => void submit({ watchedAt: new Date().toISOString() })}
              className="btn-primary py-3"
            >
              Just now
            </button>
            {target.kind === "season" ? (
              <button
                type="button"
                disabled={saving}
                onClick={() => void submit({ watchedAt: new Date().toISOString(), releaseDates: true })}
                title="Logs each episode on the day it aired"
                className="btn-secondary flex-col items-start justify-center gap-0.5 py-3 text-left"
              >
                <span className="text-sm font-semibold">Release dates</span>
                <span className="text-2xs" style={{ color: "var(--text-mute)" }}>Each episode as it aired</span>
              </button>
            ) : (
              <button
                type="button"
                // Disabled rather than hidden while an episode's date is being
                // looked up, so the grid does not reflow under the pointer.
                disabled={saving || !releaseIso}
                onClick={() => { if (releaseIso) void submit({ watchedAt: releaseIso }); }}
                title={
                  releaseIso
                    ? `Logs it on ${localDateFromIsoDate(releaseDate!)?.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}`
                    : pickable && loadAirDate && pickedAirDate === undefined
                      ? "Looking up the release date…"
                      : "No release date to log it on"
                }
                className="btn-secondary py-3"
              >
                Release date
              </button>
            )}
            <button
              type="button"
              onClick={() => setMode("custom")}
              className="btn-secondary py-3"
            >
              Other date
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={() => void submit({ watchedAt: new Date().toISOString(), dateUnknown: true })}
              title="Logs this watch without a specific date"
              className="btn-secondary flex-col items-start justify-center gap-0.5 py-3 text-left"
            >
              <span className="text-sm font-semibold">Unknown date</span>
              <span className="text-2xs" style={{ color: "var(--text-mute)" }}>No specific date</span>
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            <input
              type="datetime-local"
              aria-label="Watch date and time"
              value={customDate}
              max={maxCustom}
              onChange={(e) => setCustomDate(e.target.value)}
              className="w-full rounded-xl border px-4 py-3 text-sm field-control"
              style={{ borderColor: "var(--border-field)", background: "var(--surface)", color: "var(--text)" }}
            />
            {customInFuture && (
              <p className="text-xs text-danger">That time hasn't happened yet.</p>
            )}
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setMode("quick")}
                className="btn-secondary flex-1"
              >
                Back
              </button>
              <button
                type="button"
                disabled={saving || !customIso || customInFuture}
                onClick={() => { if (customIso) void submit({ watchedAt: customIso }); }}
                className="btn-primary flex-1"
              >
                {saving ? "Saving…" : "Log Watch"}
              </button>
            </div>
          </div>
        )}

        {error && <p role="alert" className="mt-3 text-xs text-danger">{error}</p>}
      </div>
    </div>
  );
}
