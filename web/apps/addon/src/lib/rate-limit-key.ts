import type { FastifyRequest } from "fastify";
import { matchesStremioProfileKey, UUID_V4_PATTERN } from "@fetch/shared";
import { FETCH_API_TOKEN } from "./config.js";
import { knownStremioKeyGeneration } from "./profile.js";

/**
 * The rate-limit bucket a request counts against.
 *
 * A signed `/u/<profileId>/<key>/…` request counts against its profile: the
 * key can only have come from Settings, so the bucket can't be multiplied by
 * inventing ids, and each installation in a household gets its own budget
 * however many of them share an address. Everything else (the keyless forms,
 * a wrong key, `/health`) counts against the client address, as before.
 *
 * This runs before the route, so it can't ask the API which key generation is
 * current; it uses the last one confirmed, or the first generation for a
 * profile not seen yet. Either way the key is one Settings handed out, so it
 * still can't be invented; the route is what turns a replaced key away.
 */
export const rateLimitKey = (request: FastifyRequest): string => {
  const params = request.params as Record<string, unknown> | null | undefined;
  const profileId = params?.profileId;
  const profileKey = params?.profileKey;
  if (
    typeof profileId === "string" &&
    typeof profileKey === "string" &&
    UUID_V4_PATTERN.test(profileId)
  ) {
    const generation = knownStremioKeyGeneration(profileId) ?? 0;
    if (matchesStremioProfileKey(profileKey, FETCH_API_TOKEN, profileId, generation)) {
      return `profile:${profileId}`;
    }
  }
  return `ip:${request.ip}`;
};
