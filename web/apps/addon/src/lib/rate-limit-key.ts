import type { FastifyRequest } from "fastify";
import { matchesStremioProfileKey, UUID_V4_PATTERN } from "@fetch/shared";
import { FETCH_API_TOKEN } from "./config.js";

/**
 * The rate-limit bucket a request counts against.
 *
 * A signed `/u/<profileId>/<key>/…` request counts against its profile: the
 * key can only have come from Settings, so the bucket can't be multiplied by
 * inventing ids, and each installation in a household gets its own budget
 * however many of them share an address. Everything else (the keyless forms,
 * a wrong key, `/health`) counts against the client address, as before.
 */
export const rateLimitKey = (request: FastifyRequest): string => {
  const params = request.params as Record<string, unknown> | null | undefined;
  const profileId = params?.profileId;
  const profileKey = params?.profileKey;
  if (
    typeof profileId === "string" &&
    typeof profileKey === "string" &&
    UUID_V4_PATTERN.test(profileId) &&
    matchesStremioProfileKey(profileKey, FETCH_API_TOKEN, profileId)
  ) {
    return `profile:${profileId}`;
  }
  return `ip:${request.ip}`;
};
