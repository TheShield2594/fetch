import { createContext, useCallback, useContext, useEffect, useState } from "react";

export type Theme = "fetch" | "light" | "glass" | "midnight" | "matinee";

export type ThemeOption = {
  id: Theme;
  label: string;
  /** Why it exists, in a few words — the line under its name in Appearance. */
  description: string;
  /** What the swatch is painted with: the ground, a card on it, and the accent. */
  swatch: { bg: string; card: string; accent: string; text: string };
};

/*
 * Five, each with a reason to exist. There were six, and side by side on the
 * Shelf the dark ones were near enough the same theme with a different accent:
 * "Dark" was Fetch on #000 instead of #0a0a0b, with an orange borrowed from the
 * platform; "Midnight" was Fetch's red on navy. Dark is folded into Fetch, Light
 * is Fetch's palette on a light ground, Midnight takes gold, and the theme
 * named after another product is Matinee.
 *
 * Keep the swatch colours in step with each theme's --bg-0, --bg-1, --accent
 * and --text in index.css.
 */
export const THEMES: ThemeOption[] = [
  // The TV app's look, first and the default, so the web side reads as the same product.
  {
    id: "fetch",
    label: "Fetch",
    description: "The TV app's look: near-black and Fetch red",
    swatch: { bg: "#0a0a0b", card: "#151517", accent: "#dc2b33", text: "#ffffff" },
  },
  {
    id: "light",
    label: "Light",
    description: "Fetch on a light ground, for daylight",
    swatch: { bg: "#f2f2f7", card: "#ffffff", accent: "#dc2b33", text: "#000000" },
  },
  {
    id: "glass",
    label: "Glass",
    description: "Frosted panels over the artwork, in blue",
    swatch: { bg: "#0b0d12", card: "#11141b", accent: "#0a84ff", text: "#f5f6f8" },
  },
  {
    id: "midnight",
    label: "Midnight Cinema",
    description: "Deep navy and marquee gold",
    swatch: { bg: "#07080d", card: "#0e1018", accent: "#f0c419", text: "#f4f1ea" },
  },
  {
    id: "matinee",
    label: "Matinee",
    description: "Warm charcoal with an orange accent",
    swatch: { bg: "#14181c", card: "#1c2228", accent: "#ff8000", text: "#ffffff" },
  },
];

const STORAGE_KEY = "fetch:theme";

// The colour the browser paints its own chrome with — the address bar on
// Android, the status bar in an installed app. It has to be each theme's
// --bg-0 exactly: a near-match reads as a seam between the app and the phone.
const THEME_BG: Record<Theme, string> = Object.fromEntries(THEMES.map((t) => [t.id, t.swatch.bg])) as Record<
  Theme,
  string
>;

/**
 * Theme ids that have been retired, and where a choice of one lands. A saved
 * "letterboxd" — in this browser or on a profile — is the same palette under
 * its new name; "dark" was Fetch on true black, and is Fetch.
 */
const RENAMED: Record<string, Theme> = { letterboxd: "matinee", dark: "fetch" };

function isTheme(value: string | null | undefined): value is Theme {
  return THEMES.some((t) => t.id === value);
}

/** A stored or synced id as a current theme, following renames; null when it names none. */
export function toTheme(value: string | null | undefined): Theme | null {
  if (isTheme(value)) return value;
  return (value && RENAMED[value]) || null;
}

/** With no saved choice, everyone starts on Fetch, the TV app's look. */
export const DEFAULT_THEME: Theme = "fetch";

// Set when someone picks a theme in the menu. Before Fetch existed the app
// saved its own OS-matched pick ("light" or "dark") on first load, so a saved
// light/dark without this mark was never chosen by anyone: those visitors move
// to Fetch once, and a later pick of light sets the mark and sticks.
const PICKED_KEY = "fetch:theme-picked";
const AUTO_SAVED: readonly string[] = ["light", "dark"];

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    // Storage unavailable (private mode, blocked site data).
    return null;
  }
}

function writeStorage(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Not persisted; the theme still applies for this visit.
  }
}

/** Exported for tests. Keep in sync with public/theme-init.js, which paints before React. */
export function resolveInitialTheme(stored: string | null, picked: boolean): Theme {
  if (!picked && stored && AUTO_SAVED.includes(stored)) return DEFAULT_THEME;
  return toTheme(stored) ?? DEFAULT_THEME;
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

/**
 * The theme in this browser. localStorage is the cache that lets the first
 * paint be right before anything has loaded (see public/theme-init.js); the
 * choice itself belongs to the profile, which `ThemeSync` in App.tsx reads and
 * writes.
 */
export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(getInitialTheme);

  useEffect(() => {
    applyTheme(theme);
    writeStorage(STORAGE_KEY, theme);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    writeStorage(PICKED_KEY, "1");
    setThemeState(next);
  }, []);

  return { theme, setTheme };
}

/** What Appearance and the profile menu read and pick from. */
export type ThemeChoice = { theme: Theme; chooseTheme: (next: Theme) => void };

export const ThemeContext = createContext<ThemeChoice | null>(null);

export function useThemeChoice(): ThemeChoice {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useThemeChoice must be used within the app shell");
  return ctx;
}
