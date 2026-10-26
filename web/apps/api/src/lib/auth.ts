import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import { DEVICE_TOKEN_PREFIX, findDeviceByToken } from "./device-token.js";

declare module "fastify" {
  interface FastifyRequest {
    /** Set when the request authenticated with a paired device's token rather than `API_TOKEN`. */
    deviceId?: string;
  }
}

const API_TOKEN = process.env.API_TOKEN;

const toSha256Digest = (value: string) => createHash("sha256").update(value).digest();

// WWW-Authenticate (RFC 6750) marks this as a bearer-token failure specifically,
// distinct from route-level 401s (e.g. an incorrect profile PIN) — the web client
// uses it to tell "your API token is invalid, re-enter it" apart from other 401s,
// and a paired TV uses it to tell "this device was signed out" apart from them.
const unauthorized = (reply: FastifyReply) =>
  reply.code(401).header("WWW-Authenticate", "Bearer").send({ error: "Unauthorized" });

export const verifyToken = async (request: FastifyRequest, reply: FastifyReply) => {
  if (!API_TOKEN) {
    request.log.error("API_TOKEN is not configured");
    return reply.code(500).send({ error: "API token is not configured" });
  }

  const authHeader = request.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) {
    return unauthorized(reply);
  }

  const token = authHeader.slice("Bearer ".length).trim();
  const tokenDigest = toSha256Digest(token);
  const expectedTokenDigest = toSha256Digest(API_TOKEN);

  if (timingSafeEqual(tokenDigest, expectedTokenDigest)) return;

  // A paired device's own token is accepted wherever API_TOKEN is. The lookup is
  // by hash, so the comparison is the database's equality on a digest rather
  // than on the secret itself.
  if (token.startsWith(DEVICE_TOKEN_PREFIX)) {
    const device = await findDeviceByToken(token);
    if (device) {
      request.deviceId = device.id;
      return;
    }
  }

  return unauthorized(reply);
};

/**
 * Turns away a request made with a device token. Pairing and revoking devices
 * stay with whoever holds `API_TOKEN` (the website): a device that could pair
 * others could outlive its own revocation by minting a replacement first.
 */
export const requireOwnerToken = async (request: FastifyRequest, reply: FastifyReply) => {
  if (request.deviceId) {
    return reply.code(403).send({ error: "Devices can't manage other devices", code: "owner_token_required" });
  }
};
