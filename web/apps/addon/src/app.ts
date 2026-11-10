import Fastify, { type RawRequestDefaultExpression } from "fastify";
import rateLimit from "@fastify/rate-limit";
import { normalizeProxyPath, redactedRequestSerializer } from "@fetch/shared";
import { Sentry } from "./lib/sentry.js";
import { LOG_LEVEL, PROXY_PATH_PREFIXES, trustProxy } from "./lib/config.js";
import { addonRouter } from "./lib/router.js";
import { rateLimitKey } from "./lib/rate-limit-key.js";
import { registerCatalogRoutes } from "./routes/catalog.js";
import { registerManifestRoute } from "./routes/manifest.js";
import { registerMetaRoute } from "./routes/meta.js";
import { registerConfigureRoute, registerHealthRoute } from "./routes/misc.js";
import { registerScrobbleRoute } from "./routes/scrobble.js";

/**
 * The Stremio addon service: the server itself, and the routes it carries.
 *
 * This file was 1,283 lines — routing, caching, an HTTP client and auth in
 * one scroll, with a single lib file beside it. It is now the assembly,
 * mirroring how `apps/api` is laid out:
 * `lib/` for everything that is a decision (who this request is acting as,
 * what may write, what is worth caching and for how long), `routes/` for the
 * endpoints Stremio actually calls.
 */

export const app = Fastify({
  logger: {
    level: LOG_LEVEL,
    // Fastify's default `req` serializer writes the URL to the log verbatim,
    // query string and all, and `docker-compose.yml` keeps 30 MB of those logs
    // per service on the host, where a log shipper or a support bundle pasted
    // into an issue can carry a credential off with them.
    serializers: { req: redactedRequestSerializer },
  },
  ...(trustProxy !== undefined ? { trustProxy } : {}),
  rewriteUrl: (request: RawRequestDefaultExpression) => normalizeProxyPath(request.url ?? "/", PROXY_PATH_PREFIXES)
});

// ─── Rate limiting ───
// All routes here are unauthenticated (Stremio doesn't send credentials), so apply a global limit.
//
// One Stremio home screen asks for every advertised catalog at once (two per
// list plus each discovery row), and a search asks again for each list
// catalog, so the budget covers a few home loads and searches a minute per
// installation. A signed install URL gets its own bucket (see `rateLimitKey`),
// so a household behind one address, or every user behind a reverse proxy
// without `TRUST_PROXY`, doesn't share a single one.

app.register(rateLimit, {
  global: true,
  max: 300,
  timeWindow: "1 minute",
  keyGenerator: rateLimitKey,
});

// ─── Error tracking (opt-in via SENTRY_DSN) ───

Sentry.setupFastifyErrorHandler(app);

// ─── Routes ───
//
// Everything Stremio calls is registered through `addonRouter`, which serves it
// both bare and under the per-profile prefix — see `lib/profile.ts`. `/health`
// is the exception: it is this container's own, not part of the addon protocol.

//
// They go in a plugin rather than straight onto `app`: `@fastify/rate-limit`
// attaches itself to routes through an `onRoute` hook that only exists once the
// plugin has loaded, which happens after this file has run. Routes declared on
// the root here were registered before that and were never limited at all.
app.register(async (scope) => {
  const routes = addonRouter(scope);

  registerHealthRoute(scope);
  registerManifestRoute(routes);
  registerCatalogRoutes(routes);
  registerMetaRoute(routes);
  registerScrobbleRoute(routes);
  registerConfigureRoute(routes);
});
