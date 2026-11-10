import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { prisma } from "./prisma.js";

// The OAuth callback is necessarily exempt from the API token gate — Trakt
// redirects a browser to it and cannot send a bearer token — so the only thing
// binding "who started this linking flow" to "who is finishing it" is the
// `state` parameter. Without one, anyone who can lure the operator's browser to
// the callback URL with an authorization code of their own can silently bind
// this install to *their* Trakt account, and the scheduled poller then imports
// their history into the operator's library.
//
// `state` alone binds the callback to *a* flow this install started, not to
// the browser that started it: an authorize URL is a link like any other, so
// one account could start a flow and send the link to another, whose Trakt
// tokens would then land on the first account's profile. So each flow also
// sets a random nonce in an httpOnly cookie on the browser that started it
// (see `routes/trakt.ts`), stores only its digest here, and the callback is
// refused unless the browser finishing the flow presents the same nonce.
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

// The browser nonce: 32 random bytes as base64url, so it is cookie-safe as is.
const BROWSER_NONCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const DIGEST_PATTERN = /^[0-9a-f]{64}$/;

const nonceDigest = (nonce: string): string => createHash("sha256").update(nonce).digest("hex");

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

/** As stored: the flow, and the digest of the nonce its browser was given. */
type StoredFlow = OAuthFlow & { browserNonceHash: string };

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
export const createOAuthState = async (
  owner: string
): Promise<{ state: string; codeChallenge: string; browserNonce: string }> => {
  const state = randomBytes(32).toString("hex");
  const codeVerifier = randomBytes(32).toString("base64url");
  const browserNonce = randomBytes(32).toString("base64url");
  // The value is whose connection this is: the callback arrives with no
  // session, so the state is what says which profile the tokens belong to.
  const flow: StoredFlow = { owner, codeVerifier, browserNonceHash: nonceDigest(browserNonce) };
  await prisma.kV.create({
    data: { key: stateKey(state), value: JSON.stringify(flow), updatedAt: new Date() },
  });
  await prisma.kV.deleteMany({
    where: { key: { startsWith: STATE_KEY_PREFIX }, updatedAt: { lt: cutoff() } },
  });
  return { state, codeChallenge: codeChallengeFor(codeVerifier), browserNonce };
};

const parseFlow = (value: string): StoredFlow | null => {
  try {
    const parsed = JSON.parse(value) as Partial<StoredFlow> | null;
    if (!parsed || typeof parsed.owner !== "string" || !parsed.owner) return null;
    if (typeof parsed.codeVerifier !== "string" || !VERIFIER_PATTERN.test(parsed.codeVerifier)) return null;
    if (typeof parsed.browserNonceHash !== "string" || !DIGEST_PATTERN.test(parsed.browserNonceHash)) return null;
    return { owner: parsed.owner, codeVerifier: parsed.codeVerifier, browserNonceHash: parsed.browserNonceHash };
  } catch {
    return null;
  }
};

const isStartingBrowser = (flow: StoredFlow, browserNonce: unknown): boolean => {
  if (typeof browserNonce !== "string" || !BROWSER_NONCE_PATTERN.test(browserNonce)) return false;
  return timingSafeEqual(Buffer.from(nonceDigest(browserNonce)), Buffer.from(flow.browserNonceHash));
};

/**
 * Verifies and consumes a `state` returned by Trakt, returning the flow it was
 * minted for, or null when it is missing, malformed, unknown or expired, or
 * when `browserNonce` (the cookie the callback's browser sent) is not the one
 * the flow handed the browser that started it.
 *
 * A browser that isn't the starting one is turned away *before* the delete,
 * so a forwarded link opened somewhere else cannot burn the real flow.
 *
 * The delete *is* the check: a single `deleteMany` scoped to the exact key and
 * an unexpired timestamp reports how many rows it removed, so two concurrent
 * callbacks carrying the same state cannot both pass. Expired rows are left for
 * the sweep in `createOAuthState` — deleting them here would make an expired
 * state indistinguishable from an unknown one for the *next* attempt, which is
 * the same answer either way.
 */
export const consumeOAuthState = async (candidate: unknown, browserNonce: unknown): Promise<OAuthFlow | null> => {
  if (typeof candidate !== "string" || !STATE_PATTERN.test(candidate)) return null;
  const where = { key: stateKey(candidate), updatedAt: { gte: cutoff() } };
  const row = await prisma.kV.findFirst({ where, select: { value: true } });
  if (!row) return null;
  // A state minted before states carried a PKCE verifier and a browser nonce
  // (a bare owner id, or nothing at all) cannot finish, so it is refused; the
  // sweep in `createOAuthState` removes it.
  const flow = parseFlow(row.value);
  if (!flow || !isStartingBrowser(flow, browserNonce)) return null;
  const { count } = await prisma.kV.deleteMany({ where });
  if (count === 0) return null;
  return { owner: flow.owner, codeVerifier: flow.codeVerifier };
};
