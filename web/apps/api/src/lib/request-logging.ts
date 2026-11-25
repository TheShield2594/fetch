import type { FastifyInstance } from "fastify";
import { redactUrl } from "@fetch/shared";

const LOG_LEVELS = ["fatal", "error", "warn", "info", "debug", "trace", "silent"] as const;
export type LogLevel = (typeof LOG_LEVELS)[number];

/**
 * `LOG_LEVEL` as a level the logger accepts. Unset, empty or misspelt all mean
 * `info`: a typo must not stop the API starting, and must not silence it.
 */
export const parseLogLevel = (raw: string | undefined): LogLevel => {
  const value = raw?.trim().toLowerCase();
  return (LOG_LEVELS as readonly string[]).includes(value ?? "") ? (value as LogLevel) : "info";
};

/** A request slower than this is worth a line at `info` even when it succeeded. */
export const SLOW_REQUEST_MS = 1000;

/**
 * The level one finished request is logged at, or null for none at all.
 *
 * Fastify's own request logging writes two `info` lines per request. The
 * compose healthcheck alone is a request every ten seconds — some 17,000 lines
 * a day — and every TV sync and Stremio catalog adds its pair, so the lines
 * worth reading were rotated out of a capped log within hours. A request that
 * went fine is now `debug`; what stays at the default level is the request that
 * failed or took long enough to be noticed.
 */
export const requestLogLevel = (url: string, statusCode: number, elapsedMs: number): LogLevel | null => {
  const path = url.split("?", 1)[0] ?? url;
  if (path === "/health" || path.startsWith("/health/")) return null;
  if (statusCode >= 500) return "error";
  if (statusCode >= 400) return "warn";
  if (elapsedMs >= SLOW_REQUEST_MS) return "info";
  return "debug";
};

/** Replaces Fastify's request logging; pair with `disableRequestLogging: true`. */
export const registerRequestLogging = (app: FastifyInstance): void => {
  app.addHook("onResponse", async (request, reply) => {
    const responseTime = reply.elapsedTime;
    const level = requestLogLevel(request.url, reply.statusCode, responseTime);
    if (!level || level === "silent") return;
    request.log[level](
      {
        method: request.method,
        // Masked for the same reason the `req` serializer is: a webhook's
        // `?token=` would otherwise be written out here.
        url: redactUrl(request.url),
        statusCode: reply.statusCode,
        responseTime: Math.round(responseTime),
      },
      responseTime >= SLOW_REQUEST_MS ? "slow request" : "request completed"
    );
  });
};
