import type { FastifyReply, FastifyRequest } from "fastify";
import { isAllowedOrigin } from "./cors.js";

/**
 * The website's credentials, carried in cookies rather than handed to the page.
 *
 * A browser used to keep its session token (and a PIN-protected profile's
 * access token) in `localStorage` and send them as headers, which left both
 * readable by any script that ever ran on the page. They now travel as
 * `HttpOnly` cookies the page cannot read at all. Everything that is not a
 * browser — the paired TVs, the addon service, scripts holding `API_TOKEN` —
 * keeps sending `Authorization: Bearer …` exactly as before; a bearer header
 * always wins over a cookie.
 *
 * Attributes, and why:
 *
 * - `HttpOnly`: the point of the change.
 * - `SameSite=Lax`: the documented deployments are same-site — the web app and
 *   the API on one host at different ports (`192.168.1.25:7002` → `:7000`;
 *   ports do not make a different *site*), or one origin behind a proxy — so
 *   the cookie rides along on the app's own `fetch`es while a cross-site page
 *   cannot make a browser send it on anything but a top-level GET navigation,
 *   which changes nothing. `Strict` would add nothing the CSRF check below
 *   does not already give, and WebKit has a history of withholding `Strict`
 *   cookies from pages that were themselves reached by a cross-site link.
 * - `Secure` when the request reached us over HTTPS — the socket, a proxy's
 *   `X-Forwarded-Proto`, or an `https://` page origin (a page served over
 *   HTTPS can only call an HTTPS API) — and then the `__Host-` name, which
 *   the browser only accepts with `Secure`, `Path=/` and no `Domain`, so a
 *   sibling subdomain cannot plant one. A plain-HTTP LAN install gets the
 *   unprefixed name without `Secure`, because a `Secure` cookie set over HTTP
 *   is dropped and nobody could sign in.
 * - `Path=/`, host-only (no `Domain`): sent to this host only.
 *
 * Cookies ignore the port, so on a LAN install the browser also sends these to
 * the web (:7002) and addon (:7001) containers. Neither reads or logs them.
 */

export const SESSION_COOKIE = "fetch_session";
export const PROFILE_COOKIE = "fetch_profile";
const SECURE_PREFIX = "__Host-";

/**
 * Sent by the website on every request. On a cookie-authenticated write it is
 * the CSRF guard: a cross-site page can make a browser send the cookie, but it
 * cannot add a custom header without a CORS preflight, and the preflight only
 * succeeds for an allowed origin. On sign-in it is also how the website asks for
 * a cookie rather than a token in the response body.
 */
export const WEB_CLIENT_HEADER = "x-fetch-client";
export const WEB_CLIENT_VALUE = "web";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

const headerValue = (value: string | string[] | undefined): string | undefined =>
  Array.isArray(value) ? value[0] : value;

/** `Cookie:` parsed into name → value. Later duplicates do not override earlier ones. */
export const parseCookies = (header: string | undefined): Record<string, string> => {
  const cookies: Record<string, string> = {};
  if (!header) return cookies;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    const name = part.slice(0, eq).trim();
    if (!name || name in cookies) continue;
    let value = part.slice(eq + 1).trim();
    if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) value = value.slice(1, -1);
    try {
      cookies[name] = decodeURIComponent(value);
    } catch {
      cookies[name] = value;
    }
  }
  return cookies;
};

/** The named cookie on this request, preferring the `__Host-` variant. */
export const readCookie = (request: FastifyRequest, name: string): string | null => {
  const cookies = parseCookies(headerValue(request.headers.cookie));
  return cookies[`${SECURE_PREFIX}${name}`] || cookies[name] || null;
};

/** Whether the browser reached this API over HTTPS, as far as can be told. */
export const isSecureRequest = (request: FastifyRequest): boolean => {
  if (request.protocol === "https") return true;
  const forwarded = headerValue(request.headers["x-forwarded-proto"])?.split(",")[0]?.trim().toLowerCase();
  if (forwarded === "https") return true;
  // An HTTPS page cannot call an HTTP API (mixed content), so an https Origin
  // means this request is HTTPS end to end even if no proxy said so.
  const origin = headerValue(request.headers.origin);
  return Boolean(origin?.toLowerCase().startsWith("https://"));
};

type CookieOptions = { maxAgeSec: number; secure: boolean };

const serialize = (name: string, value: string, { maxAgeSec, secure }: CookieOptions): string =>
  [
    `${name}=${encodeURIComponent(value)}`,
    "Path=/",
    `Max-Age=${Math.max(0, Math.floor(maxAgeSec))}`,
    ...(maxAgeSec <= 0 ? ["Expires=Thu, 01 Jan 1970 00:00:00 GMT"] : []),
    "HttpOnly",
    "SameSite=Lax",
    ...(secure ? ["Secure"] : []),
  ].join("; ");

/** Adds to `Set-Cookie` rather than replacing what another call already put there. */
const appendSetCookie = (reply: FastifyReply, cookies: string[]) => {
  const existing = reply.getHeader("set-cookie");
  const current = Array.isArray(existing) ? existing.map(String) : existing === undefined ? [] : [String(existing)];
  reply.header("set-cookie", [...current, ...cookies]);
};

const setCookie = (request: FastifyRequest, reply: FastifyReply, name: string, value: string, maxAgeSec: number) => {
  const secure = isSecureRequest(request);
  appendSetCookie(reply, [serialize(secure ? `${SECURE_PREFIX}${name}` : name, value, { maxAgeSec, secure })]);
};

export const setSessionCookie = (request: FastifyRequest, reply: FastifyReply, token: string, maxAgeSec: number) =>
  setCookie(request, reply, SESSION_COOKIE, token, maxAgeSec);

export const setProfileCookie = (request: FastifyRequest, reply: FastifyReply, token: string, maxAgeSec: number) =>
  setCookie(request, reply, PROFILE_COOKIE, token, maxAgeSec);

/**
 * Expires the website's cookies. Both spellings of each: the plain one always
 * (it may be left over from the install being reached over HTTP), the `__Host-`
 * one only over HTTPS, the one place a browser will accept the `Secure` that
 * clearing it requires.
 */
export const clearAuthCookies = (request: FastifyRequest, reply: FastifyReply, names = [SESSION_COOKIE, PROFILE_COOKIE]) => {
  const secure = isSecureRequest(request);
  const cookies: string[] = [];
  for (const name of names) {
    cookies.push(serialize(name, "", { maxAgeSec: 0, secure: false }));
    if (secure) cookies.push(serialize(`${SECURE_PREFIX}${name}`, "", { maxAgeSec: 0, secure: true }));
  }
  appendSetCookie(reply, cookies);
};

/** Whether the caller is the website, which wants cookies rather than tokens. */
export const isWebClient = (request: FastifyRequest): boolean =>
  headerValue(request.headers[WEB_CLIENT_HEADER])?.trim().toLowerCase() === WEB_CLIENT_VALUE;

const originOf = (url: string | undefined): string | null => {
  if (!url) return null;
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
};

const WEB_PUBLIC_ORIGIN = originOf((process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.trim());

/**
 * Whether `origin` is the website: an origin CORS already allows, the
 * configured `FETCH_WEB_PUBLIC`, or this API's own host (the same-origin proxy
 * deployment, which needs no CORS entry at all).
 */
export const isTrustedWebOrigin = (request: FastifyRequest, origin: string): boolean => {
  if (isAllowedOrigin(origin)) return true;
  const normalized = originOf(origin);
  if (!normalized) return false;
  if (normalized === WEB_PUBLIC_ORIGIN) return true;
  const originHost = new URL(normalized).host.toLowerCase();
  const hosts = [headerValue(request.headers.host), headerValue(request.headers["x-forwarded-host"])?.split(",")[0]]
    .map((host) => host?.trim().toLowerCase())
    .filter(Boolean);
  return hosts.includes(originHost);
};

/**
 * Cross-site request forgery check for a cookie-authenticated request. Reads
 * pass. A write must carry `X-Fetch-Client: web`, and when the browser sent an
 * `Origin` (every current one does on a write) it must be the website's.
 * Bearer-authenticated requests never reach this: a cross-site page cannot set
 * `Authorization`.
 */
export const passesCsrfCheck = (request: FastifyRequest): boolean => {
  if (SAFE_METHODS.has(request.method.toUpperCase())) return true;
  if (!isWebClient(request)) return false;
  const origin = headerValue(request.headers.origin);
  if (origin === undefined) return true;
  return isTrustedWebOrigin(request, origin);
};

export const CSRF_REJECTED_RESPONSE = {
  error: "This request was refused because it did not come from the Fetch website",
  code: "csrf_rejected",
} as const;
