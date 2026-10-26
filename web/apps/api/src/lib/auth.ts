import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import { DEVICE_TOKEN_PREFIX, findDeviceByToken } from "./device-token.js";
import { SESSION_TOKEN_PREFIX } from "./request-auth.js";
import { findSession } from "./sessions.js";

declare module "fastify" {
  interface FastifyRequest {
    /** Set when the request authenticated with a paired device's token. */
    deviceId?: string;
  }
}

const API_TOKEN = process.env.API_TOKEN;

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
 */
export const verifyToken = async (request: FastifyRequest, reply: FastifyReply) => {
  if (!API_TOKEN) {
    request.log.error("API_TOKEN is not configured");
    return reply.code(500).send({ error: "API token is not configured" });
  }

  const token = bearerToken(request);
  if (!token) return unauthorized(reply);

  if (token.startsWith(SESSION_TOKEN_PREFIX)) {
    const session = await findSession(token);
    if (!session) return unauthorized(reply);
    request.auth = session;
    return;
  }

  if (token.startsWith(DEVICE_TOKEN_PREFIX)) {
    const device = await findDeviceByToken(token);
    if (!device) return unauthorized(reply);
    request.deviceId = device.id;
    request.auth = { kind: "device", deviceId: device.id };
    return;
  }

  if (!isApiToken(token)) return unauthorized(reply);
  request.auth = { kind: "token" };
};

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
