import { describe, expect, it } from "vitest";
import { paletteShortcutHint } from "./shortcut";

const nav = (fields: Record<string, unknown>) => fields as unknown as Navigator;

describe("paletteShortcutHint", () => {
  it("shows ⌘K on a Mac", () => {
    expect(paletteShortcutHint(nav({ platform: "MacIntel", userAgent: "" }))).toBe("⌘K");
  });

  it("shows ⌘K on an iPad", () => {
    expect(paletteShortcutHint(nav({ platform: "iPad", userAgent: "" }))).toBe("⌘K");
  });

  it("prefers userAgentData when the browser offers it", () => {
    expect(paletteShortcutHint(nav({ userAgentData: { platform: "macOS" }, platform: "", userAgent: "" }))).toBe("⌘K");
  });

  it("shows Ctrl K on Windows and Linux", () => {
    expect(paletteShortcutHint(nav({ userAgentData: { platform: "Windows" }, platform: "Win32", userAgent: "" }))).toBe("Ctrl K");
    expect(paletteShortcutHint(nav({ platform: "Linux x86_64", userAgent: "" }))).toBe("Ctrl K");
  });
});
