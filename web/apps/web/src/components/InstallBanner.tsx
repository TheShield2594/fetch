import { useState } from "react";
import { Download, ShieldAlert, X } from "lucide-react";
import { MANUAL_INSTALL_HINT, useInstallOffer } from "../hooks/useInstallOffer";

const DISMISSED_KEY = "fetch:install-banner-dismissed";
// The header button's fortnight snooze. Someone who dismissed that has already
// said no once, and the banner is not a second way to ask.
const LEGACY_SNOOZE_KEY = "fetch:install-snooze-until";

function wasDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === "1" || Number(localStorage.getItem(LEGACY_SNOOZE_KEY) ?? 0) > Date.now();
  } catch {
    return false;
  }
}

/**
 * The offer to install Fetch as an app, once.
 *
 * It was a pair of round buttons in the header — Install and a dismiss "×" —
 * on every page of every visit, which made it two of the four controls a phone
 * saw at the top of the screen. Installing is something a person does at most
 * once per device, so it is asked once, here, in the page; dismissed, it is
 * gone for good from the page and stays a row in the profile menu and in
 * Settings → Appearance.
 */
export function InstallBanner() {
  const { state, install } = useInstallOffer();
  const [dismissed, setDismissed] = useState(wasDismissed);
  const [hint, setHint] = useState<string | null>(null);

  if (dismissed || state === "installed" || state === "unavailable") return null;

  const dismiss = () => {
    try {
      localStorage.setItem(DISMISSED_KEY, "1");
    } catch {
      // Not persisted; it is still gone for this visit.
    }
    setDismissed(true);
  };

  const onInstall = async () => {
    if (!(await install())) setHint(state === "ios" ? MANUAL_INSTALL_HINT.ios : MANUAL_INSTALL_HINT.other);
  };

  const needsHttps = state === "needs-https";
  const Icon = needsHttps ? ShieldAlert : Download;

  return (
    <aside
      aria-label="Install Fetch"
      className="mb-5 flex items-start gap-3 rounded-2xl px-4 py-3 text-sm"
      style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--text)" }}
    >
      <Icon className="mt-0.5 h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--text-dim)" }} />
      <div className="min-w-0 flex-1">
        {needsHttps ? (
          <p>
            <span className="font-semibold">Fetch can’t be installed from this address.</span>{" "}
            <span style={{ color: "var(--text-dim)" }}>
              Browsers allow installing, offline use and notifications only from an https:// address or
              localhost. Serve Fetch through a reverse proxy with a certificate to turn all three on.
            </span>
          </p>
        ) : (
          <p>
            <span className="font-semibold">Install Fetch as an app.</span>{" "}
            <span style={{ color: "var(--text-dim)" }}>It opens full screen, works offline and can notify you.</span>
          </p>
        )}
        {hint && (
          <p role="status" className="mt-1 text-xs" style={{ color: "var(--text-dim)" }}>
            {hint}
          </p>
        )}
        {!needsHttps && (
          <button type="button" onClick={() => void onInstall()} className="btn-primary btn-xs mt-2">
            {state === "ios" ? "Add to Home Screen" : "Install"}
          </button>
        )}
      </div>
      <button
        type="button"
        onClick={dismiss}
        aria-label={needsHttps ? "Dismiss install notice" : "Dismiss install offer"}
        className="tap-target -mr-1 -mt-0.5 flex h-8 w-8 flex-none items-center justify-center rounded-full transition-colors hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
        style={{ color: "var(--text-mute)" }}
      >
        <X className="h-4 w-4" />
      </button>
    </aside>
  );
}
