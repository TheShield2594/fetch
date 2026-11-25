import { describe, expect, it } from "vitest";
import { API_VERSION, MIN_TV_APP, compareVersions, isTvAppTooOld, parseAppVersion, versionInfo } from "./version.js";

describe("version", () => {
  it("reads the server's version from package.json", () => {
    const info = versionInfo();
    expect(info.server).toMatch(/^\d+\.\d+\.\d+$/);
    expect(info.apiVersion).toBe(API_VERSION);
    expect(info.minTvApp).toBe(MIN_TV_APP);
    expect(info.features).toContain("sync-report");
  });

  it("parses a TV build's version, ignoring what follows the numbers", () => {
    expect(parseAppVersion("1.9.1")).toEqual([1, 9, 1]);
    expect(parseAppVersion("v2.0.0-debug")).toEqual([2, 0, 0]);
    expect(parseAppVersion("2.0 (beta)")).toEqual([2, 0]);
    expect(parseAppVersion("dev")).toBeNull();
    expect(parseAppVersion(undefined)).toBeNull();
  });

  it("compares versions part by part, missing parts as zero", () => {
    expect(compareVersions([1, 10], [1, 9, 9])).toBeGreaterThan(0);
    expect(compareVersions([2], [2, 0, 0])).toBe(0);
    expect(compareVersions([1, 8, 9], [1, 9])).toBeLessThan(0);
  });

  it("flags only a readable version below the minimum", () => {
    expect(isTvAppTooOld("1.8.4", "1.9.0")).toBe(true);
    expect(isTvAppTooOld("1.9.0", "1.9.0")).toBe(false);
    expect(isTvAppTooOld("2.0.0", "1.9.0")).toBe(false);
    expect(isTvAppTooOld("nightly", "1.9.0")).toBe(false);
    expect(isTvAppTooOld(null, "1.9.0")).toBe(false);
  });
});
