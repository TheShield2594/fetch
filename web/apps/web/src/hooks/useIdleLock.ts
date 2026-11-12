import { useEffect, useRef, useState } from "react";

/**
 * Locking a PIN profile after a spell of inactivity, on this browser.
 *
 * A PIN unlock lasts the profile token's 12 hours, so on a shared computer a
 * PIN profile otherwise stays open for half a day after its owner walks away.
 * The limit is a per-browser choice (Settings → Profile), kept in
 * localStorage: it is about this machine, not the profile, and it must not
 * lock someone's own laptop because they chose it on the family one.
 */

export const IDLE_LOCK_KEY = "fetch_idle_lock_minutes";
export const IDLE_LOCK_CHOICES = [0, 5, 15, 30, 60] as const;
/** Same-tab signal that the choice changed; `storage` covers other tabs. */
const CHANGED_EVENT = "fetch:idle-lock-changed";
/** How often the clock is checked while the tab is open. */
const CHECK_INTERVAL_MS = 15_000;
/**
 * The last activity in any tab, so a tab left idle doesn't lock the profile
 * (whose credentials every tab shares) while someone is using it in another.
 * Written at most this often, not on every pointer move.
 */
export const IDLE_ACTIVITY_KEY = "fetch_idle_last_activity";
const SHARE_INTERVAL_MS = 5_000;

const readSharedActivity = (): number => {
  try {
    const value = Number(window.localStorage.getItem(IDLE_ACTIVITY_KEY));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
};

const writeSharedActivity = (at: number): void => {
  try {
    window.localStorage.setItem(IDLE_ACTIVITY_KEY, String(at));
  } catch {
    /* storage unavailable: each tab then goes by its own activity */
  }
};

/** The chosen limit in minutes; 0 is off. Storage that can't be read is off. */
export function getIdleLockMinutes(): number {
  try {
    const raw = Number(window.localStorage.getItem(IDLE_LOCK_KEY));
    return (IDLE_LOCK_CHOICES as readonly number[]).includes(raw) ? raw : 0;
  } catch {
    return 0;
  }
}

export function setIdleLockMinutes(minutes: number): void {
  try {
    if (minutes > 0) window.localStorage.setItem(IDLE_LOCK_KEY, String(minutes));
    else window.localStorage.removeItem(IDLE_LOCK_KEY);
  } catch {
    /* private window or blocked storage: the choice just isn't kept */
  }
  window.dispatchEvent(new Event(CHANGED_EVENT));
}

/** The current choice, kept in step with changes from Settings or another tab. */
export function useIdleLockMinutes(): number {
  const [minutes, setMinutes] = useState(getIdleLockMinutes);
  useEffect(() => {
    const reread = () => setMinutes(getIdleLockMinutes());
    window.addEventListener(CHANGED_EVENT, reread);
    window.addEventListener("storage", reread);
    return () => {
      window.removeEventListener(CHANGED_EVENT, reread);
      window.removeEventListener("storage", reread);
    };
  }, []);
  return minutes;
}

const ACTIVITY_EVENTS = ["pointerdown", "pointermove", "keydown", "wheel", "touchstart", "scroll"] as const;

/**
 * Calls `onLock` once the page has gone `minutes` without a pointer, key or
 * scroll, while `enabled` (the active profile has a PIN). A hidden tab is
 * checked again the moment it is shown, so coming back to it after the limit
 * locks before anything is seen; a timer alone would not, as browsers slow
 * timers in background tabs.
 */
export function useIdleLock(enabled: boolean, onLock: () => void): void {
  const minutes = useIdleLockMinutes();
  const onLockRef = useRef(onLock);
  useEffect(() => {
    onLockRef.current = onLock;
  }, [onLock]);

  useEffect(() => {
    if (!enabled || minutes <= 0) return;
    const limitMs = minutes * 60_000;
    let lastActivity = Date.now();
    let lastShared = 0;
    let locked = false;

    const check = () => {
      if (locked) return;
      const latest = Math.max(lastActivity, readSharedActivity());
      if (Date.now() - latest < limitMs) return;
      locked = true;
      onLockRef.current();
    };
    const markActive = () => {
      // Activity in a tab that has sat past the limit doesn't reopen it: the
      // check runs first.
      check();
      if (locked) return;
      lastActivity = Date.now();
      if (lastActivity - lastShared >= SHARE_INTERVAL_MS) {
        lastShared = lastActivity;
        writeSharedActivity(lastActivity);
      }
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") check();
    };

    for (const type of ACTIVITY_EVENTS) window.addEventListener(type, markActive, { passive: true });
    document.addEventListener("visibilitychange", onVisibility);
    const interval = window.setInterval(check, CHECK_INTERVAL_MS);
    return () => {
      for (const type of ACTIVITY_EVENTS) window.removeEventListener(type, markActive);
      document.removeEventListener("visibilitychange", onVisibility);
      window.clearInterval(interval);
    };
  }, [enabled, minutes]);
}
