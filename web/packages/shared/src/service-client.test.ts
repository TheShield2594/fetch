import { describe, expect, it } from "vitest";
import { deriveStremioProfileKey, matchesStremioProfileKey } from "./service-client.js";

const TOKEN = "an-api-token";
const PROFILE = "11111111-1111-4111-8111-111111111111";
const OTHER = "22222222-2222-4222-8222-222222222222";

describe("deriveStremioProfileKey", () => {
  it("is a fixed 64-hex key per token and profile", () => {
    const key = deriveStremioProfileKey(TOKEN, PROFILE);
    expect(key).toMatch(/^[0-9a-f]{64}$/);
    expect(deriveStremioProfileKey(TOKEN, PROFILE)).toBe(key);
  });

  it("differs between profiles and between tokens", () => {
    const key = deriveStremioProfileKey(TOKEN, PROFILE);
    expect(deriveStremioProfileKey(TOKEN, OTHER)).not.toBe(key);
    expect(deriveStremioProfileKey("another-token", PROFILE)).not.toBe(key);
  });
});

describe("deriveStremioProfileKey generations", () => {
  it("keeps generation 0 identical to the original derivation", async () => {
    const { createHmac } = await import("node:crypto");
    const original = createHmac("sha256", TOKEN).update(`fetch-stremio-addon:${PROFILE}`).digest("hex");
    expect(deriveStremioProfileKey(TOKEN, PROFILE, 0)).toBe(original);
    expect(deriveStremioProfileKey(TOKEN, PROFILE)).toBe(original);
  });

  it("gives each generation its own key", () => {
    const keys = [0, 1, 2].map((g) => deriveStremioProfileKey(TOKEN, PROFILE, g));
    expect(new Set(keys).size).toBe(3);
  });

  it("matches only the current generation", () => {
    const old = deriveStremioProfileKey(TOKEN, PROFILE, 0);
    const current = deriveStremioProfileKey(TOKEN, PROFILE, 1);
    expect(matchesStremioProfileKey(old, TOKEN, PROFILE, 1)).toBe(false);
    expect(matchesStremioProfileKey(current, TOKEN, PROFILE, 1)).toBe(true);
  });
});

describe("matchesStremioProfileKey", () => {
  const key = deriveStremioProfileKey(TOKEN, PROFILE);

  it("accepts the profile's own key", () => {
    expect(matchesStremioProfileKey(key, TOKEN, PROFILE)).toBe(true);
  });

  it("refuses another profile's key", () => {
    expect(matchesStremioProfileKey(deriveStremioProfileKey(TOKEN, OTHER), TOKEN, PROFILE)).toBe(false);
  });

  it("refuses everything when there is no token to derive from", () => {
    expect(matchesStremioProfileKey(key, undefined, PROFILE)).toBe(false);
  });

  it("refuses non-strings and keys of the wrong length without throwing", () => {
    expect(matchesStremioProfileKey(undefined, TOKEN, PROFILE)).toBe(false);
    expect(matchesStremioProfileKey(key.slice(1), TOKEN, PROFILE)).toBe(false);
    expect(matchesStremioProfileKey(`${key}0`, TOKEN, PROFILE)).toBe(false);
    expect(matchesStremioProfileKey("é".repeat(64), TOKEN, PROFILE)).toBe(false);
  });
});
