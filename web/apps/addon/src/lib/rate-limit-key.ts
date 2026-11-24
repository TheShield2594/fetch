import type { FastifyRequest } from "fastify";
import { UUID_V4_PATTERN } from "@fetch/shared";
import { isConfirmedStremioKey } from "./profile.js";

/**
 * The rate-limit bucket a request counts against.
 *
 * A signed `/u/<profileId>/<key>/…` request counts against its profile: the
 * key can only have come from Settings, so the bucket can't be multiplied by
 * inventing ids, and each installation in a household gets its own budget
 * however many of them share an address. Everything else (the keyless forms,
 * a wrong key, `/health`) counts against the client address, as before.
 *
 * This runs before the route, so it can't ask the API whether the key is
 * current; it uses the last key the API confirmed for the profile. That key is
 * one Settings handed out, so it can't be invented; the route is what turns a
 * replaced key away. An install's first request after this service starts
 * counts against the address, until its key has been confirmed once.
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
    if (isConfirmedStremioKey(profileId, profileKey)) return `profile:${profileId}`;
  }
  return `ip:${request.ip}`;
};
