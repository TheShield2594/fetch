(function () {
  // Keep in sync with THEMES/THEME_BG in apps/web/src/hooks/useTheme.ts
  var themeBg = {
    fetch: "#0a0a0b",
    light: "#f2f2f7",
    glass: "#0b0d12",
    midnight: "#07080d",
    matinee: "#14181c",
  };

  // Each theme's --accent, for the pre-hydration loader in index.html. That
  // loader paints before the app's CSS exists, so it cannot read --accent and
  // used to be a hard-coded orange — which is the wrong brand colour on the
  // blue and red themes, on the one frame a visitor sees first. Keep in sync
  // with the --accent declarations in apps/web/src/index.css.
  var themeAccent = {
    fetch: "#dc2b33",
    light: "#dc2b33",
    glass: "#0a84ff",
    midnight: "#f0c419",
    matinee: "#ff8000",
  };

  // Retired ids and where a saved choice of one lands (RENAMED in useTheme.ts).
  var renamed = { letterboxd: "matinee", dark: "fetch" };

  var stored = null;
  try {
    stored = localStorage.getItem("fetch:theme");
  } catch (e) {
    // localStorage unavailable (private browsing, disabled storage, etc.) — fall back to default theme.
  }

  var picked = null;
  try {
    picked = localStorage.getItem("fetch:theme-picked");
  } catch (e) {
    // As above.
  }

  // Mirrors resolveInitialTheme() in useTheme.ts, so the first paint matches
  // what React mounts: no stored choice, or a light/dark the app saved itself
  // before anyone picked one, starts on Fetch.
  var autoSaved = (stored === "light" || stored === "dark") && picked !== "1";
  if (Object.prototype.hasOwnProperty.call(renamed, stored)) stored = renamed[stored];
  var known = Object.prototype.hasOwnProperty.call(themeBg, stored);
  var theme = known && !autoSaved ? stored : "fetch";
  document.documentElement.setAttribute("data-theme", theme);
  document.documentElement.style.colorScheme = theme === "light" ? "light" : "dark";
  document.documentElement.style.backgroundColor = themeBg[theme];
  document.documentElement.style.setProperty("--loader-accent", themeAccent[theme]);

  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", themeBg[theme]);
})();
