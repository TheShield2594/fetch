import type { FastifyRequest } from "fastify";
import { prisma } from "./prisma.js";
import { getDefaultProfileId } from "./profile.js";
import { UUID_V4_PATTERN } from "./types.js";

export type WebhookProfileResolution =
  | { ok: true; profileId: string }
  | { ok: false; status: number; error: string };

/**
 * Picks the profile a Plex/Jellyfin scrobble belongs to.
 *
 * Media servers don't send `x-profile-id`, so `resolveProfile` can't be used
 * here. Resolution runs in three steps, most explicit first:
 *
 * 1. `?profile=<uuid>` on the webhook URL. Plex and Jellyfin both let you
 *    configure the target URL per user, so a household can give each member a
 *    URL that lands in their own profile. This is the only unambiguous option
 *    and therefore wins outright — a bad ID is an error, not a silent fallback.
 * 2. The media-server account name, matched case-insensitively against profile
 *    names. Zero configuration for the common case where the Plex user "Sam"
 *    has a Fetch profile called "Sam".
 * 3. The account's default (oldest) profile — the single-profile install,
 *    which is what every webhook did implicitly before this existed.
 *
 * Every step is limited to `accountId`'s profiles: the account whose webhook
 * key was used, or the admin's for `WEBHOOK_SECRET`. A profile ID from another
 * account is "not found", and a Plex user called "Sam" matches only a "Sam" in
 * the same account. Null (no accounts exist yet) searches every profile.
 *
 * Within the account, a webhook can write to any profile, including a
 * PIN-protected one. That's deliberate: the PIN gates the *browser* UI, and the
 * media server reporting a play has no way to complete a PIN challenge.
 */
export const resolveWebhookProfile = async (
  request: FastifyRequest,
  accountName: string | null | undefined,
  accountId: string | null
): Promise<WebhookProfileResolution> => {
  const inAccount = accountId ? { accountId } : {};
  const query = (request.query ?? {}) as Record<string, unknown>;
  const rawProfileId = typeof query.profile === "string" ? query.profile.trim() : "";

  if (rawProfileId) {
    if (!UUID_V4_PATTERN.test(rawProfileId)) {
      return { ok: false, status: 400, error: "The profile query parameter must be a valid UUID" };
    }
    const profile = await prisma.profile.findFirst({ where: { id: rawProfileId, ...inAccount }, select: { id: true } });
    if (!profile) {
      return { ok: false, status: 404, error: "Profile not found" };
    }
    return { ok: true, profileId: profile.id };
  }

  const name = accountName?.trim();
  if (name) {
    const matched = await prisma.profile.findFirst({
      where: { name: { equals: name, mode: "insensitive" }, ...inAccount },
      select: { id: true },
      orderBy: { createdAt: "asc" },
    });
    if (matched) {
      return { ok: true, profileId: matched.id };
    }
    request.log.info(
      { accountName: name },
      "Webhook account has no matching profile name; falling back to the default profile"
    );
  }

  return { ok: true, profileId: await getDefaultProfileId(accountId) };
};
