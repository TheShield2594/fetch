import { createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from "node:crypto";

/**
 * Optional two-factor sign-in (#564): time-based one-time codes (RFC 6238), the
 * kind every authenticator app reads from a QR code.
 *
 * SHA-1, six digits, 30-second steps: the parameters authenticator apps assume
 * when a URI leaves them out, so nothing here depends on an app honouring the
 * optional ones.
 */

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const STEP_SECONDS = 30;
const DIGITS = 6;
/** Codes from one step either side are accepted, for a clock that is a little off. */
const DRIFT_STEPS = 1;
/** 160 bits, the size RFC 4226 recommends for an HMAC-SHA1 key. */
const SECRET_BYTES = 20;

export const TOTP_ISSUER = "Fetch";

export const base32Encode = (bytes: Uint8Array): string => {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
};

/** Null for anything that isn't base32. Spaces, dashes and case are forgiven. */
export const base32Decode = (text: string): Buffer | null => {
  const clean = text.replace(/[\s-]/g, "").replace(/=+$/, "").toUpperCase();
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const char of clean) {
    const index = BASE32_ALPHABET.indexOf(char);
    if (index === -1) return null;
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
};

/** A new secret, base32 as authenticator apps take it. */
export const generateTotpSecret = (): string => base32Encode(randomBytes(SECRET_BYTES));

export const timeStep = (nowMs: number): number => Math.floor(nowMs / 1000 / STEP_SECONDS);

/** The code for one time step (RFC 4226 HOTP with the step as the counter). */
export const totpCodeAt = (secret: Buffer, step: number): string => {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const digest = createHmac("sha1", secret).update(counter).digest();
  const offset = digest[digest.length - 1]! & 0x0f;
  const binary =
    ((digest[offset]! & 0x7f) << 24) |
    (digest[offset + 1]! << 16) |
    (digest[offset + 2]! << 8) |
    digest[offset + 3]!;
  return String(binary % 10 ** DIGITS).padStart(DIGITS, "0");
};

const CODE_PATTERN = /^\d{6}$/;

/** A code as typed: spaces dropped ("123 456" is how apps show it). */
export const normalizeTotpCode = (raw: unknown): string | null => {
  if (typeof raw !== "string") return null;
  const code = raw.replace(/\s/g, "");
  return CODE_PATTERN.test(code) ? code : null;
};

/**
 * The time step `code` belongs to, or null when it matches none in the window.
 *
 * A step at or before `lastUsedStep` is refused even if the code is right, so a
 * code that has been used once (or seen over someone's shoulder after) cannot
 * be used again while it is still current.
 */
export const matchTotp = (
  secretBase32: string,
  code: string,
  nowMs: number,
  lastUsedStep: number | null = null
): number | null => {
  const secret = base32Decode(secretBase32);
  if (!secret || secret.length === 0) return null;
  const presented = Buffer.from(code);
  const current = timeStep(nowMs);
  let matched: number | null = null;
  // Every step in the window is compared, matched or not, so the time taken
  // says nothing about which one it was.
  for (let step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step += 1) {
    const expected = Buffer.from(totpCodeAt(secret, step));
    if (expected.length === presented.length && timingSafeEqual(expected, presented)) {
      if (lastUsedStep === null || step > lastUsedStep) matched ??= step;
    }
  }
  return matched;
};

/** The `otpauth://` URI an authenticator app reads from the QR code. */
export const totpUri = (secretBase32: string, accountName: string, issuer: string = TOTP_ISSUER): string => {
  const label = `${encodeURIComponent(issuer)}:${encodeURIComponent(accountName)}`;
  const params = new URLSearchParams({
    secret: secretBase32,
    issuer,
    algorithm: "SHA1",
    digits: String(DIGITS),
    period: String(STEP_SECONDS),
  });
  return `otpauth://totp/${label}?${params.toString()}`;
};

// ─── Recovery codes ───
//
// Ten single-use codes, shown once when two-factor is turned on. Only their
// hashes are kept. They are 50 random bits each, far beyond guessing within the
// sign-in lockout, so a plain SHA-256 is enough: there is no low-entropy input
// for a slow hash to protect.

export const RECOVERY_CODE_COUNT = 10;
const RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
const RECOVERY_CODE_LENGTH = 10;

/** `abcde-fghjk`: no 0/o or 1/l/i to misread. */
export const generateRecoveryCodes = (count: number = RECOVERY_CODE_COUNT): string[] =>
  Array.from({ length: count }, () => {
    let code = "";
    for (let i = 0; i < RECOVERY_CODE_LENGTH; i += 1) code += RECOVERY_ALPHABET[randomInt(RECOVERY_ALPHABET.length)];
    return `${code.slice(0, 5)}-${code.slice(5)}`;
  });

/** A recovery code as typed, reduced to what is hashed: lowercase, no dash or spaces. */
export const normalizeRecoveryCode = (raw: unknown): string | null => {
  if (typeof raw !== "string") return null;
  const code = raw.toLowerCase().replace(/[\s-]/g, "");
  return code.length === RECOVERY_CODE_LENGTH && [...code].every((c) => RECOVERY_ALPHABET.includes(c)) ? code : null;
};

export const hashRecoveryCode = (normalized: string): string =>
  createHash("sha256").update(`fetch-recovery-code:${normalized}`).digest("hex");

// ─── Sign-in challenge ───
//
// `POST /auth/login` with the right password answers an account with two-factor
// on with a challenge instead of a session. The challenge is what the second
// step presents with the code: proof that the password was right a moment ago.
//
// It is stateless, signed with a key derived from `API_TOKEN`, and it is bound
// to the account's password hash and second-factor state as they were, so a
// password change or turning two-factor off and on again voids challenges
// already handed out. A challenge does not by itself make guessing codes any
// cheaper: wrong codes count towards the same lockout as wrong passwords.

export const TOTP_CHALLENGE_TTL_MS = 5 * 60 * 1000;
const CHALLENGE_LABEL = "fetch-totp-challenge";

// Without `API_TOKEN` (a bare development server) challenges are signed with a
// key that lives as long as the process.
const fallbackKey = randomBytes(32);

const challengeKey = (): Buffer | string => {
  const apiToken = process.env.API_TOKEN;
  return apiToken ? createHmac("sha256", apiToken).update(CHALLENGE_LABEL).digest() : fallbackKey;
};

export type ChallengeBinding = { id: string; passwordHash: string; totpSecret: string | null };

const bindingDigest = (account: ChallengeBinding): string =>
  createHash("sha256").update(`${account.passwordHash}\u0000${account.totpSecret ?? ""}`).digest("base64url");

const sign = (payload: string): string => createHmac("sha256", challengeKey()).update(payload).digest("base64url");

export const mintTotpChallenge = (account: ChallengeBinding, nowMs: number = Date.now()): string => {
  const payload = `${account.id}.${nowMs + TOTP_CHALLENGE_TTL_MS}.${bindingDigest(account)}`;
  return `${Buffer.from(payload).toString("base64url")}.${sign(payload)}`;
};

/** The account id a challenge was minted for, if it is genuine and current. Not yet checked against the account. */
export const readTotpChallenge = (raw: unknown, nowMs: number = Date.now()): string | null => {
  if (typeof raw !== "string" || raw.length > 512) return null;
  const [encoded, mac] = raw.split(".");
  if (!encoded || !mac) return null;
  const payload = Buffer.from(encoded, "base64url").toString();
  const expected = Buffer.from(sign(payload));
  const presented = Buffer.from(mac);
  if (expected.length !== presented.length || !timingSafeEqual(expected, presented)) return null;
  const [id, expiry] = payload.split(".");
  if (!id || !expiry || !(Number(expiry) > nowMs)) return null;
  return id;
};

/** Whether a genuine challenge still matches the account's password and second factor. */
export const challengeMatchesAccount = (raw: string, account: ChallengeBinding): boolean => {
  const payload = Buffer.from(raw.split(".")[0] ?? "", "base64url").toString();
  const digest = payload.split(".")[2] ?? "";
  const expected = Buffer.from(bindingDigest(account));
  const presented = Buffer.from(digest);
  return expected.length === presented.length && timingSafeEqual(expected, presented);
};
