// Fastify's request logging includes the full URL, query string and all, and a
// reverse proxy in front of it writes the same line to its own access log. Any
// credential that travels as a query parameter therefore ends up in plaintext
// in two places that outlive the request.
//
// The webhook secret is the one that has no way around it: Plex cannot attach
// custom headers to its outgoing webhooks, so `?token=<WEBHOOK_SECRET>` is the
// only form it can send. Redacting on the way into the log is what keeps that
// out of the API's own logs — the proxy's copy is outside our reach, which is
// why the README steers senders that *can* set headers to `x-webhook-secret`.
//
// It lives here rather than in either service so the API's and the addon's
// loggers share one list of parameters to mask.

const SENSITIVE_QUERY_PARAMS = new Set([
  "token",
  "secret",
  "api_key",
  "apikey",
  "access_token",
  "refresh_token",
  "password",
  "pin",
]);

const REDACTED = "REDACTED";

const redactPair = (pair: string): string => {
  const eq = pair.indexOf("=");
  // No `=` means no value to leak — a bare `?token` says nothing on its own.
  if (eq === -1) return pair;
  const key = pair.slice(0, eq);
  if (!isSensitiveQueryParam(key)) return pair;
  return `${key}=${REDACTED}`;
};

// Keys are rarely percent-encoded, but a sender that encodes one shouldn't be
// able to slip a secret past the match by writing `%74oken`.
const decodeKey = (key: string): string => {
  try {
    return decodeURIComponent(key.replace(/\+/g, " "));
  } catch {
    // Malformed escapes (a lone `%`) throw; the raw key is the best guess left.
    return key;
  }
};

// Two routes carry a secret in the path itself, where Stremio needs it because
// an add-on URL is all it keeps (#636):
// - the addon service's `/u/<profileId>/<profileKey>/…`
// - the API's `/addon/stremio/<secret>/…`
// The segment after each prefix is masked wherever it appears in the path, so a
// path prefix in front (`/api/addon/stremio/…`) or a full URL (Sentry's
// `request.url`) is caught too. The profile id stays readable: it names a
// profile, proves nothing, and is what makes the line useful.
const SENSITIVE_PATH_SEGMENTS: readonly RegExp[] = [
  /(\/u\/[^/?#]+\/)[^/?#]+/g,
  /(\/addon\/stremio\/)[^/?#]+/g,
];

const redactPath = (path: string): string =>
  SENSITIVE_PATH_SEGMENTS.reduce((current, pattern) => current.replace(pattern, `$1${REDACTED}`), path);

/** Returns a raw query string (no leading `?`) with sensitive values masked. */
export const redactQueryString = (query: string): string => query.split("&").map(redactPair).join("&");

/**
 * Returns `url` with the secret path segments and the values of known-sensitive
 * query parameters replaced, leaving everything else intact so logs stay useful.
 */
export const redactUrl = (url: string): string => {
  const queryStart = url.indexOf("?");
  if (queryStart === -1) return redactPath(url);

  const path = url.slice(0, queryStart);
  const query = url.slice(queryStart + 1);

  return `${redactPath(path)}?${redactQueryString(query)}`;
};

/** Whether a query parameter's name is one {@link redactUrl} masks. */
export const isSensitiveQueryParam = (key: string): boolean =>
  SENSITIVE_QUERY_PARAMS.has(decodeKey(key).toLowerCase());

/**
 * The fields Fastify's default `req` serializer reads. Declared structurally so
 * this package needs no dependency on Fastify itself; a `FastifyRequest`
 * satisfies it.
 */
type LoggableRequest = {
  method: string;
  url: string;
  // `?: T | undefined`, not `?: T`: Fastify types these as present-and-possibly
  // -undefined, and under `exactOptionalPropertyTypes` the bare `?:` made the
  // claim above — that a `FastifyRequest` satisfies this — untrue, which showed
  // up as `fastify()` silently picking its HTTP/2 overload instead.
  host?: string | undefined;
  ip?: string | undefined;
  socket?: { remotePort?: number | undefined } | undefined;
};

/**
 * Fastify's default `req` log serializer with the URL run through
 * {@link redactUrl}. Every other field is reproduced as Fastify emits it, so
 * the shape of an access log line is unchanged.
 */
export const redactedRequestSerializer = (request: LoggableRequest) => {
  const remotePort = request.socket?.remotePort;
  return {
    method: request.method,
    url: redactUrl(request.url),
    // The three optional fields are omitted rather than set to `undefined`.
    // The log line is identical either way — JSON drops an undefined value —
    // but Fastify's serializer return type spells them `?: string`, which under
    // `exactOptionalPropertyTypes` means "the key may be missing", not "the
    // value may be undefined". Setting them made `fastify()` fall through to
    // its HTTP/2 overload, and every route registration after it fail.
    ...(request.host !== undefined ? { host: request.host } : {}),
    ...(request.ip !== undefined ? { remoteAddress: request.ip } : {}),
    ...(remotePort !== undefined ? { remotePort } : {}),
  };
};
