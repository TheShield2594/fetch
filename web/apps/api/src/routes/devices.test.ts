import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, FastifyPluginAsync } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";
import type { AuthPrincipal } from "../lib/request-auth.js";
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
  profile: { findMany: vi.fn(), findUnique: vi.fn(), create: vi.fn(), update: vi.fn(), count: vi.fn() },
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
const buildApp = (auth?: AuthPrincipal): Promise<FastifyInstance> =>
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
  }, {}, auth);

const asDevice = { "x-test-device": DEVICE_ID };

describe("device routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubEnv("FETCH_WEB_PUBLIC", "https://fetch.example/");
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

    it("records the requesting address on the pairing", async () => {
      prismaMock.devicePairing.create.mockResolvedValue({});
      const app = await buildApp();

      await app.inject({ method: "POST", url: "/devices/pair/start", payload: {}, remoteAddress: "10.0.0.7" });

      expect(prismaMock.devicePairing.count).toHaveBeenCalledWith({
        where: { requesterIp: "10.0.0.7", confirmedAt: null },
      });
      expect(prismaMock.devicePairing.create.mock.calls[0]![0].data.requesterIp).toBe("10.0.0.7");
    });

    it("refuses when one address already holds its share of pending pairings", async () => {
      // Per-address count first, then the global one.
      prismaMock.devicePairing.count.mockResolvedValueOnce(3).mockResolvedValueOnce(3);
      const app = await buildApp();

      const response = await app.inject({ method: "POST", url: "/devices/pair/start", payload: {} });

      expect(response.statusCode).toBe(429);
      expect(prismaMock.devicePairing.create).not.toHaveBeenCalled();
    });

    it("lets another address pair while one is at its cap", async () => {
      prismaMock.devicePairing.count.mockImplementation(async (args?: { where?: { requesterIp?: string } }) =>
        args?.where?.requesterIp === "10.0.0.66" ? 3 : 2
      );
      prismaMock.devicePairing.create.mockResolvedValue({});
      const app = await buildApp();

      const blocked = await app.inject({ method: "POST", url: "/devices/pair/start", payload: {}, remoteAddress: "10.0.0.66" });
      const allowed = await app.inject({ method: "POST", url: "/devices/pair/start", payload: {}, remoteAddress: "10.0.0.8" });

      expect(blocked.statusCode).toBe(429);
      expect(allowed.statusCode).toBe(201);
    });

    it("refuses when too many pairings are already waiting overall", async () => {
      prismaMock.devicePairing.count.mockResolvedValueOnce(0).mockResolvedValueOnce(200);
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

  describe("POST /devices/pair/confirm with a code from the pairing hub", () => {
    const handoffSecret = "the-tvs-handoff-secret";

    beforeEach(() => {
      // This install is the hub, so the claim goes to the in-memory store
      // rather than over the network.
      vi.stubEnv("FETCH_PAIRING_HUB_SERVE", "true");
      prismaMock.devicePairing.findUnique.mockResolvedValue(null);
      prismaMock.devicePairing.create.mockResolvedValue({});
    });

    const startAtHub = async () => {
      const { startHubPairing } = await import("../lib/pairing-hub.js");
      const started = startHubPairing({ name: "Shield TV", handoffHash: hashSecret(handoffSecret), ip: "203.0.113.9" });
      if (!started.ok) throw new Error("hub start failed");
      return started;
    };

    it("claims the code at the hub with the browser's addresses and stores a confirmed pairing for the handoff secret", async () => {
      // The app first: building it reloads modules, and the hub's store must
      // be the one the routes see.
      const app = await buildApp();
      const started = await startAtHub();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        payload: { code: started.code, apiBase: "https://fetch.home.example/api/", webBase: "https://fetch.home.example" },
      });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({ deviceName: "Shield TV" });
      expect(prismaMock.devicePairing.create).toHaveBeenCalledWith({
        data: expect.objectContaining({
          code: started.code.replace("-", ""),
          pollSecretHash: hashSecret(handoffSecret),
          deviceName: "Shield TV",
          confirmedAt: expect.any(Date),
          requesterIp: null,
        }),
      });
      const { pollHubPairing } = await import("../lib/pairing-hub.js");
      expect(pollHubPairing(started.pollSecret)).toEqual({
        status: "linked",
        link: { api: "https://fetch.home.example/api", web: "https://fetch.home.example" },
      });
    });

    it("answers 409 for a hub code another website already claimed", async () => {
      const app = await buildApp();
      const started = await startAtHub();
      const confirm = (apiBase: string) =>
        app.inject({ method: "POST", url: "/devices/pair/confirm", payload: { code: started.code, apiBase } });

      expect((await confirm("https://fetch.home.example/api")).statusCode).toBe(200);
      expect((await confirm("https://elsewhere.example/api")).statusCode).toBe(409);
    });

    it("can retry a claim whose pairing failed to save", async () => {
      const app = await buildApp();
      const started = await startAtHub();
      const confirm = () =>
        app.inject({
          method: "POST",
          url: "/devices/pair/confirm",
          payload: { code: started.code, apiBase: "https://fetch.home.example/api" },
        });
      prismaMock.devicePairing.create.mockRejectedValueOnce(new Error("database went away"));

      expect((await confirm()).statusCode).toBe(500);
      expect((await confirm()).statusCode).toBe(200);
      expect(prismaMock.devicePairing.create).toHaveBeenLastCalledWith({
        data: expect.objectContaining({ pollSecretHash: hashSecret(handoffSecret) }),
      });
    });

    it("refuses to give the TV a loopback address, before using the code up", async () => {
      const app = await buildApp();
      const started = await startAtHub();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        payload: { code: started.code, apiBase: "http://localhost:7000" },
      });

      expect(response.statusCode).toBe(422);
      expect(response.json().code).toBe("pairing_address_loopback");
      const { pollHubPairing } = await import("../lib/pairing-hub.js");
      expect(pollHubPairing(started.pollSecret).status).toBe("pending");
    });

    it("answers 404 when the hub doesn't know the code either", async () => {
      const app = await buildApp();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        payload: { code: "WXYZ-2345", apiBase: "https://fetch.home.example/api" },
      });

      expect(response.statusCode).toBe(404);
      expect(prismaMock.devicePairing.create).not.toHaveBeenCalled();
    });

    it("gives the TV FETCH_API_PUBLIC rather than a loopback address the browser used", async () => {
      vi.stubEnv("FETCH_API_PUBLIC", "http://192.168.1.25:7000/");
      // The app first: building it reloads modules, and the hub's store must
      // be the one the routes see.
      const app = await buildApp();
      const started = await startAtHub();

      const response = await app.inject({
        method: "POST",
        url: "/devices/pair/confirm",
        payload: { code: started.code, apiBase: "http://localhost:7000" },
      });

      expect(response.statusCode).toBe(200);
      const { pollHubPairing } = await import("../lib/pairing-hub.js");
      expect(pollHubPairing(started.pollSecret)).toMatchObject({ status: "linked", link: { api: "http://192.168.1.25:7000" } });
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
      prismaMock.profile.findUnique.mockResolvedValue(profileRow(PROFILE_ID, "Alice", "2026-01-01T00:00:00Z"));
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

    // A TV pushes its whole list: a limit it may not lift stays as stored, and
    // the rest of the update still lands.
    it("keeps a stored parental limit the TV has no authority to lift, and applies the rest", async () => {
      prismaMock.deviceProfileSlot.findUnique.mockResolvedValue({ deviceId: DEVICE_ID, slot: 2, profileId: PROFILE_ID });
      prismaMock.profile.findUnique.mockResolvedValue({
        ...profileRow(PROFILE_ID, "Alice", "2026-01-01T00:00:00Z"),
        isKidsProfile: true,
        maxRating: "PARENTAL_GUIDANCE",
        allowUnrated: false,
      });
      prismaMock.profile.findMany.mockResolvedValue([{ id: PROFILE_ID }, { id: SECOND_PROFILE_ID }]);
      prismaMock.profile.update.mockResolvedValue(profileRow(PROFILE_ID, "Alicia", "2026-01-01T00:00:00Z"));
      const app = await buildApp({ kind: "device", deviceId: DEVICE_ID, accountId: "acc-1" });

      const response = await app.inject({
        method: "PUT",
        url: "/devices/me/profiles/2",
        headers: asDevice,
        payload: { name: "Alicia", parental: { isKidsProfile: false, maxRating: "ADULTS_ONLY", allowUnrated: false } },
      });

      expect(response.statusCode).toBe(200);
      // allowUnrated: false is no loosening, so it stays in.
      expect(prismaMock.profile.update).toHaveBeenCalledWith(
        expect.objectContaining({ where: { id: PROFILE_ID }, data: { allowUnrated: false, name: "Alicia" } })
      );
    });

    // The checks `PATCH /profiles/:id` and `POST /profiles` make (#544).
    describe("as the profile routes would", () => {
      const ACCOUNT_ID = "acc-1";
      const device: AuthPrincipal = { kind: "device", deviceId: DEVICE_ID, accountId: ACCOUNT_ID };
      const stored = (overrides: object = {}) => ({
        ...profileRow(PROFILE_ID, "Alice", "2026-01-01T00:00:00Z"),
        accountId: ACCOUNT_ID,
        ...overrides,
      });

      it("leaves a PIN-protected profile the TV hasn't unlocked as it is, and answers with what is stored", async () => {
        prismaMock.deviceProfileSlot.findUnique.mockResolvedValue({ deviceId: DEVICE_ID, slot: 2, profileId: PROFILE_ID });
        prismaMock.profile.findUnique.mockResolvedValue(stored({ pinHash: "scrypt$stored" }));
        const app = await buildApp(device);

        const response = await app.inject({
          method: "PUT",
          url: "/devices/me/profiles/2",
          headers: asDevice,
          payload: { name: "Mallory", avatarId: "fetch:owl" },
        });

        expect(response.statusCode).toBe(200);
        expect(response.json().profile).toMatchObject({ slot: 2, name: "Alice", avatarId: "fetch:fox", hasPin: true });
        expect(prismaMock.profile.update).not.toHaveBeenCalled();
      });

      it("changes a PIN-protected profile for a TV holding its profile token", async () => {
        vi.stubEnv("API_TOKEN", "test-api-token-0123456789abcdef0123456789abcdef");
        prismaMock.deviceProfileSlot.findUnique.mockResolvedValue({ deviceId: DEVICE_ID, slot: 2, profileId: PROFILE_ID });
        prismaMock.profile.findUnique.mockResolvedValue(stored({ pinHash: "scrypt$stored" }));
        prismaMock.profile.update.mockResolvedValue(stored({ name: "Alicia", pinHash: "scrypt$stored" }));
        const app = await buildApp(device);
        const { mintProfileToken } = await import("../lib/profile-token.js");

        const response = await app.inject({
          method: "PUT",
          url: "/devices/me/profiles/2",
          headers: { ...asDevice, "x-profile-token": mintProfileToken(PROFILE_ID, "scrypt$stored") },
          payload: { name: "Alicia" },
        });

        expect(response.statusCode).toBe(200);
        expect(prismaMock.profile.update).toHaveBeenCalledWith(
          expect.objectContaining({ where: { id: PROFILE_ID }, data: { name: "Alicia" } })
        );
      });

      const createInSlot = (app: FastifyInstance, headers: Record<string, string> = {}) =>
        app.inject({
          method: "PUT",
          url: "/devices/me/profiles/4",
          headers: { ...asDevice, ...headers },
          payload: { name: "Unrestricted" },
        });

      it("refuses to add a profile for a TV being used as a kids profile", async () => {
        prismaMock.deviceProfileSlot.findUnique.mockResolvedValue(null);
        prismaMock.profile.findUnique.mockResolvedValue(stored({ isKidsProfile: true }));
        const app = await buildApp(device);

        const response = await createInSlot(app, { "x-profile-id": PROFILE_ID });

        expect(response.statusCode).toBe(403);
        expect(response.json()).toMatchObject({ code: "parental_authority_required" });
        expect(prismaMock.profile.create).not.toHaveBeenCalled();
      });

      it("refuses to add a profile when the TV names none and the account has a kids profile", async () => {
        prismaMock.deviceProfileSlot.findUnique.mockResolvedValue(null);
        prismaMock.profile.findMany.mockResolvedValue([{ id: PROFILE_ID }, { id: SECOND_PROFILE_ID }]);
        prismaMock.profile.count.mockResolvedValue(1);
        const app = await buildApp(device);

        const response = await createInSlot(app);

        expect(response.statusCode).toBe(403);
        expect(prismaMock.profile.create).not.toHaveBeenCalled();
      });

      it("adds a profile for a TV being used as a grown-up's profile", async () => {
        prismaMock.deviceProfileSlot.findUnique.mockResolvedValue(null);
        prismaMock.profile.findUnique.mockResolvedValue(stored());
        prismaMock.profile.create.mockResolvedValue(profileRow(SECOND_PROFILE_ID, "Unrestricted", "2026-03-01T00:00:00Z"));
        prismaMock.deviceProfileSlot.create.mockResolvedValue({});
        const app = await buildApp(device);

        const response = await createInSlot(app, { "x-profile-id": PROFILE_ID });

        expect(response.statusCode).toBe(201);
        expect(prismaMock.profile.create).toHaveBeenCalledWith(
          expect.objectContaining({ data: expect.objectContaining({ name: "Unrestricted", accountId: ACCOUNT_ID }) })
        );
      });
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
