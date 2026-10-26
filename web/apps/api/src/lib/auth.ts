import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import { SESSION_TOKEN_PREFIX } from "./request-auth.js";
import { findSession } from "./sessions.js";

const API_TOKEN = process.env.API_TOKEN;

const toSha256Digest = (value: string) => createHash("sha256").update(value).digest();

const unauthorized = (reply: FastifyReply) =>
  // WWW-Authenticate (RFC 6750) marks this as a bearer-token failure specifically,
  // distinct from route-level 401s (e.g. an incorrect profile PIN) — the web client
  // uses it to tell "you are signed out, sign in again" apart from other 401s.
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
 * Accepts either `API_TOKEN` or a signed-in session's token, and records which
 * on `request.auth` for the routes to scope by.
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

  if (!isApiToken(token)) return unauthorized(reply);
  request.auth = { kind: "token" };
};
