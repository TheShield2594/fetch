import type { FastifyRequest } from "fastify";

/**
 * Every route the add-on service may call with its own credential, as
 * `METHOD /route/pattern` (Fastify's pattern, not the concrete URL).
 *
 * The add-on is the one service the README lets face the internet, so it does
 * not hold `API_TOKEN` (#637): that token is the operator's credential, which
 * sees and changes every account, and the root every stored secret is
 * encrypted under. It holds the service token instead, and a request carrying
 * that token and no other credential is the add-on principal, which the auth
 * gate lets through to these routes and turns away from everything else —
 * account and profile management, settings writes, imports and exports, the
 * admin's routes.
 *
 * All of them are reads except the three scrobble routes, which are how a
 * media player's playback reaches the profile its add-on URL belongs to.
 *
 * Adding a route here widens what a compromised add-on can do. Keep it to what
 * `apps/addon` actually calls (`apps/addon/src/lib/fetch-data.ts`, `routes/`,
 * `lib/profile.ts`, and `discoveryApiPath` in `@fetch/shared`).
 */
export const ADDON_SERVICE_ROUTES: ReadonlySet<string> = new Set([
  // Who the request is for: the oldest profile for a keyless URL, and whether
  // a signed URL's key is current.
  "GET /profiles",
  "GET /profiles/:id/addon-url/verify",
  // The manifest.
  "GET /lists",
  "GET /genres",
  "GET /addon/config",
  // Catalog rows.
  "GET /lists/:listId/items",
  "GET /trending",
  "GET /popular",
  "GET /anime",
  "GET /streaming",
  "GET /recommendations/personal",
  "GET /recommendations/ai",
  // A title's page.
  "GET /meta/:type/:imdbId",
  "GET /meta/series/:imdbId/seasons",
  "GET /meta/series/:imdbId/season/:seasonNumber/episodes",
  "GET /series/progress/:imdbId",
  "GET /settings/preferences",
  "GET /rpdb/config",
  // Playback from a media player, through the add-on's scrobble route.
  "POST /scrobble/start",
  "POST /scrobble/pause",
  "POST /scrobble/stop",
]);

/** Whether the route this request matched is one the add-on principal may use. */
export const isAddonServiceRoute = (request: FastifyRequest): boolean => {
  const pattern = request.routeOptions.url;
  if (!pattern) return false;
  return ADDON_SERVICE_ROUTES.has(`${request.method} ${pattern}`);
};

export const ADDON_SCOPE_RESPONSE = {
  error: "The add-on service's key can't be used here",
  code: "addon_scope",
} as const;
