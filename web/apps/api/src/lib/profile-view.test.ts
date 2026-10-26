import { describe, expect, it } from "vitest";
import { parseProfileAttributes } from "./profile-view.js";

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

  it.each([
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
