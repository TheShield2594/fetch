import { Settings } from "lucide-react";
import { Link } from "react-router";
import { useSettingsHealth } from "../hooks/useSettingsHealth";
import { healthDotClass, type HealthTone } from "./settings/health";
import { prefetchRoute } from "../utils/routePrefetch";
import { useIsKidsProfile } from "../hooks/useProfile";

/**
 * Settings, one tap away on a phone.
 *
 * Below `sm` Settings was the last row of the tab bar's More sheet — two taps
 * to reach the page whose status board answers "is my sync working?". This
 * puts it in the header beside the profile, and carries the worst state the
 * board would show as a dot, so a broken source is visible from every page
 * without opening anything. The rail is the same control from `sm` up.
 *
 * The chunk is warmed on touch rather than on idle: it is the largest route
 * and most sessions never open it (see `IDLE_PREFETCH`).
 */
export function PhoneSettingsButton() {
  const { sections } = useSettingsHealth();
  const tones = Object.values(sections).map((h) => h.tone);
  const worst: HealthTone | null = tones.includes("bad") ? "bad" : tones.includes("warn") ? "warn" : null;
  const warm = () => prefetchRoute("/settings");
  // A kids profile is kept out of Settings (see KidsGate): no button to it.
  const isKids = useIsKidsProfile();
  if (isKids) return null;

  return (
    <Link
      to="/settings"
      onPointerDown={warm}
      onFocus={warm}
      aria-label={worst ? `Settings, ${worst === "bad" ? "a source has a problem" : "a source needs attention"}` : "Settings"}
      // The theme toggle's shape beside it, so the header's round buttons read
      // as one set.
      className="relative flex h-10 w-10 flex-none items-center justify-center rounded-full transition-[background-color,transform] duration-fast active:scale-95 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset sm:hidden"
      style={{ border: "1px solid var(--border-strong)", background: "var(--surface)", color: "var(--text)" }}
    >
      <Settings className="h-4 w-4" />
      {worst && <span aria-hidden="true" className={`${healthDotClass(worst)} absolute -right-0.5 -top-0.5`} />}
    </Link>
  );
}
