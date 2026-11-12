import { Lock } from "lucide-react";
import type { Profile } from "../../api";

const CHIP = "inline-flex flex-none items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold";

/**
 * What a profile is, in words: the one in use, the primary one (it owns the
 * addons others can share), a kids profile, PIN-protected. The same chips on
 * the picker, the profile cards and the profile page.
 */
export function ProfileBadges({
  profile,
  isActive = false,
  isPrimary = false,
  showPin = true,
  className = "",
}: {
  profile: Pick<Profile, "hasPin" | "parental">;
  isActive?: boolean | undefined;
  isPrimary?: boolean | undefined;
  showPin?: boolean | undefined;
  className?: string | undefined;
}) {
  const isKids = profile.parental?.isKidsProfile === true;
  if (!isActive && !isPrimary && !isKids && !(showPin && profile.hasPin)) return null;
  return (
    <span className={`flex flex-wrap items-center gap-1.5 ${className}`}>
      {isActive && <span className={`${CHIP} bg-accent-500/10 text-accent-text`}>In use</span>}
      {isPrimary && (
        <span className={CHIP} style={{ background: "var(--surface-strong)", color: "var(--text-dim)" }}>Primary</span>
      )}
      {isKids && <KidsChip />}
      {showPin && profile.hasPin && (
        <span className={CHIP} style={{ background: "var(--surface-strong)", color: "var(--text-dim)" }}>
          <Lock size={11} aria-hidden="true" /> PIN
        </span>
      )}
    </span>
  );
}

/** "Kids", on its own: the rail and the profile menu wear it beside the name. */
export function KidsChip() {
  return (
    <span className={CHIP} style={{ background: "color-mix(in srgb, var(--status-ok) 16%, transparent)", color: "var(--status-ok)" }}>
      Kids
    </span>
  );
}
