import * as Sentry from "@sentry/node";
import type { Breadcrumb, Event } from "@sentry/node";
import { isSensitiveQueryParam, redactQueryString, redactUrl } from "./redact-url.js";

export { Sentry };

// Request headers that carry a credential. Dropped, not masked: none of them
// says anything worth reading in an error report.
const SENSITIVE_HEADERS = new Set([
  "authorization",
  "proxy-authorization",
  "cookie",
  "set-cookie",
  "x-api-key",
  "x-webhook-secret",
  "x-fetch-session",
  "x-fetch-service-token",
  "x-fetch-stremio-profile-key",
]);

const REDACTED = "REDACTED";

type QueryString = NonNullable<NonNullable<Event["request"]>["query_string"]>;

const scrubQueryString = (query: QueryString): QueryString => {
  if (typeof query === "string") return redactQueryString(query.replace(/^\?/, ""));
  if (Array.isArray(query)) {
    return query.map(([key, value]) => [key, isSensitiveQueryParam(key) ? REDACTED : value] as [string, string]);
  }
  return Object.fromEntries(
    Object.entries(query).map(([key, value]) => [key, isSensitiveQueryParam(key) ? REDACTED : value]),
  );
};

/**
 * Strips the credentials a request can carry from a Sentry event: the secret
 * path segments and query parameters `redactUrl` masks in the logs, and the
 * auth and cookie headers (#636). Exported for tests; `initNodeSentry` wires it
 * in as both `beforeSend` and `beforeSendTransaction`.
 */
export const scrubSentryEvent = <T extends Event>(event: T): T => {
  const request = event.request;
  if (request) {
    if (typeof request.url === "string") request.url = redactUrl(request.url);
    if (request.query_string !== undefined) request.query_string = scrubQueryString(request.query_string);
    if (request.headers) {
      request.headers = Object.fromEntries(
        Object.entries(request.headers).filter(([name]) => !SENSITIVE_HEADERS.has(name.toLowerCase())),
      );
    }
    delete request.cookies;
  }
  // An unmatched route is named after its raw URL.
  if (typeof event.transaction === "string") event.transaction = redactUrl(event.transaction);
  if (event.breadcrumbs) event.breadcrumbs = event.breadcrumbs.map(scrubSentryBreadcrumb);
  return event;
};

/** Masks the URL an HTTP breadcrumb records (an outgoing fetch, or an incoming request). */
export const scrubSentryBreadcrumb = (breadcrumb: Breadcrumb): Breadcrumb => {
  const url: unknown = breadcrumb.data?.["url"];
  if (typeof url === "string" && breadcrumb.data) {
    breadcrumb.data = { ...breadcrumb.data, url: redactUrl(url) };
  }
  if (typeof breadcrumb.message === "string") breadcrumb.message = redactUrl(breadcrumb.message);
  return breadcrumb;
};

// Shared bootstrap for the api and addon services. Opt-in via SENTRY_DSN;
// error tracking only — no performance tracing — to keep this a
// lightweight, privacy-conscious opt-in for self-hosted deployments.
export const initNodeSentry = (service: string): boolean => {
  const dsn = process.env.SENTRY_DSN?.trim();
  if (!dsn) return false;

  Sentry.init({
    dsn,
    environment: process.env.NODE_ENV ?? "development",
    initialScope: { tags: { service } },
    tracesSampleRate: 0,
    beforeSend: (event) => scrubSentryEvent(event),
    beforeSendTransaction: (event) => scrubSentryEvent(event),
    beforeBreadcrumb: (breadcrumb) => scrubSentryBreadcrumb(breadcrumb),
  });
  return true;
};
