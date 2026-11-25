import type { FastifyInstance } from "fastify";
import { APP_VERSION_HEADER, MIN_TV_APP, SERVER_VERSION, isTvAppTooOld } from "./version.js";

/**
 * Turns away a TV app older than `MIN_TV_APP` with one clear answer (#639).
 *
 * Only requests that say which build they come from are checked: the website
 * and scripts send no `X-Fetch-App-Version`, and an older TV that doesn't send
 * it either is let through as before. A TV that does send it and is too old
 * gets `426 Upgrade Required` with `code: "tv_app_too_old"`, which it turns
 * into "Update Fetch on this TV" rather than whatever a changed route would
 * have broken.
 *
 * Exempt: the probes and `/version` (the TV asks these to find out *why*), and
 * the TV's sync report, so the website's TVs page still shows which build is
 * out of date.
 *
 * Every response also says which server answered, in `X-Fetch-Server-Version`.
 */
export const TV_APP_TOO_OLD = "tv_app_too_old";

const EXEMPT_PATHS = new Set(["/health", "/health/ready", "/version", "/devices/me/report"]);

export const registerAppVersionGate = (app: FastifyInstance): void => {
  app.addHook("onRequest", async (request, reply) => {
    reply.header("X-Fetch-Server-Version", SERVER_VERSION);

    const raw = request.headers[APP_VERSION_HEADER];
    const appVersion = Array.isArray(raw) ? raw[0] : raw;
    if (!appVersion) return;

    const path = request.url.split("?", 1)[0] ?? request.url;
    if (EXEMPT_PATHS.has(path)) return;

    if (isTvAppTooOld(appVersion)) {
      return reply.code(426).send({
        error: `This server needs Fetch ${MIN_TV_APP} or newer on the TV; this TV has ${appVersion}. Update the app on the TV.`,
        code: TV_APP_TOO_OLD,
        minTvApp: MIN_TV_APP,
        server: SERVER_VERSION,
      });
    }
  });
};
