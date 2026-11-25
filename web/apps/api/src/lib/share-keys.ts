import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * Keys for the links that work without signing in: a profile's calendar feed
 * and a shared list.
 *
 * A calendar app polling a feed, or a friend opening a list, can't send a
 * bearer token, so the link itself has to be the credential — the same answer
 * the Stremio addon URLs use (lib/stremio-secret.ts), and built the same way:
 * an HMAC of `API_TOKEN`, so nothing is stored that a database dump could
 * replay, and a link that leaks gives away nothing but itself.
 *
 * Unlike a Stremio URL the link also names what it is for (the profile, the
 * list), so checking one is a single derivation rather than a walk over every
 * row. The `generation` is a column on that row: a positive number while the
 * link is on, the next number when it is replaced, and the same number negated
 * while it is off — kept rather than cleared, so turning a link back on moves
 * to a number no address was ever made from instead of reviving the old one.
 * Null is a link that has never been on. Rotating `API_TOKEN` replaces every
 * link.
 */
export type ShareKeyPurpose = "calendar-feed" | "list";

const KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export const deriveShareKey = (purpose: ShareKeyPurpose, id: string, generation: number): string | null => {
  const apiToken = process.env.API_TOKEN;
  if (!apiToken) return null;
  return createHmac("sha256", apiToken).update(`fetch-share:v1:${purpose}:${id}:${generation}`).digest("base64url");
};

/** Whether a stored generation is a link that is on. */
export const isLiveGeneration = (generation: number | null | undefined): generation is number =>
  typeof generation === "number" && generation > 0;

/** The generation for turning a link on, or replacing it: one past any it has had. */
export const nextGeneration = (generation: number | null | undefined): number => Math.abs(generation ?? 0) + 1;

/** The generation for a link turned off. */
export const retiredGeneration = (generation: number): number => -Math.abs(generation);

/** Whether `candidate` is the current key for this link. False while the link is off. */
export const shareKeyMatches = (
  candidate: unknown,
  purpose: ShareKeyPurpose,
  id: string,
  generation: number | null | undefined
): boolean => {
  if (!isLiveGeneration(generation)) return false;
  if (typeof candidate !== "string" || !KEY_PATTERN.test(candidate)) return false;
  const expected = deriveShareKey(purpose, id, generation);
  if (!expected) return false;
  // Both are 43-character base64url digests, so the lengths always agree.
  return timingSafeEqual(Buffer.from(candidate), Buffer.from(expected));
};

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const KEY = "[A-Za-z0-9_-]{43}";
const PUBLIC_SHARE_PATHS = [
  new RegExp(`^/calendar/feed/${UUID}/${KEY}/calendar\\.ics$`, "i"),
  new RegExp(`^/shared/lists/${UUID}/${KEY}$`, "i"),
];

/**
 * Whether a request path is shaped like one of these links, and so may skip
 * the bearer-token check. Structural only, like `isStremioSecretPath`: the
 * route is what verifies the key and answers 404 for a wrong one.
 */
export const isShareLinkPath = (url: string): boolean => {
  const path = url.split("?", 1)[0] ?? url;
  return PUBLIC_SHARE_PATHS.some((pattern) => pattern.test(path));
};
