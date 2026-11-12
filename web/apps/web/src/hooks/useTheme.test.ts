import { describe, expect, it } from "vitest";
import { DEFAULT_THEME, THEMES, resolveInitialTheme, toTheme } from "./useTheme";

describe("resolveInitialTheme", () => {
  it("starts a new visitor on the Fetch theme", () => {
    expect(DEFAULT_THEME).toBe("fetch");
    expect(resolveInitialTheme(null, false)).toBe("fetch");
  });

  it("moves a light or dark the app saved itself to Fetch", () => {
    expect(resolveInitialTheme("light", false)).toBe("fetch");
    expect(resolveInitialTheme("dark", false)).toBe("fetch");
  });

  it("keeps a light someone picked", () => {
    expect(resolveInitialTheme("light", true)).toBe("light");
  });

  it("keeps themes that were only ever picked by hand", () => {
    expect(resolveInitialTheme("glass", false)).toBe("glass");
    expect(resolveInitialTheme("midnight", false)).toBe("midnight");
    expect(resolveInitialTheme("matinee", false)).toBe("matinee");
    expect(resolveInitialTheme("fetch", false)).toBe("fetch");
  });

  it("carries a retired theme to the one it became", () => {
    expect(resolveInitialTheme("letterboxd", false)).toBe("matinee");
    expect(resolveInitialTheme("dark", true)).toBe("fetch");
    expect(toTheme("letterboxd")).toBe("matinee");
  });

  it("ignores an unknown stored value", () => {
    expect(resolveInitialTheme("sepia", true)).toBe("fetch");
    expect(toTheme("sepia")).toBeNull();
    expect(toTheme(null)).toBeNull();
  });

  it("lists Fetch first, and names no theme after another product", () => {
    expect(THEMES[0]?.id).toBe("fetch");
    for (const theme of THEMES) expect(theme.label).not.toMatch(/letterboxd/i);
  });
});
