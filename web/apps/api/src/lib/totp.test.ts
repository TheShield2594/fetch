import { describe, expect, it } from "vitest";
import {
  base32Decode,
  base32Encode,
  challengeMatchesAccount,
  generateRecoveryCodes,
  generateTotpSecret,
  hashRecoveryCode,
  matchTotp,
  mintTotpChallenge,
  normalizeRecoveryCode,
  normalizeTotpCode,
  readTotpChallenge,
  TOTP_CHALLENGE_TTL_MS,
  totpCodeAt,
  totpUri,
} from "./totp.js";

// RFC 6238 appendix B, SHA-1, cut to six digits.
const RFC_SECRET = Buffer.from("12345678901234567890");
const RFC_VECTORS: [number, string][] = [
  [59, "287082"],
  [1111111109, "081804"],
  [1111111111, "050471"],
  [1234567890, "005924"],
  [2000000000, "279037"],
];

describe("totp", () => {
  it("matches the RFC 6238 test vectors", () => {
    for (const [seconds, code] of RFC_VECTORS) {
      expect(totpCodeAt(RFC_SECRET, Math.floor(seconds / 30))).toBe(code);
    }
  });

  it("round-trips base32 as authenticator apps write it", () => {
    expect(base32Encode(Buffer.from("foobar"))).toBe("MZXW6YTBOI");
    expect(base32Decode("mzxw 6ytb-oi")?.toString()).toBe("foobar");
    expect(base32Decode("not base32!")).toBeNull();
    const secret = generateTotpSecret();
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);
    expect(base32Decode(secret)).toHaveLength(20);
  });

  it("accepts a code one step either side and nothing further", () => {
    const secret = base32Encode(RFC_SECRET);
    const now = 1234567890 * 1000;
    const step = Math.floor(1234567890 / 30);
    expect(matchTotp(secret, totpCodeAt(RFC_SECRET, step), now)).toBe(step);
    expect(matchTotp(secret, totpCodeAt(RFC_SECRET, step - 1), now)).toBe(step - 1);
    expect(matchTotp(secret, totpCodeAt(RFC_SECRET, step + 1), now)).toBe(step + 1);
    expect(matchTotp(secret, totpCodeAt(RFC_SECRET, step - 2), now)).toBeNull();
  });

  it("refuses a code from a step already used", () => {
    const secret = base32Encode(RFC_SECRET);
    const now = 1234567890 * 1000;
    const step = Math.floor(1234567890 / 30);
    const code = totpCodeAt(RFC_SECRET, step);
    expect(matchTotp(secret, code, now, step)).toBeNull();
    expect(matchTotp(secret, code, now, step - 1)).toBe(step);
  });

  it("reads codes as people type them", () => {
    expect(normalizeTotpCode("123 456")).toBe("123456");
    expect(normalizeTotpCode("12345")).toBeNull();
    expect(normalizeTotpCode(123456)).toBeNull();
  });

  it("builds an otpauth URI an authenticator can read", () => {
    const uri = new URL(totpUri("JBSWY3DPEHPK3PXP", "alex"));
    expect(uri.protocol).toBe("otpauth:");
    expect(uri.pathname).toContain("Fetch:alex");
    expect(uri.searchParams.get("secret")).toBe("JBSWY3DPEHPK3PXP");
    expect(uri.searchParams.get("issuer")).toBe("Fetch");
  });
});

describe("recovery codes", () => {
  it("are distinct, readable, and hash the same however they are typed", () => {
    const codes = generateRecoveryCodes();
    expect(codes).toHaveLength(10);
    expect(new Set(codes).size).toBe(10);
    for (const code of codes) expect(code).toMatch(/^[a-z2-9]{5}-[a-z2-9]{5}$/);
    const [code] = codes;
    const typed = normalizeRecoveryCode(` ${code!.toUpperCase().replace("-", " ")} `);
    expect(typed).not.toBeNull();
    expect(hashRecoveryCode(typed!)).toBe(hashRecoveryCode(normalizeRecoveryCode(code)!));
    expect(normalizeRecoveryCode("short")).toBeNull();
  });
});

describe("sign-in challenge", () => {
  const account = { id: "11111111-1111-4111-8111-111111111111", passwordHash: "scrypt$x", totpSecret: "v1.a.b.c" };

  it("names its account until it expires", () => {
    const now = Date.now();
    const challenge = mintTotpChallenge(account, now);
    expect(readTotpChallenge(challenge, now)).toBe(account.id);
    expect(challengeMatchesAccount(challenge, account)).toBe(true);
    expect(readTotpChallenge(challenge, now + TOTP_CHALLENGE_TTL_MS + 1)).toBeNull();
  });

  it("is refused once tampered with", () => {
    const challenge = mintTotpChallenge(account);
    const [payload, mac] = challenge.split(".");
    const forged = Buffer.from(Buffer.from(payload!, "base64url").toString().replace(account.id, "someone-else")).toString("base64url");
    expect(readTotpChallenge(`${forged}.${mac}`)).toBeNull();
    expect(readTotpChallenge("garbage")).toBeNull();
    expect(readTotpChallenge(undefined)).toBeNull();
  });

  it("stops matching once the password or the secret changes", () => {
    const challenge = mintTotpChallenge(account);
    expect(challengeMatchesAccount(challenge, { ...account, passwordHash: "scrypt$y" })).toBe(false);
    expect(challengeMatchesAccount(challenge, { ...account, totpSecret: "v1.d.e.f" })).toBe(false);
  });
});
