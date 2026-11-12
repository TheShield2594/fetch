import { ApiError } from "../api/client";

/**
 * The sentence a page shows when a load fails, in place of whatever the server
 * or the network layer said.
 *
 * A raw "Too Many Requests" or "Internal Server Error" is the server's status
 * line, not a message: it tells the user nothing they can act on except that
 * something is wrong, and reads as a fault in their library rather than a
 * moment's hiccup. Those, and any failure to reach the server at all, become
 * one plain "couldn't load, try again" sentence.
 *
 * Kept as-is: a 4xx the API answered with on purpose (a locked profile, a bad
 * request), whose message is written for a person and says something specific,
 * and the offline message, which already says what to do.
 */
export function loadErrorMessage(err: unknown, what: string): string {
  const fallback = `Couldn't load ${what}. Try again in a moment.`;
  if (err instanceof ApiError) {
    if (err.status === 429 || err.status >= 500) return fallback;
    return err.message || fallback;
  }
  if (err instanceof Error && /offline/i.test(err.message)) return err.message;
  return fallback;
}
