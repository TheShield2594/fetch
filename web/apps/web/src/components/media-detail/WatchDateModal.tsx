import { useState } from "react";
import { X } from "lucide-react";
import type { WatchLogTarget } from "./detailPanelUtils";
import { useFocusTrap } from "../../hooks/useFocusTrap";
import { useScrollLock } from "../../hooks/useScrollLock";
import { useEscapeKey } from "../../hooks/useEscapeKey";
import { useExitAnimation } from "../../hooks/useExitAnimation";
import { useVisualViewport } from "../../hooks/useVisualViewport";
import { SECTION_TITLE } from "../typography";
import { utcNoonIsoFromIsoDate } from "../../utils/calendarDate";
import { clampEpisode, EpisodePicker, type PickerSeason } from "./EpisodePicker";

const NO_SEASONS: readonly PickerSeason[] = [];

export function WatchDateModal({
  target,
  seasons = NO_SEASONS,
  onLog,
  onClose,
}: {
  target: WatchLogTarget;
  /** The show's seasons, for an episode target. Without them the pickers fall back to typed numbers. */
  seasons?: readonly PickerSeason[];
  onLog: (date: string, episode?: { season: number; episode: number }, dateUnknown?: boolean) => Promise<void>;
  onClose: () => void;
}) {
  const [mode, setMode] = useState<"quick" | "custom">("quick");
  const [customDate, setCustomDate] = useState(() => new Date().toISOString().slice(0, 10));
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
  const picked = clampEpisode(seasons, chosen);

  const submit = async (dateIso: string, dateUnknown?: boolean) => {
    setSaving(true);
    setError(null);
    try {
      const episodeInfo = target.kind === "episode" ? picked : undefined;
      await onLog(dateIso, episodeInfo, dateUnknown);
      requestClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to log watch");
      setSaving(false);
    }
  };

  const releaseDate = target.kind === "movie" ? target.releaseDate : null;

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
        <div className="mb-5 flex items-center justify-between">
          <h3 id="watch-date-modal-title" className={SECTION_TITLE} style={{ color: "var(--text)" }}>When did you watch this?</h3>
          <button type="button" onClick={requestClose} aria-label="Close" className="rounded-lg p-1 hover:opacity-80" style={{ color: "var(--text-mute)" }}>

            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Which episode, bounded by the seasons the show has. */}
        {target.kind === "episode" && (
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

        {mode === "quick" ? (
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              disabled={saving}
              onClick={() => void submit(new Date().toISOString())}
              className="btn-primary py-3"
            >
              Just finished
            </button>
            {releaseDate && (
              <button
                type="button"
                disabled={saving}
                onClick={() => {
                  // A partial release date ("2019", "") names no day to log, so
                  // the button does nothing rather than throwing a RangeError
                  // out of the handler.
                  const iso = utcNoonIsoFromIsoDate(releaseDate);
                  if (iso) void submit(iso);
                }}
                className="btn-secondary py-3"
              >
                Release date
              </button>
            )}
            <button
              type="button"
              disabled={saving}
              onClick={() => void submit(new Date().toISOString(), true)}
              title="Logs this watch without a specific date"
              className="btn-secondary flex-col items-start justify-center gap-0.5 py-3 text-left"
            >
              <span className="text-sm font-semibold">Unknown date</span>
              <span className="text-2xs" style={{ color: "var(--text-mute)" }}>Logs without a specific date</span>
            </button>
            <button
              type="button"
              onClick={() => setMode("custom")}
              className="btn-secondary py-3"
            >
              Other date
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            <input
              type="date"
              aria-label="Watch date"
              value={customDate}
              max={new Date().toISOString().slice(0, 10)}
              onChange={(e) => setCustomDate(e.target.value)}
              className="w-full rounded-xl border px-4 py-3 text-sm field-control"
              style={{ borderColor: "var(--border-field)", background: "var(--surface)", color: "var(--text)" }}
            />
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
                disabled={saving || !customDate}
                onClick={() => {
                  const iso = utcNoonIsoFromIsoDate(customDate);
                  if (iso) void submit(iso);
                }}
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
