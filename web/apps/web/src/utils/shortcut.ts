/**
 * Whether this device labels its shortcuts with ⌘ (Apple platforms) rather
 * than Ctrl. The palette opens on either modifier (see useCommandPalette), so
 * this only decides what the hint says.
 */
export function isApplePlatform(nav: Navigator | undefined = typeof navigator === "undefined" ? undefined : navigator): boolean {
  if (!nav) return false;
  const uaData = (nav as Navigator & { userAgentData?: { platform?: string } }).userAgentData;
  const platform = uaData?.platform || nav.platform || nav.userAgent || "";
  return /mac|iphone|ipad|ipod/i.test(platform);
}

/** The visible hint for the palette shortcut: "⌘K" on Apple platforms, "Ctrl K" elsewhere. */
export function paletteShortcutHint(nav?: Navigator): string {
  return isApplePlatform(nav) ? "⌘K" : "Ctrl K";
}
