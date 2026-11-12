import type { CheckIn } from "../../api";

/**
 * "Now Watching", while a check-in on this title is open: what is playing, and
 * the two ways to end it.
 *
 * Only that. It used to draw a full-width "Check In" button as well when
 * nothing was playing, a second copy of the square button beside the panel's
 * primary action; starting a check-in is that button's job alone now.
 */
export function CheckInBlock({
  activeCheckin,
  onCheckout,
}: {
  activeCheckin: CheckIn | null;
  onCheckout: (logWatch: boolean) => void;
}) {
  if (!activeCheckin) return null;

  return (
    <div className="rounded-xl border border-accent-500/30 bg-accent-500/5 p-4 space-y-3">
      <div className="flex items-center gap-2">
        <span className="relative flex h-2.5 w-2.5">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-accent-500" />
        </span>
        <span className="text-sm font-semibold text-accent-text">Now Watching</span>
        {activeCheckin.season != null && activeCheckin.episode != null && (
          <span className="text-xs" style={{ color: "var(--text-mute)" }}>S{String(activeCheckin.season).padStart(2, "0")}:E{String(activeCheckin.episode).padStart(2, "0")}</span>
        )}
      </div>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => onCheckout(false)}
          className="btn-secondary btn-sm flex-1"
        >
          Check Out
        </button>
        <button
          type="button"
          onClick={() => onCheckout(true)}
          className="btn-primary btn-sm flex-1"
        >
          Finished &amp; Log
        </button>
      </div>
    </div>
  );
}
