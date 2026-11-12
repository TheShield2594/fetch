import { describe, expect, it } from "vitest";
import { parseProfileAttributes, toProfileView } from "./profile-view.js";

describe("parseProfileAttributes", () => {
  it("leaves out what the body leaves out", () => {
    expect(parseProfileAttributes({ name: "Alice" })).toEqual({ ok: true, data: {} });
  });

  it("reads appearance and sharing fields", () => {
    expect(
      parseProfileAttributes({
        avatarColorHex: "#1E88E5",
        avatarId: "fetch:fox",
        profileBackgroundId: null,
        usesPrimaryAddons: true,
      })
    ).toEqual({
      ok: true,
      data: { avatarColorHex: "#1E88E5", avatarId: "fetch:fox", profileBackgroundId: null, usesPrimaryAddons: true },
    });
  });

  it("reads the web theme by its shape, and clears it on null", () => {
    expect(parseProfileAttributes({ webTheme: "matinee" })).toEqual({ ok: true, data: { webTheme: "matinee" } });
    expect(parseProfileAttributes({ webTheme: null })).toEqual({ ok: true, data: { webTheme: null } });
    expect(parseProfileAttributes({ webTheme: "<script>" })).toEqual({ ok: false, error: "webTheme is not a valid theme id" });
  });

  it("reads parental settings nested, the way the API answers with them", () => {
    expect(parseProfileAttributes({ parental: { maxRating: "TEENS", allowUnrated: false } })).toEqual({
      ok: true,
      data: { maxRating: "TEENS", allowUnrated: false },
    });
  });

  it("applies the kids preset when kids mode is switched on alone", () => {
    expect(parseProfileAttributes({ parental: { isKidsProfile: true } })).toEqual({
      ok: true,
      data: { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false },
    });
  });

  it("keeps an explicit limit sent alongside kids mode", () => {
    expect(parseProfileAttributes({ parental: { isKidsProfile: true, maxRating: "ALL_AGES" } })).toMatchObject({
      ok: true,
      data: { maxRating: "ALL_AGES" },
    });
  });

  it("hands kids rules back apart from the columns it can set directly", () => {
    expect(parseProfileAttributes({ parental: { kidsRules: { dailyMinutes: 45 } } })).toEqual({
      ok: true,
      data: {},
      kidsRules: { patch: { dailyMinutes: 45 }, changedAt: null },
    });
  });

  it("answers kids rules as null for a profile that never had any", () => {
    const view = toProfileView({
      id: "p1",
      name: "Kid",
      pinHash: null,
      avatarColorHex: null,
      avatarId: null,
      profileBackgroundId: null,
      webTheme: null,
      usesPrimaryAddons: false,
      usesPrimaryPlugins: false,
      isKidsProfile: true,
      maxRating: "PARENTAL_GUIDANCE",
      allowUnrated: false,
      kidsRules: null,
      kidsRulesUpdatedAt: null,
      createdAt: new Date("2026-01-01T00:00:00.000Z"),
      updatedAt: new Date("2026-01-01T00:00:00.000Z"),
    });
    expect(view.parental.kidsRules).toBeNull();
  });

  it.each([
    [{ parental: { kidsRules: { dailyMinutes: 0 } } }, /dailyMinutes/],
    [{ avatarColorHex: "blue" }, /avatarColorHex/],
    [{ avatarId: "../../etc" }, /avatarId/],
    [{ usesPrimaryAddons: "yes" }, /usesPrimaryAddons/],
    [{ parental: { maxRating: "PG-13" } }, /maxRating/],
  ])("rejects %j", (body, message) => {
    const result = parseProfileAttributes(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toMatch(message);
  });
});
