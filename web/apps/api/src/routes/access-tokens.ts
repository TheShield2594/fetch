import type { FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import {
  createAccessToken,
  listAccessTokens,
  MAX_ACCESS_TOKEN_DAYS,
  MAX_ACCESS_TOKEN_NAME_LENGTH,
  MAX_ACCESS_TOKENS_PER_ACCOUNT,
  revokeAccessToken,
} from "../lib/access-tokens.js";
import { recordAccountEvent } from "../lib/account-events.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

// An account's personal access tokens (lib/access-tokens.ts). Managed from a
// signed-in browser only: a token that could mint tokens could outlive its own
// revocation, and `API_TOKEN` has no account to make one for.

const signedInAccount = (request: FastifyRequest, reply: FastifyReply): string | null => {
  if (request.auth?.kind === "session") return request.auth.accountId;
  void reply.code(400).send({ error: "Sign in to the website to manage access tokens" });
  return null;
};

const accessTokenRoutes: FastifyPluginAsync = async (app) => {
  app.get("/auth/tokens", async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;
    return { tokens: await listAccessTokens(accountId) };
  });

  // The token is in this response and nowhere else afterwards.
  app.post("/auth/tokens", { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } }, async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;

    const body = (request.body ?? {}) as { name?: unknown; readOnly?: unknown; expiresInDays?: unknown };
    const name = typeof body.name === "string" ? body.name.trim() : "";
    if (!name || name.length > MAX_ACCESS_TOKEN_NAME_LENGTH) {
      return reply.code(400).send({ error: `name is required (at most ${MAX_ACCESS_TOKEN_NAME_LENGTH} characters)` });
    }
    if (body.readOnly !== undefined && typeof body.readOnly !== "boolean") {
      return reply.code(400).send({ error: "readOnly must be true or false" });
    }
    const days = body.expiresInDays ?? null;
    if (days !== null && (typeof days !== "number" || !Number.isInteger(days) || days < 1 || days > MAX_ACCESS_TOKEN_DAYS)) {
      return reply.code(400).send({ error: `expiresInDays must be a whole number from 1 to ${MAX_ACCESS_TOKEN_DAYS}` });
    }

    const result = await createAccessToken(accountId, { name, readOnly: body.readOnly === true, expiresInDays: days });
    if (!result.ok) {
      return reply.code(409).send({
        error: `An account can have at most ${MAX_ACCESS_TOKENS_PER_ACCOUNT} access tokens. Revoke one first.`,
        code: "access_token_limit",
      });
    }
    await recordAccountEvent(accountId, "access_token_created", { request, detail: name });
    reply.header("Cache-Control", "no-store");
    return reply.code(201).send({ token: result.token, accessToken: result.accessToken });
  });

  app.delete<{ Params: { id: string } }>("/auth/tokens/:id", async (request, reply) => {
    const accountId = signedInAccount(request, reply);
    if (!accountId) return reply;
    if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });

    const revoked = await revokeAccessToken(accountId, request.params.id);
    if (!revoked) return reply.code(404).send({ error: "Access token not found" });
    await recordAccountEvent(accountId, "access_token_revoked", { request, detail: revoked.name });
    return reply.code(204).send();
  });
};

export default accessTokenRoutes;
