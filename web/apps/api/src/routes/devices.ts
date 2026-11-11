import type { FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import { Prisma } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { requireOwnerToken } from "../lib/auth.js";
import { accountScope } from "../lib/request-auth.js";
import { findAdminAccountId } from "../lib/sessions.js";
import {
  formatPairingCode,
  generateDeviceToken,
  generatePairingCode,
  generatePollSecret,
  hashSecret,
  invalidateDeviceCache,
  normalizePairingCode,
  PAIRING_CODE_LENGTH,
} from "../lib/device-token.js";
import {
  findSlotProfileId,
  listDeviceProfiles,
  MAX_DEVICE_PROFILE_SLOTS,
  PRIMARY_SLOT,
  type SlottedProfile,
} from "../lib/device-slots.js";
import { parseProfileAttributes, PROFILE_VIEW_SELECT, toProfileView } from "../lib/profile-view.js";
import { hasParentalAuthority, invalidateProfileCache, loosensParentalLimits } from "../lib/profile.js";
import { UUID_V4_PATTERN } from "../lib/types.js";
import {
  claimThroughHub,
  isLoopbackAddress,
  PairingHubUnavailableError,
  parsePublicAddress,
} from "../lib/pairing-hub.js";

/** How long a code shown on the TV can be confirmed for. */
export const PAIRING_TTL_MS = 5 * 60 * 1000;
/** How often the TV is told to poll. */
const POLL_INTERVAL_SEC = 3;
/**
 * Unconfirmed pairings allowed at once. Starting one needs no credential, so
 * without a ceiling the table is something anyone on the network can fill.
 * The rows are tiny, so the global ceiling is generous; the per-address one is
 * what stops a single host from holding every slot and locking real TVs out.
 */
const MAX_PENDING_PAIRINGS = 200;
/** Unconfirmed pairings one client address may hold at once. */
const MAX_PENDING_PAIRINGS_PER_IP = 3;
const MAX_DEVICE_NAME_LENGTH = 80;
const PAIRING_SECRET_HEADER = "x-pairing-secret";

/**
 * Where the website is, so the TV's QR code can open its confirm page with the
 * code filled in. The TV talks to the API, which is usually on another port, so
 * it can't work this out itself. Unset means the TV shows the code alone.
 */
const WEB_PUBLIC = (process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.trim().replace(/\/+$/, "");

/** Where this API is, as a TV should reach it; only a fallback, since compose fills it with an example IP. */
const apiPublic = (): string | undefined => process.env.FETCH_API_PUBLIC?.trim().replace(/\/+$/, "");

export const pairingConfirmUrl = (code: string): string | null =>
  WEB_PUBLIC ? `${WEB_PUBLIC}/pair?code=${encodeURIComponent(code)}` : null;

const deviceNameFrom = (raw: unknown, fallback: string): string => {
  if (typeof raw !== "string") return fallback;
  const trimmed = raw.trim().slice(0, MAX_DEVICE_NAME_LENGTH);
  return trimmed || fallback;
};

const toDeviceView = (device: { id: string; name: string; createdAt: Date; lastSeenAt: Date | null }) => ({
  id: device.id,
  name: device.name,
  createdAt: device.createdAt.toISOString(),
  lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
});

const toSlottedView = ({ slot, profile }: SlottedProfile) => ({
  slot,
  isPrimary: slot === PRIMARY_SLOT,
  ...toProfileView(profile),
});

/** The routes under `/devices/me` are the device's own; the website has no slot numbering. */
const requireDevice = async (request: FastifyRequest, reply: FastifyReply) => {
  if (!request.deviceId) {
    return reply.code(403).send({ error: "Only a paired device can use this route", code: "device_token_required" });
  }
};

const parseSlot = (raw: string): number | null => {
  const slot = Number(raw);
  return Number.isInteger(slot) && slot >= 1 && slot <= MAX_DEVICE_PROFILE_SLOTS ? slot : null;
};

const NOT_FOUND_MESSAGE = "That code is wrong or has expired. Check the TV for a new one.";

/**
 * Confirms a code the TV got from the pairing hub (`lib/pairing-hub.ts`): claims
 * it there with this website's address, then stores a pairing that is already
 * confirmed and whose poll secret is the TV's handoff secret, so the TV's next
 * `/devices/pair/poll` here, with that secret, collects its token as usual.
 *
 * The address comes from the browser confirming, which knows where it reached
 * this API and website: `FETCH_API_PUBLIC` and `FETCH_WEB_PUBLIC` default to an
 * example IP, so they are only used when the browser's is a loopback address.
 */
const confirmThroughHub = async (
  request: FastifyRequest,
  reply: FastifyReply,
  code: string,
  body: { name?: unknown; apiBase?: unknown; webBase?: unknown }
) => {
  const fromBrowser = (raw: unknown, fallback: string | undefined) => {
    const parsed = parsePublicAddress(raw);
    if (parsed && !isLoopbackAddress(parsed)) return parsed;
    return parsePublicAddress(fallback) ?? parsed;
  };
  const api = fromBrowser(body.apiBase, apiPublic());
  const web = fromBrowser(body.webBase, WEB_PUBLIC);
  if (!api) return reply.code(404).send({ error: NOT_FOUND_MESSAGE });
  // Checked before claiming, which uses the code up: a TV told "localhost" would try
  // itself, never finish, and be left with a code nobody can confirm again.
  if (isLoopbackAddress(api)) {
    return reply.code(422).send({
      error:
        "This website was opened at localhost, which the TV can't reach. Open it at the address other devices use, or set FETCH_API_PUBLIC on the server.",
      code: "pairing_address_loopback",
    });
  }

  let claim;
  try {
    claim = await claimThroughHub(code, { api, web });
  } catch (error) {
    if (!(error instanceof PairingHubUnavailableError)) throw error;
    request.log.warn({ err: error }, "pairing hub claim failed");
    return reply.code(502).send({
      error: "Couldn't reach the pairing hub to find that TV. Check this server's internet connection and try again.",
      code: "pairing_hub_unavailable",
    });
  }
  if (!claim || claim.status === "not_found") return reply.code(404).send({ error: NOT_FOUND_MESSAGE });
  if (claim.status === "taken") return reply.code(409).send({ error: "That code has already been confirmed." });

  const now = new Date();
  const deviceName = deviceNameFrom(body.name, deviceNameFrom(claim.name, "TV"));
  const accountId = accountScope(request) ?? (await findAdminAccountId());
  // An expired local pairing may still hold the same code.
  await prisma.devicePairing.deleteMany({ where: { expiresAt: { lte: now } } });
  try {
    await prisma.devicePairing.create({
      data: {
        code,
        pollSecretHash: claim.handoffHash,
        deviceName,
        requesterIp: null,
        expiresAt: new Date(now.getTime() + PAIRING_TTL_MS),
        confirmedAt: now,
        accountId,
      },
    });
  } catch (error) {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
      return reply.code(409).send({ error: "That code has already been confirmed." });
    }
    throw error;
  }
  return { deviceName };
};

const devicesRoutes: FastifyPluginAsync = async (app) => {
  // ─── Pairing ───

  app.post<{ Body: unknown }>(
    "/devices/pair/start",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const body = (request.body ?? {}) as { name?: unknown };
      const now = new Date();
      await prisma.devicePairing.deleteMany({ where: { expiresAt: { lte: now } } });

      // Unconfirmed only: a confirmed code is just waiting for its TV's next poll.
      const fromThisAddress = await prisma.devicePairing.count({
        where: { requesterIp: request.ip, confirmedAt: null },
      });
      if (fromThisAddress >= MAX_PENDING_PAIRINGS_PER_IP) {
        return reply
          .code(429)
          .send({ error: "Too many pairings in progress from this device. Try again in a few minutes." });
      }
      const pending = await prisma.devicePairing.count({ where: { confirmedAt: null } });
      if (pending >= MAX_PENDING_PAIRINGS) {
        return reply.code(429).send({ error: "Too many pairings in progress. Try again in a few minutes." });
      }

      const pollSecret = generatePollSecret();
      const expiresAt = new Date(now.getTime() + PAIRING_TTL_MS);
      // A collision on an eight-character code is vanishingly rare, but the
      // unique index is what decides, so a clash is retried rather than a 500.
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const code = generatePairingCode();
        try {
          await prisma.devicePairing.create({
            data: {
              code,
              pollSecretHash: hashSecret(pollSecret),
              deviceName: deviceNameFrom(body.name, "TV"),
              requesterIp: request.ip,
              expiresAt,
            },
          });
          return reply.code(201).send({
            code: formatPairingCode(code),
            confirmUrl: pairingConfirmUrl(formatPairingCode(code)),
            pollSecret,
            expiresAt: expiresAt.toISOString(),
            intervalSec: POLL_INTERVAL_SEC,
          });
        } catch (error) {
          if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") continue;
          throw error;
        }
      }
      return reply.code(503).send({ error: "Could not allocate a pairing code. Try again." });
    }
  );

  app.post<{ Body: unknown }>(
    "/devices/pair/confirm",
    { preHandler: requireOwnerToken, config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const body = (request.body ?? {}) as { code?: unknown; name?: unknown; apiBase?: unknown; webBase?: unknown };
      const code = typeof body.code === "string" ? normalizePairingCode(body.code) : "";
      if (code.length !== PAIRING_CODE_LENGTH) {
        return reply.code(400).send({ error: `code must be ${PAIRING_CODE_LENGTH} letters and digits` });
      }

      const pairing = await prisma.devicePairing.findUnique({ where: { code } });
      // Unknown and expired answer alike, so the response says nothing about
      // which codes have ever existed.
      if (!pairing || pairing.expiresAt.getTime() <= Date.now()) {
        // Not one of this website's codes: most likely one a TV got from the
        // pairing hub, because it was never told this website's address.
        return confirmThroughHub(request, reply, code, body);
      }
      if (pairing.confirmedAt) {
        return reply.code(409).send({ error: "That code has already been confirmed." });
      }

      const deviceName = deviceNameFrom(body.name, pairing.deviceName);
      // The TV becomes part of whoever confirmed it, and sees their profiles.
      // Confirmed with API_TOKEN, it is the admin's.
      const accountId = accountScope(request) ?? (await findAdminAccountId());
      await prisma.devicePairing.update({
        where: { id: pairing.id },
        data: { confirmedAt: new Date(), deviceName, accountId },
      });
      return { deviceName };
    }
  );

  app.get(
    "/devices/pair/poll",
    { config: { rateLimit: { max: 40, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const header = request.headers[PAIRING_SECRET_HEADER];
      const secret = (Array.isArray(header) ? header[0] : header)?.trim();
      if (!secret) return reply.code(400).send({ error: `${PAIRING_SECRET_HEADER} header is required` });

      const pollSecretHash = hashSecret(secret);
      const pairing = await prisma.devicePairing.findUnique({ where: { pollSecretHash } });
      if (!pairing || pairing.expiresAt.getTime() <= Date.now()) {
        return reply.code(404).send({ status: "expired", error: "This pairing code has expired." });
      }
      if (!pairing.confirmedAt) {
        return reply.code(202).send({ status: "pending", expiresAt: pairing.expiresAt.toISOString() });
      }

      // Claim and create in one transaction: the delete only succeeds for one
      // poller, so a confirmed code yields exactly one device token.
      const token = generateDeviceToken();
      const device = await prisma.$transaction(async (tx) => {
        const claimed = await tx.devicePairing.deleteMany({ where: { id: pairing.id, confirmedAt: { not: null } } });
        if (claimed.count === 0) return null;
        return tx.device.create({
          data: {
            name: pairing.deviceName,
            tokenHash: hashSecret(token),
            lastSeenAt: new Date(),
            accountId: pairing.accountId,
          },
        });
      });
      if (!device) return reply.code(404).send({ status: "expired", error: "This pairing code has expired." });

      return { status: "approved", token, device: toDeviceView(device) };
    }
  );

  // ─── Managing devices (website) ───

  // Each account sees and manages only the TVs it paired; API_TOKEN sees every one.
  const ownedDeviceWhere = (request: FastifyRequest, id?: string) => {
    const scope = accountScope(request);
    return { ...(id ? { id } : {}), ...(scope ? { accountId: scope } : {}) };
  };

  app.get("/devices", { preHandler: requireOwnerToken }, async (request) => {
    const devices = await prisma.device.findMany({ where: ownedDeviceWhere(request), orderBy: { createdAt: "asc" } });
    return { devices: devices.map(toDeviceView) };
  });

  app.patch<{ Params: { id: string }; Body: unknown }>(
    "/devices/:id",
    { preHandler: requireOwnerToken },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
      const body = (request.body ?? {}) as { name?: unknown };
      if (typeof body.name !== "string" || !body.name.trim()) {
        return reply.code(400).send({ error: "name is required" });
      }
      const existing = await prisma.device.findFirst({ where: ownedDeviceWhere(request, request.params.id) });
      if (!existing) return reply.code(404).send({ error: "Device not found" });
      const device = await prisma.device.update({
        where: { id: existing.id },
        data: { name: deviceNameFrom(body.name, existing.name) },
      });
      invalidateDeviceCache();
      return { device: toDeviceView(device) };
    }
  );

  app.delete<{ Params: { id: string } }>(
    "/devices/:id",
    { preHandler: requireOwnerToken },
    async (request, reply) => {
      if (!UUID_V4_PATTERN.test(request.params.id)) return reply.code(400).send({ error: "id must be a valid UUID" });
      const { count } = await prisma.device.deleteMany({ where: ownedDeviceWhere(request, request.params.id) });
      // Cleared whether or not a row went, so the device's next request is
      // looked up again and answered 401 rather than served from the cache.
      invalidateDeviceCache();
      if (count === 0) return reply.code(404).send({ error: "Device not found" });
      return reply.code(204).send();
    }
  );

  // ─── The device itself ───

  app.get("/devices/me", { preHandler: requireDevice }, async (request, reply) => {
    const device = await prisma.device.findUnique({ where: { id: request.deviceId! } });
    if (!device) return reply.code(401).header("WWW-Authenticate", "Bearer").send({ error: "Unauthorized" });
    return { device: toDeviceView(device) };
  });

  app.get("/devices/me/profiles", { preHandler: requireDevice }, async (request) => {
    const profiles = await listDeviceProfiles(request.deviceId!, accountScope(request));
    return { profiles: profiles.map(toSlottedView), maxProfiles: MAX_DEVICE_PROFILE_SLOTS };
  });

  // Creates or updates the profile in a slot, from the TV's profile editor.
  // A PIN is never sent here: PINs are set on the website and verified by
  // `POST /profiles/:id/verify`, so the TV never holds a hash it could leak.
  app.put<{ Params: { slot: string }; Body: unknown }>(
    "/devices/me/profiles/:slot",
    { preHandler: requireDevice },
    async (request, reply) => {
      const slot = parseSlot(request.params.slot);
      if (slot === null) {
        return reply.code(400).send({ error: `slot must be between 1 and ${MAX_DEVICE_PROFILE_SLOTS}` });
      }
      const body = (request.body ?? {}) as { name?: unknown };
      const attributes = parseProfileAttributes(body);
      if (!attributes.ok) return reply.code(400).send({ error: attributes.error });
      if (body.name !== undefined && (typeof body.name !== "string" || !body.name.trim())) {
        return reply.code(400).send({ error: "name must be a non-empty string when provided" });
      }
      const name = typeof body.name === "string" ? body.name.trim() : undefined;

      const deviceId = request.deviceId!;
      const accountId = accountScope(request);
      const slotted = await findSlotProfileId(deviceId, slot);
      // A slot only ever points at this device's own account's profiles; this
      // is the guard for a row that somehow doesn't.
      const profileId =
        slotted && (await prisma.profile.count({ where: { id: slotted, ...(accountId ? { accountId } : {}) } }))
          ? slotted
          : null;
      if (profileId) {
        // A TV pushes its whole profile list, so one limit it may not lift
        // shouldn't stop the name or picture from syncing: a loosening this
        // request has no authority for is left out, and the stricter stored
        // limits come back in the answer. (The TV only ever tightens on a
        // pull, so it keeps its own setting; the website is where a grown-up
        // lifts a limit.)
        const data = { ...attributes.data };
        const current = await prisma.profile.findUnique({
          where: { id: profileId },
          select: { isKidsProfile: true, maxRating: true, allowUnrated: true },
        });
        if (current && loosensParentalLimits(current, data) && !(await hasParentalAuthority(request))) {
          // Field by field, so a tightening sent alongside still applies.
          for (const key of ["isKidsProfile", "maxRating", "allowUnrated"] as const) {
            if (data[key] !== undefined && loosensParentalLimits(current, { [key]: data[key] })) delete data[key];
          }
        }
        const profile = await prisma.profile.update({
          where: { id: profileId },
          data: { ...data, ...(name ? { name } : {}) },
          select: PROFILE_VIEW_SELECT,
        });
        invalidateProfileCache();
        return { profile: toSlottedView({ slot, profile }) };
      }

      if (!name) return reply.code(400).send({ error: "name is required to create a profile" });
      const profile = await prisma.$transaction(async (tx) => {
        const created = await tx.profile.create({
          data: { name, accountId, ...attributes.data },
          select: PROFILE_VIEW_SELECT,
        });
        await tx.deviceProfileSlot.create({ data: { deviceId, slot, profileId: created.id } });
        return created;
      });
      invalidateProfileCache();
      return reply.code(201).send({ profile: toSlottedView({ slot, profile }) });
    }
  );
};

export default devicesRoutes;
