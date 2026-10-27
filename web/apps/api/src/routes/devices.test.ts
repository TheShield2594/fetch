import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, FastifyPluginAsync } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";
import { createHash } from "node:crypto";

const hashSecret = (value: string) => createHash("sha256").update(value).digest("hex");

const DEVICE_ID = "33333333-3333-4333-8333-333333333333";
const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const SECOND_PROFILE_ID = "22222222-2222-4222-8222-222222222222";

const prismaMock = {
  devicePairing: {
    deleteMany: vi.fn(),
    count: vi.fn(),
    create: vi.fn(),
    findUnique: vi.fn(),
    update: vi.fn(),
  },
  device: { create: vi.fn(), findMany: vi.fn(), findUnique: vi.fn(), update: vi.fn(), deleteMany: vi.fn() },
  deviceProfileSlot: { findMany: vi.fn(), findUnique: vi.fn(), create: vi.fn(), delete: vi.fn() },
  profile: { findMany: vi.fn(), create: vi.fn(), update: vi.fn(), count: vi.fn() },
  // A device confirmed with API_TOKEN goes to the admin account; none exists here.
  account: { findFirst: vi.fn() },
  $transaction: vi.fn(),
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

const profileRow = (id: string, name: string, createdAt: string) => ({
  id,
  name,
  pinHash: null,
  avatarColorHex: "#1E88E5",
  avatarId: "fetch:fox",
  profileBackgroundId: null,
  usesPrimaryAddons: false,
  usesPrimaryPlugins: false,
  isKidsProfile: false,
  maxRating: null,
  allowUnrated: true,
  createdAt: new Date(createdAt),
  updatedAt: new Date(createdAt),
});

/**
 * The real gate sets `request.deviceId` from a device token; here a test
 * header stands in for it, so each test can be the website or a TV.
 */
const buildApp = (): Promise<FastifyInstance> =>
  buildRouteApp(async () => {
    const { default: routes } = await import("./devices.js");
    const plugin: FastifyPluginAsync = async (app) => {
      app.addHook("onRequest", async (request) => {
        const device = request.headers["x-test-device"];
        if (typeof device === "string") request.deviceId = device;
      });
      await app.register(routes);
    };
    return { default: plugin };
  });

const asDevice = { "x-test-device": DEVICE_ID };

describe("device routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubEnv("CATALOGGY_WEB_PUBLIC", "https://fetch.example/");
    prismaMock.devicePairing.deleteMany.mockResolvedValue({ count: 0 });
    prismaMock.devicePairing.count.mockResolvedValue(0);
    // The profile in a slot belongs to the device's account.
    prismaMock.profile.count.mockResolvedValue(1);
    prismaMock.$transaction.mockImplementation(async (callback: (tx: typeof prismaMock) => unknown) => callback(prismaMock));
  });

  describe("POST /devices/pair/start", () => {
    it("returns a dashed code and a poll secret, and stores only the secret's hash", async () => {
      prismaMock.devicePairing.create.mockResolvedValue({});
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/start", payload: { name: "Bedroom TV" } });

      expect(response.statusCode).toBe(201);
      const body = response.json();
      expect(body.code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
      expect(body.pollSecret).toEqual(expect.any(String));
      expect(body.confirmUrl).toBe(`https://fetch.example/pair?code=${body.code}`);
      const stored = prismaMock.devicePairing.create.mock.calls[0]![0].data;
      expect(stored.pollSecretHash).toBe(hashSecret(body.pollSecret));
      expect(stored.code).toBe(body.code.replace("-", ""));
      expect(stored.deviceName).toBe("Bedroom TV");
      expect(new Date(stored.expiresAt).getTime() - Date.now()).toBeLessThanOrEqual(5 * 60 * 1000);
    });

    it("refuses when too many pairings are already waiting", async () => {
      prismaMock.devicePairing.count.mockResolvedValue(20);
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/start", payload: {} });

      expect(response.statusCode).toBe(429);
      expect(prismaMock.devicePairing.create).not.toHaveBeenCalled();
    });
  });

  describe("POST /devices/pair/confirm", () => {
    const pending = (overrides: Record<string, unknown> = {}) => ({
      id: "pairing-1",
      code: "ABCDEFGH",
      deviceName: "TV",
      expiresAt: new Date(Date.now() + 60_000),
      confirmedAt: null,
      ...overrides,
    });

    it("confirms a code typed with a dash, spaces and lowercase", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue(pending());
      prismaMock.devicePairing.update.mockResolvedValue({});
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        payload: { code: " abcd-efgh ", name: "Living room" },
      });

      expect(response.statusCode).toBe(200);
      expect(prismaMock.devicePairing.findUnique).toHaveBeenCalledWith({ where: { code: "ABCDEFGH" } });
      expect(prismaMock.devicePairing.update).toHaveBeenCalledWith({
        where: { id: "pairing-1" },
        data: { confirmedAt: expect.any(Date), deviceName: "Living room", accountId: null },
      });
    });

    it("rejects a wrong code", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue(null);
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/confirm", payload: { code: "ZZZZ-ZZZZ" } });

      expect(response.statusCode).toBe(404);
    });

    it("rejects an expired code the same way as a wrong one", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue(pending({ expiresAt: new Date(Date.now() - 1) }));
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/confirm", payload: { code: "ABCD-EFGH" } });

      expect(response.statusCode).toBe(404);
      expect(prismaMock.devicePairing.update).not.toHaveBeenCalled();
    });

    it("rejects a code of the wrong length before looking it up", async () => {
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/confirm", payload: { code: "ABC" } });

      expect(response.statusCode).toBe(400);
      expect(prismaMock.devicePairing.findUnique).not.toHaveBeenCalled();
    });

    it("can't be used by a paired device", async () => {
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        headers: asDevice,
        payload: { code: "ABCD-EFGH" },
      });

      expect(response.statusCode).toBe(403);
    });
  });

  describe("GET /devices/pair/poll", () => {
    const poll = (app: FastifyInstance) =>
      app.inject({ method: "GET", url: "/devices/pair/poll", headers: { "x-pairing-secret": "the-secret" } });

    it("says pending until the code is confirmed", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue({
        id: "pairing-1",
        expiresAt: new Date(Date.now() + 60_000),
        confirmedAt: null,
      });
      const app = await buildApp();

      const response = await poll(app);

      expect(response.statusCode).toBe(202);
      expect(response.json().status).toBe("pending");
      expect(prismaMock.devicePairing.findUnique).toHaveBeenCalledWith({
        where: { pollSecretHash: hashSecret("the-secret") },
      });
    });

    it("hands over a device token once, storing only its hash", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue({
        id: "pairing-1",
        deviceName: "Living room",
        expiresAt: new Date(Date.now() + 60_000),
        confirmedAt: new Date(),
      });
      prismaMock.devicePairing.deleteMany.mockResolvedValue({ count: 1 });
      prismaMock.device.create.mockImplementation(async ({ data }: { data: { name: string } }) => ({
        id: DEVICE_ID,
        name: data.name,
        createdAt: new Date(),
        lastSeenAt: new Date(),
      }));
      const app = await buildApp();

      const response = await poll(app);

      expect(response.statusCode).toBe(200);
      const body = response.json();
      expect(body.status).toBe("approved");
      expect(body.token).toMatch(/^fdt_/);
      expect(body.device).toMatchObject({ id: DEVICE_ID, name: "Living room" });
      expect(prismaMock.device.create.mock.calls[0]![0].data.tokenHash).toBe(hashSecret(body.token));
    });

    it("gives nothing to a second poller that lost the claim", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue({
        id: "pairing-1",
        deviceName: "TV",
        expiresAt: new Date(Date.now() + 60_000),
        confirmedAt: new Date(),
      });
      prismaMock.devicePairing.deleteMany.mockResolvedValue({ count: 0 });
      const app = await buildApp();

      const response = await poll(app);

      expect(response.statusCode).toBe(404);
      expect(prismaMock.device.create).not.toHaveBeenCalled();
    });

    it("says expired once the code has lapsed", async () => {
      prismaMock.devicePairing.findUnique.mockResolvedValue({
        id: "pairing-1",
        expiresAt: new Date(Date.now() - 1),
        confirmedAt: new Date(),
      });
      const app = await buildApp();

      const response = await poll(app);

      expect(response.statusCode).toBe(404);
      expect(response.json().status).toBe("expired");
    });

    it("requires the secret", async () => {
      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: "/devices/pair/poll" });
      expect(response.statusCode).toBe(400);
    });
  });

  describe("managing devices", () => {
    it("lists devices with when each was last seen", async () => {
      prismaMock.device.findMany.mockResolvedValue([
        { id: DEVICE_ID, name: "TV", createdAt: new Date("2026-10-01T00:00:00Z"), lastSeenAt: null },
      ]);
      const app = await buildApp();

      const response = await app.inject({ method: "GET", url: "/devices" });

      expect(response.json()).toEqual({
        devices: [{ id: DEVICE_ID, name: "TV", createdAt: "2026-10-01T00:00:00.000Z", lastSeenAt: null }],
      });
    });

    it("revokes a device", async () => {
      prismaMock.device.deleteMany.mockResolvedValue({ count: 1 });
      const app = await buildApp();

      const response = await app.inject({ method: "DELETE", url: `/devices/${DEVICE_ID}` });

      expect(response.statusCode).toBe(204);
      expect(prismaMock.device.deleteMany).toHaveBeenCalledWith({ where: { id: DEVICE_ID } });
    });

    it("won't let a device list or revoke devices", async () => {
      const app = await buildApp();

      const list = await app.inject({ method: "GET", url: "/devices", headers: asDevice });
      const revoke = await app.inject({ method: "DELETE", url: `/devices/${DEVICE_ID}`, headers: asDevice });

      expect(list.statusCode).toBe(403);
      expect(revoke.statusCode).toBe(403);
      expect(prismaMock.device.deleteMany).not.toHaveBeenCalled();
    });
  });

  describe("GET /devices/me/profiles", () => {
    it("gives every profile a slot, the oldest taking the primary one", async () => {
      prismaMock.profile.findMany.mockResolvedValue([
        profileRow(PROFILE_ID, "Alice", "2026-01-01T00:00:00Z"),
        profileRow(SECOND_PROFILE_ID, "Bob", "2026-02-01T00:00:00Z"),
      ]);
      prismaMock.deviceProfileSlot.findMany.mockResolvedValue([]);
      prismaMock.deviceProfileSlot.create.mockResolvedValue({});
      const app = await buildApp();

      const response = await app.inject({ method: "GET", url: "/devices/me/profiles", headers: asDevice });

      expect(response.statusCode).toBe(200);
      expect(response.json().profiles).toMatchObject([
        { slot: 1, isPrimary: true, id: PROFILE_ID, name: "Alice", avatarId: "fetch:fox" },
        { slot: 2, isPrimary: false, id: SECOND_PROFILE_ID, name: "Bob" },
      ]);
    });

    it("keeps slots already assigned and fills the gap with a new profile", async () => {
      prismaMock.profile.findMany.mockResolvedValue([
        profileRow(PROFILE_ID, "Alice", "2026-01-01T00:00:00Z"),
        profileRow(SECOND_PROFILE_ID, "Bob", "2026-02-01T00:00:00Z"),
      ]);
      prismaMock.deviceProfileSlot.findMany.mockResolvedValue([
        { deviceId: DEVICE_ID, slot: 1, profileId: PROFILE_ID },
        { deviceId: DEVICE_ID, slot: 3, profileId: "gone" },
      ]);
      prismaMock.deviceProfileSlot.create.mockResolvedValue({});
      const app = await buildApp();

      const response = await app.inject({ method: "GET", url: "/devices/me/profiles", headers: asDevice });

      expect(response.json().profiles.map((p: { slot: number }) => p.slot)).toEqual([1, 2]);
      expect(prismaMock.deviceProfileSlot.create).toHaveBeenCalledWith({
        data: { deviceId: DEVICE_ID, slot: 2, profileId: SECOND_PROFILE_ID },
      });
    });

    it("is only for devices", async () => {
      const app = await buildApp();
      const response = await app.inject({ method: "GET", url: "/devices/me/profiles" });
      expect(response.statusCode).toBe(403);
    });
  });

  describe("PUT /devices/me/profiles/:slot", () => {
    it("updates the profile already in the slot", async () => {
      prismaMock.deviceProfileSlot.findUnique.mockResolvedValue({ deviceId: DEVICE_ID, slot: 2, profileId: PROFILE_ID });
      prismaMock.profile.update.mockResolvedValue(profileRow(PROFILE_ID, "Alicia", "2026-01-01T00:00:00Z"));
      const app = await buildApp();

      const response = await app.inject({
        method: "PUT",
        url: "/devices/me/profiles/2",
        headers: asDevice,
        payload: { name: "Alicia", avatarColorHex: "#43A047" },
      });

      expect(response.statusCode).toBe(200);
      expect(prismaMock.profile.update).toHaveBeenCalledWith(
        expect.objectContaining({ where: { id: PROFILE_ID }, data: { avatarColorHex: "#43A047", name: "Alicia" } })
      );
    });

    it("creates a profile for an empty slot and maps it", async () => {
      prismaMock.deviceProfileSlot.findUnique.mockResolvedValue(null);
      prismaMock.profile.create.mockResolvedValue(profileRow(SECOND_PROFILE_ID, "Kids", "2026-03-01T00:00:00Z"));
      prismaMock.deviceProfileSlot.create.mockResolvedValue({});
      const app = await buildApp();

      const response = await app.inject({
        method: "PUT",
        url: "/devices/me/profiles/4",
        headers: asDevice,
        payload: { name: "Kids" },
      });

      expect(response.statusCode).toBe(201);
      expect(response.json().profile).toMatchObject({ slot: 4, id: SECOND_PROFILE_ID });
      expect(prismaMock.deviceProfileSlot.create).toHaveBeenCalledWith({
        data: { deviceId: DEVICE_ID, slot: 4, profileId: SECOND_PROFILE_ID },
      });
    });

    it("rejects a slot the TV doesn't have", async () => {
      const app = await buildApp();
      const response = await app.inject({ method: "PUT", url: "/devices/me/profiles/7", headers: asDevice, payload: {} });
      expect(response.statusCode).toBe(400);
    });
  });
});
