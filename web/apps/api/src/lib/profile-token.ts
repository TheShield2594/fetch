import { createHash, createHmac, timingSafeEqual } from "node:crypto";

// A profile-access token is a stateless, signed capability proving the holder
// passed PIN verification for a specific profile. `resolveProfile` requires a
// valid one for any PIN-protected profile, so holding the shared API_TOKEN
// alone is no longer enough to read or mutate a locked profile's data by simply
// setting the `x-profile-id` header.
//
// The signing key is derived from API_TOKEN, which is always present in a
// running deployment (the production startup guard refuses to boot without it).
// Rotating API_TOKEN therefore invalidates every outstanding profile token —
// the desired behavior: a credential change forces re-verification.
//
// The signature also covers the profile's PIN *generation* — a digest of its
// current `pinHash` — and is checked against the stored hash at use time. So
// changing, setting or removing a PIN invalidates every token minted under the
// old one at once, rather than letting a leaked token ride out its 12h TTL.

export const PROFILE_TOKEN_TTL_MS = 12 * 60 * 60 * 1000; // 12 hours
// v2: the signed message gained the PIN generation. A v1 token is simply
// rejected (the holder re-verifies), never read as a v2 one.
const TOKEN_VERSION = "v2";

const signingKey = () => process.env.API_TOKEN ?? "";

// Never leaves the HMAC input, so it reveals nothing about the hash; a profile
// without a PIN is its own generation, so setting a first PIN also rotates.
const pinGeneration = (pinHash: string | null): string =>
  pinHash ? createHash("sha256").update(pinHash).digest("base64url").slice(0, 16) : "nopin";

const sign = (profileId: string, pinHash: string | null, expiresAt: number): string =>
  createHmac("sha256", signingKey())
    .update(`${TOKEN_VERSION}:${profileId}:${pinGeneration(pinHash)}:${expiresAt}`)
    .digest("base64url");

/** `pinHash` is the profile's stored hash *now* (null for no PIN). */
export const mintProfileToken = (profileId: string, pinHash: string | null): string => {
  const expiresAt = Date.now() + PROFILE_TOKEN_TTL_MS;
  return `${TOKEN_VERSION}.${expiresAt}.${sign(profileId, pinHash, expiresAt)}`;
};

export const verifyProfileToken = (
  token: string | undefined,
  profileId: string,
  pinHash: string | null
): boolean => {
  if (!token) return false;

  const [version, expiresRaw, providedSig, ...rest] = token.split(".");
  // Exactly three parts — the same check as the old `parts.length !== 3`, said
  // so that the three below are strings rather than maybes.
  if (expiresRaw === undefined || providedSig === undefined || rest.length > 0) return false;
  if (version !== TOKEN_VERSION) return false;

  const expiresAt = Number(expiresRaw);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return false;

  // Recompute the signature over the caller-supplied profileId and the
  // profile's current PIN hash, so a token minted for profile A can never
  // authorize profile B, nor outlive a PIN change on A.
  const expectedSig = sign(profileId, pinHash, expiresAt);
  const providedBuf = Buffer.from(providedSig);
  const expectedBuf = Buffer.from(expectedSig);
  // Length pre-check: timingSafeEqual throws on unequal-length buffers.
  if (providedBuf.length !== expectedBuf.length) return false;
  return timingSafeEqual(providedBuf, expectedBuf);
};
