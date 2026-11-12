import { useState } from "react";
import { Download } from "lucide-react";
import { ThemePicker } from "../ThemePicker";
import { MANUAL_INSTALL_HINT, useInstallOffer } from "../../hooks/useInstallOffer";

/**
 * Settings → Appearance: the theme, and installing the app.
 *
 * The theme picker used to be a palette button in the header of every page,
 * on every viewport — the most prominent control on a phone, for a choice most
 * people make once. It lives here now, and in the profile menu. The choice is
 * saved to the profile, so it follows the person to another browser, and two
 * profiles in one browser each keep their own.
 */
export function AppearanceSettings() {
  const { state, install } = useInstallOffer();
  const [hint, setHint] = useState<string | null>(null);

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <ThemePicker layout="cards" />
        <p className="text-xs" style={{ color: "var(--text-mute)" }}>
          Saved to this profile, so it follows you to another browser.
        </p>
      </div>

      <div className="border-t pt-6" style={{ borderColor: "var(--border)" }}>
        <h3 className="text-sm font-semibold" style={{ color: "var(--text)" }}>Install the app</h3>
        <p className="mt-1 text-sm" style={{ color: "var(--text-dim)" }}>
          {state === "installed"
            ? "You’re using Fetch as an installed app."
            : state === "needs-https"
              ? "Browsers allow installing, offline use and notifications only from an https:// address or localhost. Serve Fetch through a reverse proxy with a certificate to turn all three on."
              : "Installed, Fetch opens full screen, works offline and can notify you."}
        </p>
        {(state === "prompt" || state === "ios" || state === "unavailable") && (
          <button
            type="button"
            className="btn-secondary btn-sm mt-3"
            onClick={() =>
              void install().then((prompted) => {
                if (!prompted) setHint(state === "ios" ? MANUAL_INSTALL_HINT.ios : MANUAL_INSTALL_HINT.other);
              })
            }
          >
            <Download size={16} aria-hidden="true" />
            {state === "ios" ? "Add to Home Screen" : "Install Fetch"}
          </button>
        )}
        {hint && (
          <p role="status" className="mt-2 text-xs" style={{ color: "var(--text-dim)" }}>
            {hint}
          </p>
        )}
      </div>
    </div>
  );
}
