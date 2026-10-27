import type { FastifyPluginAsync, FastifyReply } from "fastify";
import { resolveProfile } from "../lib/profile.js";
import { updateConnectionDetails } from "../lib/connections.js";
import {
  getPlexLinkPending,
  getProfileMediaServer,
  listProfileMediaServers,
  MEDIA_SERVER_PROVIDERS,
  MediaServerError,
  type MediaServerDetails,
  pollPlexLink,
  removeMediaServer,
  signInMediaBrowser,
  startPlexLink,
  syncMediaServer,
} from "../lib/media-servers.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

// A profile's Plex, Jellyfin and Emby sign-ins, as the TV app keeps them. Every
// route acts on the requesting profile's. Plex signs in with a link code
// entered at plex.tv/link; Jellyfin and Emby with the server's address and a
// user name and password, which is sent to the server once and not stored.

// Sign-ins take a password or ask plex.tv for a code: bounded like the other
// credential routes.
const SIGN_IN_RATE_LIMIT = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };
const POLL_RATE_LIMIT = { config: { rateLimit: { max: 60, timeWindow: "1 minute" } } };

const publicServer = (connection: { id: string; provider: string; details: MediaServerDetails; createdAt: Date }) => ({
  id: connection.id,
  provider: connection.provider,
  serverName: connection.details.serverName,
  address: connection.details.address,
  userName: connection.details.userName,
  importWatchState: connection.details.importWatchState,
  lastSyncedAt: connection.details.lastSyncedAt ?? null,
  connectedAt: connection.createdAt.toISOString(),
});

const sendFailure = (reply: FastifyReply, error: unknown, fallback: string) => {
  if (error instanceof MediaServerError) {
    const status = error.kind === "auth" ? 401 : error.kind === "unsupported" || error.kind === "blocked" ? 422 : 502;
    return reply.code(status).send({ error: error.message, code: `server_${error.kind}` });
  }
  return reply.code(502).send({ error: fallback });
};

const mediaServerRoutes: FastifyPluginAsync = async (app) => {
  app.addHook("preHandler", resolveProfile);

  app.get("/media-servers", async (request) => {
    const [servers, plexLink] = await Promise.all([
      listProfileMediaServers(request.profileId!),
      getPlexLinkPending(request.profileId!),
    ]);
    return { providers: MEDIA_SERVER_PROVIDERS, servers: servers.map(publicServer), plexLink };
  });

  app.post("/media-servers/plex/link", SIGN_IN_RATE_LIMIT, async (request, reply) => {
    try {
      return await startPlexLink(request.profileId!);
    } catch (error) {
      request.log.warn({ err: error }, "Plex sign-in could not start");
      return sendFailure(reply, error, "Could not reach plex.tv. Try again in a moment.");
    }
  });

  app.post("/media-servers/plex/link/poll", POLL_RATE_LIMIT, async (request, reply) => {
    try {
      const result = await pollPlexLink(request.profileId!);
      const servers = await listProfileMediaServers(request.profileId!);
      return { ...result, servers: servers.map(publicServer) };
    } catch (error) {
      request.log.warn({ err: error }, "Plex sign-in check failed");
      return sendFailure(reply, error, "Could not reach plex.tv. Try again in a moment.");
    }
  });

  app.post<{ Params: { provider: string }; Body: unknown }>(
    "/media-servers/:provider/sign-in",
    SIGN_IN_RATE_LIMIT,
    async (request, reply) => {
      const provider = request.params.provider;
      if (provider !== "jellyfin" && provider !== "emby") {
        return reply.code(404).send({ error: "Sign in to Plex with a link code" });
      }
      const body = (request.body ?? {}) as { address?: unknown; username?: unknown; password?: unknown };
      const address = typeof body.address === "string" ? body.address.trim() : "";
      const username = typeof body.username === "string" ? body.username.trim() : "";
      const password = typeof body.password === "string" ? body.password : "";
      if (!address || !username) return reply.code(400).send({ error: "address and username are required" });
      try {
        const details = await signInMediaBrowser(request.profileId!, provider, { address, username, password });
        return reply.code(201).send({ serverName: details.serverName, userName: details.userName });
      } catch (error) {
        request.log.warn({ err: error, provider }, "Media server sign-in failed");
        return sendFailure(reply, error, "Could not sign in to the server");
      }
    }
  );

  app.patch<{ Params: { id: string }; Body: unknown }>("/media-servers/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    const connection = await getProfileMediaServer(request.profileId!, request.params.id);
    if (!connection) return reply.code(404).send({ error: "Server not found" });
    const { importWatchState } = (request.body ?? {}) as { importWatchState?: unknown };
    if (typeof importWatchState !== "boolean") return reply.code(400).send({ error: "importWatchState must be a boolean" });
    const details = { ...connection.details, importWatchState };
    await updateConnectionDetails(connection.id, details);
    return publicServer({ ...connection, details });
  });

  app.post<{ Params: { id: string } }>("/media-servers/:id/sync", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    const connection = await getProfileMediaServer(request.profileId!, request.params.id);
    if (!connection) return reply.code(404).send({ error: "Server not found" });
    try {
      return await syncMediaServer(connection, request.log);
    } catch (error) {
      request.log.error({ err: error, provider: connection.provider }, "Media server sync failed");
      return sendFailure(reply, error, "Media server sync failed");
    }
  });

  app.delete<{ Params: { id: string } }>("/media-servers/:id", async (request, reply) => {
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
    const connection = await getProfileMediaServer(request.profileId!, request.params.id);
    if (!connection) return reply.code(404).send({ error: "Server not found" });
    await removeMediaServer(connection);
    return reply.code(204).send();
  });
};

export default mediaServerRoutes;
