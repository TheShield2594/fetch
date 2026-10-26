import type { FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import { Prisma } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { requireOwnerToken } from "../lib/auth.js";
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
import { invalidateProfileCache } from "../lib/profile.js";
import { UUID_V4_PATTERN } from "../lib/types.js";

/** How long a code shown on the TV can be confirmed for. */
export const PAIRING_TTL_MS = 5 * 60 * 1000;
/** How often the TV is told to poll. */
const POLL_INTERVAL_SEC = 3;
/**
 * Unconfirmed pairings allowed at once. Starting one needs no credential, so
 * without a ceiling the table is something anyone on the network can fill.
 */
const MAX_PENDING_PAIRINGS = 20;
const MAX_DEVICE_NAME_LENGTH = 80;
const PAIRING_SECRET_HEADER = "x-pairing-secret";

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

const devicesRoutes: FastifyPluginAsync = async (app) => {
  // ─── Pairing ───

  app.post<{ Body: unknown }>(
    "/devices/pair/start",
    { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const body = (request.body ?? {}) as { name?: unknown };
      const now = new Date();
      await prisma.devicePairing.deleteMany({ where: { expiresAt: { lte: now } } });

      const pending = await prisma.devicePairing.count();
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
              expiresAt,
            },
          });
          return reply.code(201).send({
            code: formatPairingCode(code),
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
      const body = (request.body ?? {}) as { code?: unknown; name?: unknown };
      const code = typeof body.code === "string" ? normalizePairingCode(body.code) : "";
      if (code.length !== PAIRING_CODE_LENGTH) {
        return reply.code(400).send({ error: `code must be ${PAIRING_CODE_LENGTH} letters and digits` });
      }

      const pairing = await prisma.devicePairing.findUnique({ where: { code } });
      // Unknown and expired answer alike, so the response says nothing about
      // which codes have ever existed.
      if (!pairing || pairing.expiresAt.getTime() <= Date.now()) {
        return reply.code(404).send({ error: "That code is wrong or has expired. Check the TV for a new one." });
      }
      if (pairing.confirmedAt) {
        return reply.code(409).send({ error: "That code has already been confirmed." });
      }

      const deviceName = deviceNameFrom(body.name, pairing.deviceName);
      await prisma.devicePairing.update({
        where: { id: pairing.id },
        data: { confirmedAt: new Date(), deviceName },
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
          data: { name: pairing.deviceName, tokenHash: hashSecret(token), lastSeenAt: new Date() },
        });
      });
      if (!device) return reply.code(404).send({ status: "expired", error: "This pairing code has expired." });

      return { status: "approved", token, device: toDeviceView(device) };
    }
  );

  // ─── Managing devices (website) ───

  app.get("/devices", { preHandler: requireOwnerToken }, async () => {
    const devices = await prisma.device.findMany({ orderBy: { createdAt: "asc" } });
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
      const existing = await prisma.device.findUnique({ where: { id: request.params.id } });
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
      const { count } = await prisma.device.deleteMany({ where: { id: request.params.id } });
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
    const profiles = await listDeviceProfiles(request.deviceId!);
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
      const profileId = await findSlotProfileId(deviceId, slot);
      if (profileId) {
        const profile = await prisma.profile.update({
          where: { id: profileId },
          data: { ...attributes.data, ...(name ? { name } : {}) },
          select: PROFILE_VIEW_SELECT,
        });
        invalidateProfileCache();
        return { profile: toSlottedView({ slot, profile }) };
      }

      if (!name) return reply.code(400).send({ error: "name is required to create a profile" });
      const profile = await prisma.$transaction(async (tx) => {
        const created = await tx.profile.create({
          data: { name, ...attributes.data },
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
