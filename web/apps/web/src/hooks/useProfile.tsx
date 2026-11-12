import { createContext, type ReactNode, useCallback, useContext, useMemo, useState } from "react";
import { type Profile, runtimeConfig, notifyServiceWorkerToInvalidateApiCache } from "../api";

type ProfileContextValue = {
  profile: Profile | null;
  setProfile: (profile: Profile) => void;
  /** Replaces the active profile's fields in place — an edit, not a switch. */
  patchProfile: (changes: Partial<Profile>) => void;
  switcherOpen: boolean;
  openSwitcher: () => void;
  closeSwitcher: () => void;
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

  const setProfile = useCallback((next: Profile) => {
    runtimeConfig.setProfileId(next.id);
    setProfileState(next);
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
    () => ({ profile, setProfile, patchProfile, switcherOpen, openSwitcher, closeSwitcher }),
    [profile, setProfile, patchProfile, switcherOpen, openSwitcher, closeSwitcher]
  );

  return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>;
}

export function useProfile() {
  const ctx = useContext(ProfileContext);
  if (!ctx) throw new Error("useProfile must be used within a ProfileProvider");
  return ctx;
}
