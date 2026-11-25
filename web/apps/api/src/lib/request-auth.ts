import { createHash } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";

/**
 * Who a request is acting as.
 *
 * - `token`: the bearer was `API_TOKEN`. That is the operator: scripts, the
 *   first-run setup, recovery, and an add-on service still configured the old
 *   way. It can see every account's profiles, as the whole install could
 *   before accounts existed.
 * - `addon`: the add-on service with its own key (`FETCH_ADDON_KEY`), sending
 *   the service token and no bearer. It acts for whichever profile its install
 *   URL names, like `token`, but only on the routes in `lib/addon-scope.ts`,
 *   and is never the admin (#637).
 * - `session`: someone signed in with a username and password. They see only
 *   their own account's profiles.
 * - `device`: a paired TV, with its own revocable token. It sees the profiles
 *   of the account that paired it, and is never the admin.
 * - `pat`: a personal access token the account made for a script or an
 *   integration (lib/access-tokens.ts). It sees its account's profiles, is
 *   never the admin, cannot manage the account itself (password, sign-ins,
 *   TVs, other tokens), and when `readOnly` may only make `GET` requests.
 *
 * Set by `verifyToken` (lib/auth.ts). Kept apart from lib/sessions.ts, which
 * needs the database, so a route can check who is asking without importing it.
 */
export type AuthPrincipal =
  | { kind: "token" }
  | { kind: "addon" }
  | {
      kind: "session";
      sessionId: string;
      accountId: string;
      isAdmin: boolean;
      /** Signed in with a password someone else set; see `PASSWORD_CHANGE_ROUTES`. */
      mustChangePassword?: boolean;
    }
  | { kind: "device"; deviceId: string; accountId: string | null }
  | { kind: "pat"; tokenId: string; accountId: string; readOnly: boolean };

declare module "fastify" {
  interface FastifyRequest {
    auth?: AuthPrincipal;
  }
}

/**
 * Session tokens carry a prefix so `verifyToken` can tell one from a wrong
 * `API_TOKEN` without a database lookup, and so one is recognisable if it
 * ever turns up in a log.
 */
export const SESSION_TOKEN_PREFIX = "fs1_";

/** Personal access tokens, told apart the same way. */
export const ACCESS_TOKEN_PREFIX = "fpat_";

/** Session tokens and invite codes are only ever stored as this digest. */
export const hashSecret = (value: string): string => createHash("sha256").update(value).digest("hex");

/**
 * The account a request is limited to, or null when it may act on every
 * account (`API_TOKEN`, the add-on service on its few routes, or a route the
 * auth gate leaves public).
 */
export const accountScope = (request: FastifyRequest): string | null =>
  request.auth?.kind === "session" || request.auth?.kind === "device" || request.auth?.kind === "pat"
    ? request.auth.accountId
    : null;

export const isAdminRequest = (request: FastifyRequest): boolean =>
  request.auth?.kind === "token" || (request.auth?.kind === "session" && request.auth.isAdmin);

export const ADMIN_REQUIRED_RESPONSE = {
  error: "Only the admin account can change this",
  code: "admin_required",
} as const;

/**
 * For settings that belong to the whole install rather than to one account:
 * the API keys, the Trakt and Stremio connections, the AI provider. Every
 * signed-in account shares them, so only the admin may change them.
 */
export const requireAdmin = async (request: FastifyRequest, reply: FastifyReply) => {
  if (!isAdminRequest(request)) return reply.code(403).send(ADMIN_REQUIRED_RESPONSE);
};
