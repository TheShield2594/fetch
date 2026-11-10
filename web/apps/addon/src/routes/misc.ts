import type { FastifyInstance } from "fastify";
import { ADDON_PUBLIC_BASE, addonSettingsUrl } from "../lib/config.js";
import type { AddonRouter } from "../lib/router.js";

/** Liveness. Not profile-scoped: it says nothing about anyone's data. */
export const registerHealthRoute = (app: FastifyInstance): void => {
  app.get("/health", async () => ({ status: "ok", service: "addon", publicBase: ADDON_PUBLIC_BASE ?? null }));
};

/**
 * Where Stremio's "Configure" button goes. Only offered in the manifest when
 * the deployment has told this service where the web UI lives, so the fallback
 * here is for a client that kept an older manifest.
 */
export const registerConfigureRoute = (routes: AddonRouter): void => {
  routes.get("/configure", async (_request, reply) => {
    const settingsUrl = addonSettingsUrl();
    if (settingsUrl) return reply.redirect(settingsUrl);
    return reply.code(200).send({ message: "Configure Fetch through the web UI." });
  });
};
