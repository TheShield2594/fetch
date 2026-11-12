import { createContext, type ReactNode, useCallback, useContext, useMemo, useState } from "react";
import { type Profile, runtimeConfig, notifyServiceWorkerToInvalidateApiCache } from "../api";

/**
 * Locks the profile in use without signing out: its access token and the
 * selection go (with the cached responses fetched as it, see
 * `runtimeConfig.clearProfileId`), and the app returns to "Who's watching?"
 * through the same `fetch:profile-locked` path an expired token takes. Opening
 * it again needs its PIN.
 *
 * Meant for a PIN profile on a shared browser, where an unlock otherwise lasts
 * the token's 12 hours.
 */
export function lockActiveProfile(): void {
  runtimeConfig.clearProfileId();
  void notifyServiceWorkerToInvalidateApiCache();
  window.dispatchEvent(new Event("fetch:profile-locked"));
}

type ProfileContextValue = {
  profile: Profile | null;
  setProfile: (profile: Profile) => void;
  /** Replaces the active profile's fields in place — an edit, not a switch. */
  patchProfile: (changes: Partial<Profile>) => void;
  switcherOpen: boolean;
  openSwitcher: () => void;
  closeSwitcher: () => void;
  /**
   * The active profile could not be read (the request failed, or the id in
   * storage names no profile). Until it is read, `profile` is null and nothing
   * is known about it, kids or not; see `KidsGate`.
   */
  profileLoadFailed?: boolean | undefined;
  /** Ask for the active profile again after a failure. */
  retryProfileLoad?: (() => void) | undefined;
  /** Set by the shell, which does the reading. */
  setProfileLoadFailed?: ((failed: boolean) => void) | undefined;
  /** Bumped by `retryProfileLoad`; the shell's read depends on it. */
  profileLoadAttempt?: number | undefined;
};

/** Exported for the few readers that must work outside a provider; everything else uses `useProfile`. */
export const ProfileContext = createContext<ProfileContextValue | null>(null);

export function ProfileProvider({
  initialProfile,
  children,
}: {
  initialProfile: Profile | null;
  children: ReactNode;
}) {
  const [profile, setProfileState] = useState<Profile | null>(initialProfile);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [profileLoadFailed, setProfileLoadFailed] = useState(false);
  const [profileLoadAttempt, setProfileLoadAttempt] = useState(0);
  const retryProfileLoad = useCallback(() => {
    setProfileLoadFailed(false);
    setProfileLoadAttempt((n) => n + 1);
  }, []);

  const setProfile = useCallback((next: Profile) => {
    runtimeConfig.setProfileId(next.id);
    setProfileState(next);
    setProfileLoadFailed(false);
    setSwitcherOpen(false);
    // Cached responses are keyed by profile id, but flush eagerly anyway so
    // a stale entry from before this profile existed can't be served.
    void notifyServiceWorkerToInvalidateApiCache();
  }, []);

  const patchProfile = useCallback((changes: Partial<Profile>) => {
    setProfileState((prev) => (prev ? { ...prev, ...changes } : prev));
  }, []);

  const openSwitcher = useCallback(() => setSwitcherOpen(true), []);
  const closeSwitcher = useCallback(() => setSwitcherOpen(false), []);

  const value = useMemo(
    () => ({
      profile,
      setProfile,
      patchProfile,
      switcherOpen,
      openSwitcher,
      closeSwitcher,
      profileLoadFailed,
      retryProfileLoad,
      setProfileLoadFailed,
      profileLoadAttempt,
    }),
    [profile, setProfile, patchProfile, switcherOpen, openSwitcher, closeSwitcher, profileLoadFailed, retryProfileLoad, profileLoadAttempt]
  );

  return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>;
}

export function useProfile() {
  const ctx = useContext(ProfileContext);
  if (!ctx) throw new Error("useProfile must be used within a ProfileProvider");
  return ctx;
}

/**
 * Whether the active profile is a kids profile. The website keeps a kids
 * profile out of Settings, data and device pairing (see `KidsGate`); the
 * rating limit itself is applied by the TV.
 */
export function useIsKidsProfile(): boolean {
  const ctx = useContext(ProfileContext);
  return ctx?.profile?.parental?.isKidsProfile === true;
}
