import { Sparkles } from "lucide-react";
import { setExtrasEnabled, useExtrasEnabled } from "../../hooks/useExtras";

/** The opt-in for AI recommendations. Off, their section leaves Settings; nothing is deleted. */
export function ExtrasSettings() {
  const enabled = useExtrasEnabled();
  return (
    <label htmlFor="pref-extras" className="flex items-start gap-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-3.5 cursor-pointer transition-colors hover:bg-[var(--surface-strong)]">
      <input
        id="pref-extras"
        type="checkbox"
        role="switch"
        checked={enabled}
        onChange={(e) => setExtrasEnabled(e.target.checked)}
        className="switch-control mt-0.5"
      />
      <div>
        <span className="text-sm font-medium flex items-center gap-2" style={{ color: "var(--text)" }}>
          <Sparkles size={14} />
          Show AI recommendations
        </span>
        <p className="mt-0.5 text-xs" style={{ color: "var(--text-mute)" }}>
          Adds the AI recommendations section to Settings on this device. Off by default.
        </p>
      </div>
    </label>
  );
}
