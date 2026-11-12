import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { isStandalone, onDisplayModeChange } from "../utils/displayMode";

type BeforeInstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
};

/*
 * The browser's install offer, held for the whole app.
 *
 * `beforeinstallprompt` fires once per page load, whoever is listening, and a
 * listener added after it has fired never hears it. Install used to be a button
 * in the header that owned the event; it is now offered in three places (a
 * one-time banner, the profile menu and Settings → Appearance), so the event is
 * caught here, at import, and every surface reads the same one.
 */
let deferred: BeforeInstallPromptEvent | null = null;
const subscribers = new Set<() => void>();
const emit = () => subscribers.forEach((notify) => notify());

if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    deferred = event as BeforeInstallPromptEvent;
    emit();
  });
  window.addEventListener("appinstalled", () => {
    deferred = null;
    emit();
  });
}

const subscribe = (notify: () => void) => {
  subscribers.add(notify);
  return () => subscribers.delete(notify);
};

/** For tests: forget a captured offer between cases. */
export function resetInstallOffer() {
  deferred = null;
  emit();
}

const isIOS = () => /iphone|ipad|ipod/i.test(window.navigator.userAgent);

/**
 * What installing looks like here:
 *
 * - `installed`: already running as the app — nothing to offer.
 * - `prompt`: the browser has an install dialog ready.
 * - `ios`: Safari has no prompt, but Add to Home Screen works (even over http).
 * - `needs-https`: Chrome won't offer on an insecure origin such as the
 *   `http://192.168.x.x:7002` the README hands out, so the honest thing to say
 *   is why.
 * - `unavailable`: a secure origin whose browser simply hasn't offered (yet).
 */
export type InstallState = "installed" | "prompt" | "ios" | "needs-https" | "unavailable";

export function useInstallOffer(): {
  state: InstallState;
  /** Opens the browser's dialog; resolves false where there is none to open (iOS). */
  install: () => Promise<boolean>;
} {
  const offer = useSyncExternalStore(subscribe, () => deferred);
  // Held in state rather than read at render time: installing from an open tab
  // flips the mode without reloading the document.
  const [standalone, setStandalone] = useState(isStandalone);
  useEffect(() => onDisplayModeChange(() => setStandalone(isStandalone())), []);

  const state: InstallState = standalone
    ? "installed"
    : offer
      ? "prompt"
      : isIOS()
        ? "ios"
        : !window.isSecureContext
          ? "needs-https"
          : "unavailable";

  const install = useCallback(async () => {
    const event = deferred;
    if (!event) return false;
    try {
      await event.prompt();
      await event.userChoice;
    } catch (err) {
      console.error(err);
    } finally {
      // An offer can be used once; the browser fires a fresh one if it will
      // offer again.
      deferred = null;
      emit();
    }
    return true;
  }, []);

  return { state, install };
}

export const MANUAL_INSTALL_HINT = {
  ios: "In Safari, tap Share, then Add to Home Screen.",
  other: "Use your browser's menu and choose Install app or Add to Home Screen.",
};
