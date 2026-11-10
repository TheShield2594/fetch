import { useSyncExternalStore } from "react";

/*
 * AI recommendations are left over from the website's start as a standalone
 * tracker. The website is now the Fetch account server (watchlist, history and
 * settings), so their settings stay out of the way unless someone turns them
 * on in Settings › Preferences › AI Recommendations. Their data is untouched,
 * and AI picks still show on Discover for an account that set a provider up.
 *
 * Kept per browser, like the theme: it decides what this device's navigation
 * shows, not what the account holds.
 */
export const EXTRAS_KEY = "fetch:show-extras";
const CHANGE_EVENT = "fetch:extras-change";

export function extrasEnabled(): boolean {
  try {
    return window.localStorage.getItem(EXTRAS_KEY) === "1";
  } catch {
    // Storage unavailable (private mode, blocked site data): the default.
    return false;
  }
}

export function setExtrasEnabled(enabled: boolean): void {
  try {
    if (enabled) window.localStorage.setItem(EXTRAS_KEY, "1");
    else window.localStorage.removeItem(EXTRAS_KEY);
  } catch {
    // Nothing to keep it in; the toggle still applies until the page reloads.
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

function subscribe(onChange: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === null || event.key === EXTRAS_KEY) onChange();
  };
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener("storage", onStorage);
  };
}

/** Whether AI recommendations are in Settings on this device. Off by default. */
export function useExtrasEnabled(): boolean {
  return useSyncExternalStore(subscribe, extrasEnabled, () => false);
}

