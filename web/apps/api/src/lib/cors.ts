import type { FastifyReply, FastifyRequest } from "fastify";
import { appendVary } from "./vary.js";

const IS_DEVELOPMENT = process.env.NODE_ENV === "development";
const FETCH_ALLOWED_ORIGINS = (process.env.FETCH_ALLOWED_ORIGINS ?? "")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean);
export const ALLOWED_ORIGINS = IS_DEVELOPMENT ? ["*"] : Array.from(new Set(FETCH_ALLOWED_ORIGINS));

// PUT and If-Match are for the versioned per-profile documents (TV settings,
// Home layout), which a cross-origin web app saves with both.
const CORS_METHODS = "GET,POST,PUT,DELETE,PATCH,OPTIONS";
const CORS_HEADERS = "Authorization,Content-Type,If-Match,X-Profile-Id,X-Profile-Token";

export const isAllowedOrigin = (origin: string | undefined): boolean => {
  if (IS_DEVELOPMENT) return true;
  if (!origin) return false;
  return ALLOWED_ORIGINS.includes(origin);
};

export const applyCorsHeaders = (request: FastifyRequest, reply: FastifyReply) => {
  const origin = request.headers.origin;

  if (IS_DEVELOPMENT) {
    reply.header("Access-Control-Allow-Origin", origin ?? "*");
    reply.header("Access-Control-Allow-Methods", CORS_METHODS);
    reply.header("Access-Control-Allow-Headers", CORS_HEADERS);
    appendVary(reply, "Origin");
    return;
  }

  if (!origin || !ALLOWED_ORIGINS.includes(origin)) return;

  reply.header("Access-Control-Allow-Origin", origin);
  reply.header("Access-Control-Allow-Methods", CORS_METHODS);
  reply.header("Access-Control-Allow-Headers", CORS_HEADERS);
  // Appended rather than set: the HTTP-caching hook writes `Vary` too, later
  // in the lifecycle, and a plain `reply.header` from either side drops the
  // other's fields.
  appendVary(reply, "Origin");
};
