// Kept apart from `tmdb-client.ts` so the error handler and the routes can
// recognise a missing key even in tests that mock the client module.

/**
 * Thrown by `getTmdb()` when no key is saved in Settings and `TMDB_API_KEY` is
 * unset. It is an expected state on an install that skipped the TMDB step, not
 * a fault: routes let it propagate and the app's error handler answers it once,
 * as `503 { code: "tmdb_not_configured" }`, without an error log or a Sentry
 * event, so the website and the TV can tell the user to add a key.
 */
export class TmdbNotConfiguredError extends Error {
  readonly code = "tmdb_not_configured";
  readonly statusCode = 503;

  constructor() {
    super("Add a TMDB API key in Settings to load movie and show details.");
    this.name = "TmdbNotConfiguredError";
  }
}

/**
 * Matched by its code rather than `instanceof`, so it still holds when the
 * error comes from a second copy of this module (tests reset the module
 * registry between route plugins).
 */
export const isTmdbNotConfiguredError = (error: unknown): error is TmdbNotConfiguredError =>
  error instanceof Error && (error as { code?: unknown }).code === "tmdb_not_configured";

/**
 * For a route's `catch` that would otherwise turn every failure into a 500:
 * hands a missing key on to the app's error handler and lets anything else
 * fall through to the route's own handling.
 */
export const rethrowIfTmdbNotConfigured = (error: unknown): void => {
  if (isTmdbNotConfiguredError(error)) throw error;
};
