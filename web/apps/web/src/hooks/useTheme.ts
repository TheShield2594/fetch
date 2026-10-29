import { useCallback, useEffect, useState } from "react";

export type Theme = "fetch" | "light" | "dark" | "glass" | "midnight" | "letterboxd";

export const THEMES: { id: Theme; label: string }[] = [
  // The TV app's look, first and the default, so the web side reads as the same product.
  { id: "fetch", label: "Fetch" },
  { id: "light", label: "Light" },
  { id: "dark", label: "Dark" },
  { id: "glass", label: "Glass" },
  { id: "midnight", label: "Midnight Cinema" },
  { id: "letterboxd", label: "Letterboxd" },
];

const STORAGE_KEY = "fetch:theme";

// The colour the browser paints its own chrome with — the address bar on
// Android, the status bar in an installed app. It has to be each theme's
// --bg-0 exactly: a near-match reads as a seam between the app and the phone.
const THEME_BG: Record<Theme, string> = {
  fetch: "#0d0e10",
  light: "#f2f2f7",
  dark: "#000000",
  glass: "#0b0d12",
  midnight: "#07080d",
  letterboxd: "#14181c",
};

function isTheme(value: string | null): value is Theme {
  return THEMES.some((t) => t.id === value);
}

/** With no saved choice, everyone starts on Fetch, the TV app's look. */
export const DEFAULT_THEME: Theme = "fetch";

// Set when someone picks a theme in the menu. Before Fetch existed the app
// saved its own OS-matched pick ("light" or "dark") on first load, so a saved
// light/dark without this mark was never chosen by anyone: those visitors move
// to Fetch once, and a later pick of light or dark sets the mark and sticks.
// Glass, Midnight and Letterboxd were only ever chosen, so they always stay.
const PICKED_KEY = "fetch:theme-picked";
const AUTO_SAVED: readonly Theme[] = ["light", "dark"];

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    // Storage unavailable (private mode, blocked site data).
    return null;
  }
}

/** Exported for tests. Keep in sync with public/theme-init.js, which paints before React. */
export function resolveInitialTheme(stored: string | null, picked: boolean): Theme {
  if (!isTheme(stored)) return DEFAULT_THEME;
  if (!picked && AUTO_SAVED.includes(stored)) return DEFAULT_THEME;
  return stored;
}

function getInitialTheme(): Theme {
  if (typeof window === "undefined") return DEFAULT_THEME;
  return resolveInitialTheme(readStorage(STORAGE_KEY), readStorage(PICKED_KEY) === "1");
}

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute("data-theme", theme);
  document.documentElement.style.colorScheme = theme === "light" ? "light" : "dark";
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", THEME_BG[theme]);
}

export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(getInitialTheme);

  useEffect(() => {
    applyTheme(theme);
    window.localStorage.setItem(STORAGE_KEY, theme);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    try {
      window.localStorage.setItem(PICKED_KEY, "1");
    } catch {
      // Not persisted; the pick still applies for this visit.
    }
    setThemeState(next);
  }, []);

  return { theme, setTheme };
}
