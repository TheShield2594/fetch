import { describe, expect, it } from "vitest";
import { DEFAULT_THEME, THEMES, resolveInitialTheme } from "./useTheme";

describe("resolveInitialTheme", () => {
  it("starts a new visitor on the Fetch theme", () => {
    expect(DEFAULT_THEME).toBe("fetch");
    expect(resolveInitialTheme(null, false)).toBe("fetch");
  });

  it("moves a light or dark the app saved itself to Fetch", () => {
    expect(resolveInitialTheme("light", false)).toBe("fetch");
    expect(resolveInitialTheme("dark", false)).toBe("fetch");
  });

  it("keeps a light or dark someone picked", () => {
    expect(resolveInitialTheme("light", true)).toBe("light");
    expect(resolveInitialTheme("dark", true)).toBe("dark");
  });

  it("keeps themes that were only ever picked by hand", () => {
    expect(resolveInitialTheme("glass", false)).toBe("glass");
    expect(resolveInitialTheme("midnight", false)).toBe("midnight");
    expect(resolveInitialTheme("letterboxd", false)).toBe("letterboxd");
    expect(resolveInitialTheme("fetch", false)).toBe("fetch");
  });

  it("ignores an unknown stored value", () => {
    expect(resolveInitialTheme("sepia", true)).toBe("fetch");
  });

  it("lists Fetch first in the menu", () => {
    expect(THEMES[0]).toEqual({ id: "fetch", label: "Fetch" });
  });
});
