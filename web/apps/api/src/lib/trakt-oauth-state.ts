import { createHash, randomBytes } from "node:crypto";
import { prisma } from "./prisma.js";

// The OAuth callback is necessarily exempt from the API token gate — Trakt
// redirects a browser to it and cannot send a bearer token — so the only thing
// binding "who started this linking flow" to "who is finishing it" is the
// `state` parameter. Without one, anyone who can lure the operator's browser to
// the callback URL with an authorization code of their own can silently bind
// this install to *their* Trakt account, and the scheduled poller then imports
// their history into the operator's library.
const STATE_KEY_PREFIX = "trakt:oauth:state:";

// Long enough to authorize on trakt.tv (including logging in first), short
// enough that a stolen or leaked authorize URL stops working quickly.
export const OAUTH_STATE_TTL_MS = 10 * 60 * 1000;

// 32 random bytes, hex-encoded. The value only ever travels in a URL, so hex
// keeps it free of characters that would need escaping on either leg.
const STATE_PATTERN = /^[0-9a-f]{64}$/;

// PKCE (RFC 7636): 32 random bytes as base64url is a 43-character verifier,
// the shortest Trakt accepts, drawn from the unreserved set it requires.
const VERIFIER_PATTERN = /^[A-Za-z0-9\-._~]{43,128}$/;

const stateKey = (state: string) => `${STATE_KEY_PREFIX}${state}`;

/** The S256 challenge for a verifier — the only method Trakt supports. */
export const codeChallengeFor = (codeVerifier: string): string =>
  createHash("sha256").update(codeVerifier).digest("base64url");

export type OAuthFlow = {
  /** The profile that started the flow, and so the one the tokens belong to. */
  owner: string;
  /** Sent with the code at the token exchange, in place of a client secret. */
  codeVerifier: string;
};

const cutoff = () => new Date(Date.now() - OAUTH_STATE_TTL_MS);

/**
 * Mints a single-use `state` and PKCE verifier and persists them, returning
 * the state and the verifier's challenge to embed in the authorize URL. The
 * verifier itself never leaves the server until the token exchange.
 *
 * Expired rows are swept here rather than on a timer: the only thing that
 * creates them is a click on "Connect Trakt", so the table cannot grow without
 * someone repeatedly starting the flow, and each new start pays for the
 * cleanup of the ones before it.
 */
export const createOAuthState = async (owner: string): Promise<{ state: string; codeChallenge: string }> => {
  const state = randomBytes(32).toString("hex");
  const codeVerifier = randomBytes(32).toString("base64url");
  // The value is whose connection this is: the callback arrives with no
  // session, so the state is what says which profile the tokens belong to.
  const flow: OAuthFlow = { owner, codeVerifier };
  await prisma.kV.create({
    data: { key: stateKey(state), value: JSON.stringify(flow), updatedAt: new Date() },
  });
  await prisma.kV.deleteMany({
    where: { key: { startsWith: STATE_KEY_PREFIX }, updatedAt: { lt: cutoff() } },
  });
  return { state, codeChallenge: codeChallengeFor(codeVerifier) };
};

const parseFlow = (value: string): OAuthFlow | null => {
  try {
    const parsed = JSON.parse(value) as Partial<OAuthFlow> | null;
    if (!parsed || typeof parsed.owner !== "string" || !parsed.owner) return null;
    if (typeof parsed.codeVerifier !== "string" || !VERIFIER_PATTERN.test(parsed.codeVerifier)) return null;
    return { owner: parsed.owner, codeVerifier: parsed.codeVerifier };
  } catch {
    return null;
  }
};

/**
 * Verifies and consumes a `state` returned by Trakt, returning the flow it was
 * minted for, or null when it is missing, malformed, unknown or expired.
 *
 * The delete *is* the check: a single `deleteMany` scoped to the exact key and
 * an unexpired timestamp reports how many rows it removed, so two concurrent
 * callbacks carrying the same state cannot both pass. Expired rows are left for
 * the sweep in `createOAuthState` — deleting them here would make an expired
 * state indistinguishable from an unknown one for the *next* attempt, which is
 * the same answer either way.
 */
export const consumeOAuthState = async (candidate: unknown): Promise<OAuthFlow | null> => {
  if (typeof candidate !== "string" || !STATE_PATTERN.test(candidate)) return null;
  const where = { key: stateKey(candidate), updatedAt: { gte: cutoff() } };
  const row = await prisma.kV.findFirst({ where, select: { value: true } });
  if (!row) return null;
  const { count } = await prisma.kV.deleteMany({ where });
  if (count === 0) return null;
  // A state minted before states carried a PKCE verifier (a bare owner id, or
  // nothing at all) cannot finish the exchange, so it is refused.
  return parseFlow(row.value);
};
