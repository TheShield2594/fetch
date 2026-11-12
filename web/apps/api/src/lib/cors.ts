import type { FastifyReply, FastifyRequest } from "fastify";
import { appendVary } from "./vary.js";

const IS_DEVELOPMENT = process.env.NODE_ENV === "development";
const FETCH_ALLOWED_ORIGINS = (process.env.FETCH_ALLOWED_ORIGINS ?? "")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean);
export const ALLOWED_ORIGINS = IS_DEVELOPMENT ? ["*"] : Array.from(new Set(FETCH_ALLOWED_ORIGINS));

// PUT and If-Match are for the versioned per-profile documents (TV settings,
// Home layout), which a cross-origin web app saves with both. X-Fetch-Client
// is the website's CSRF header and X-Fetch-Session the non-secret session id
// its service worker partitions the offline cache by (lib/session-cookie.ts).
const CORS_METHODS = "GET,POST,PUT,DELETE,PATCH,OPTIONS";
const CORS_HEADERS =
  "Authorization,Content-Type,If-Match,X-Profile-Id,X-Profile-Token,X-Fetch-Client,X-Fetch-Session";

// Every route is offered credentials now: the website's session is a cookie
// (lib/session-cookie.ts), and starting Trakt sign-in also sets one naming the
// browser that started it (`routes/trakt.ts`), which the browser only keeps
// from a credentialed request.
export const isAllowedOrigin = (origin: string | undefined): boolean => {
  if (IS_DEVELOPMENT) return true;
  if (!origin) return false;
  return ALLOWED_ORIGINS.includes(origin);
};

export const applyCorsHeaders = (request: FastifyRequest, reply: FastifyReply) => {
  const origin = request.headers.origin;

  if (IS_DEVELOPMENT) {
    reply.header("Access-Control-Allow-Origin", origin ?? "*");
    // Credentials only ever go with an echoed origin — browsers refuse them
    // alongside `*`.
    if (origin) reply.header("Access-Control-Allow-Credentials", "true");
    reply.header("Access-Control-Allow-Methods", CORS_METHODS);
    reply.header("Access-Control-Allow-Headers", CORS_HEADERS);
    appendVary(reply, "Origin");
    return;
  }

  if (!origin || !ALLOWED_ORIGINS.includes(origin)) return;

  reply.header("Access-Control-Allow-Origin", origin);
  // The website signs in with an HttpOnly cookie, which a cross-origin fetch
  // only sends (and may only read the answer to) when this is present. Safe
  // because the origin above is an explicit allowlist entry, never `*`.
  reply.header("Access-Control-Allow-Credentials", "true");
  reply.header("Access-Control-Allow-Methods", CORS_METHODS);
  reply.header("Access-Control-Allow-Headers", CORS_HEADERS);
  // Appended rather than set: the HTTP-caching hook writes `Vary` too, later
  // in the lifecycle, and a plain `reply.header` from either side drops the
  // other's fields.
  appendVary(reply, "Origin");
};
