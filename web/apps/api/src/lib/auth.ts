import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import { DEVICE_TOKEN_PREFIX, findDeviceByToken } from "./device-token.js";
import { SESSION_TOKEN_PREFIX } from "./request-auth.js";
import { findSession, SESSION_COOKIE_MAX_AGE_SEC } from "./sessions.js";
import {
  clearAuthCookies,
  CSRF_REJECTED_RESPONSE,
  passesCsrfCheck,
  readCookie,
  SESSION_COOKIE,
  setSessionCookie,
} from "./session-cookie.js";

declare module "fastify" {
  interface FastifyRequest {
    /** Set when the request authenticated with a paired device's token. */
    deviceId?: string;
    /**
     * How the credential arrived: `cookie` for the website's session cookie,
     * `bearer` for an `Authorization` header (TVs, the addon, scripts, and a
     * browser that has not yet moved its old token into a cookie).
     */
    authVia?: "cookie" | "bearer";
  }
}

const API_TOKEN = process.env.API_TOKEN;

/**
 * All a session may do while its password must be changed, as method and
 * path: say who it is, change the password, sign out. Not `PATCH` or
 * `DELETE /auth/me`, which a temporary password must not be able to do.
 */
//
// `POST /auth/session` too: it only moves this same session from a bearer
// token into the website's cookie, which the browser needs to get as far as
// the change-password screen.
export const PASSWORD_CHANGE_ROUTES = new Set([
  "GET /auth/me",
  "POST /auth/password",
  "POST /auth/logout",
  "POST /auth/session",
]);

const toSha256Digest = (value: string) => createHash("sha256").update(value).digest();

// WWW-Authenticate (RFC 6750) marks this as a bearer-token failure specifically,
// distinct from route-level 401s (e.g. an incorrect profile PIN) — the web client
// uses it to tell "you are signed out, sign in again" apart from other 401s, and
// a paired TV uses it to tell "this device was signed out" apart from them.
const unauthorized = (reply: FastifyReply) =>
  reply.code(401).header("WWW-Authenticate", "Bearer").send({ error: "Unauthorized" });

/** Whether `token` is `API_TOKEN`, compared in constant time. */
export const isApiToken = (token: string): boolean =>
  Boolean(API_TOKEN) && timingSafeEqual(toSha256Digest(token), toSha256Digest(API_TOKEN ?? ""));

/** The bearer token on a request, or null when there is none. */
export const bearerToken = (request: FastifyRequest): string | null => {
  const authHeader = request.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) return null;
  return authHeader.slice("Bearer ".length).trim() || null;
};

/**
 * Accepts `API_TOKEN`, a signed-in session's token or a paired device's token,
 * and records which on `request.auth` for the routes to scope by. Sessions and
 * devices are looked up by the hash of their token, so the comparison is the
 * database's equality on a digest rather than on the secret itself.
 *
 * The token comes from `Authorization: Bearer …` when there is one, and
 * otherwise from the website's session cookie (lib/session-cookie.ts). A
 * cookie can only ever carry a session — never `API_TOKEN` or a device token —
 * and a write made with one must pass the CSRF check.
 */
export const verifyToken = async (request: FastifyRequest, reply: FastifyReply) => {
  if (!API_TOKEN) {
    request.log.error("API_TOKEN is not configured");
    return reply.code(500).send({ error: "API token is not configured" });
  }

  const token = bearerToken(request);
  if (!token) return verifySessionCookie(request, reply);

  if (token.startsWith(SESSION_TOKEN_PREFIX)) {
    const session = await findSession(token);
    if (!session) return unauthorized(reply);
    if (refuseUntilPasswordChanged(session, request, reply)) return;
    // `renewed` is only for the cookie path below; a bearer token has nothing
    // to re-issue.
    request.auth = toPrincipal(session);
    request.authVia = "bearer";
    return;
  }

  if (token.startsWith(DEVICE_TOKEN_PREFIX)) {
    const device = await findDeviceByToken(token);
    if (!device) return unauthorized(reply);
    request.deviceId = device.id;
    request.auth = { kind: "device", deviceId: device.id, accountId: device.accountId };
    return;
  }

  if (!isApiToken(token)) return unauthorized(reply);
  request.auth = { kind: "token" };
  request.authVia = "bearer";
};

const verifySessionCookie = async (request: FastifyRequest, reply: FastifyReply) => {
  const token = readCookie(request, SESSION_COOKIE);
  if (!token) return unauthorized(reply);

  const session = token.startsWith(SESSION_TOKEN_PREFIX) ? await findSession(token) : null;
  if (!session) {
    // Signed out, expired or revoked: take the dead cookie away with the 401
    // so the browser stops sending it.
    clearAuthCookies(request, reply);
    return unauthorized(reply);
  }

  if (!passesCsrfCheck(request)) {
    return reply.code(403).send(CSRF_REJECTED_RESPONSE);
  }

  if (refuseUntilPasswordChanged(session, request, reply)) return;

  request.auth = toPrincipal(session);
  request.authVia = "cookie";
  // The row's expiry just moved; the cookie's moves with it, so an active
  // browser stays signed in for as long as its session does — and no longer
  // than the session's absolute lifetime.
  if (session.renewed) setSessionCookie(request, reply, token, cookieMaxAgeSec(session.expiresAt));
};

const cookieMaxAgeSec = (expiresAt: Date) =>
  Math.min(SESSION_COOKIE_MAX_AGE_SEC, Math.max(0, Math.floor((expiresAt.getTime() - Date.now()) / 1000)));

/**
 * A password someone else set (the admin, or `fetch reset-password`) is only
 * good for choosing a new one: until then the session can say who it is,
 * change the password and sign out, and nothing else — whether it arrived as
 * a bearer token or as the website's cookie. Answers the request and returns
 * true when it refuses.
 */
const refuseUntilPasswordChanged = (
  session: { mustChangePassword?: boolean | undefined },
  request: FastifyRequest,
  reply: FastifyReply
): boolean => {
  if (!session.mustChangePassword) return false;
  if (PASSWORD_CHANGE_ROUTES.has(`${request.method} ${request.url.split("?", 1)[0] ?? request.url}`)) return false;
  void reply.code(403).send({ error: "Choose a new password to continue", code: "password_change_required" });
  return true;
};

const toPrincipal = (session: NonNullable<Awaited<ReturnType<typeof findSession>>>) => ({
  kind: session.kind,
  sessionId: session.sessionId,
  accountId: session.accountId,
  isAdmin: session.isAdmin,
  ...(session.mustChangePassword !== undefined ? { mustChangePassword: session.mustChangePassword } : {}),
});

/**
 * Turns away a request made with a device token. Pairing and revoking devices
 * stay with the website: a device that could pair others could outlive its own
 * revocation by minting a replacement first.
 */
export const requireOwnerToken = async (request: FastifyRequest, reply: FastifyReply) => {
  if (request.deviceId) {
    return reply.code(403).send({ error: "Devices can't manage other devices", code: "owner_token_required" });
  }
};
