import Fastify, { type RawRequestDefaultExpression } from "fastify";
import rateLimit from "@fastify/rate-limit";
import { normalizeProxyPath, redactedRequestSerializer } from "@fetch/shared";
import { Sentry } from "./lib/sentry.js";
import {
  ADDON_CREDENTIAL_SOURCE,
  HOLDS_API_TOKEN,
  LOG_LEVEL,
  PROXY_PATH_PREFIXES,
  trustProxy,
} from "./lib/config.js";
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

// ─── Credentials ───
//
// This service is the one that may face the internet, so it should hold only
// `FETCH_ADDON_KEY` — never `API_TOKEN`, which would hand whoever compromised it
// every account and the key to every stored secret (#637). An install still
// configured the old way keeps working, and is told how to stop.

const ADDON_KEY_HOWTO =
  "Run `docker compose exec api fetch addon-key`, put the result in .env as FETCH_ADDON_KEY, " +
  "and pass FETCH_ADDON_KEY (not API_TOKEN) to the addon service — see README.md, \"The add-on's key\".";

if (ADDON_CREDENTIAL_SOURCE.kind === "api-token") {
  app.log.warn(
    `FETCH_API_TOKEN is deprecated for the addon service: it gives this container the server's root token. ${ADDON_KEY_HOWTO}`
  );
} else if (ADDON_CREDENTIAL_SOURCE.kind === "addon-key" && HOLDS_API_TOKEN) {
  app.log.warn("FETCH_API_TOKEN is set but unused, since FETCH_ADDON_KEY is set. Remove it from the addon service's environment.");
} else if (ADDON_CREDENTIAL_SOURCE.kind === "invalid") {
  app.log.error(`FETCH_ADDON_KEY is not a valid add-on key (it starts with "fak1_"). ${ADDON_KEY_HOWTO}`);
} else if (ADDON_CREDENTIAL_SOURCE.kind === "none") {
  app.log.error(`No credential for the Fetch API. ${ADDON_KEY_HOWTO}`);
}

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
