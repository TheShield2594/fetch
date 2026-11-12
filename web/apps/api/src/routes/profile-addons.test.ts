import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";
import { SECRET_CONTEXT, decryptSecret, encryptSecret, secretLookupKey } from "../lib/secret-box.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const ADDON_ID = "22222222-2222-4222-8222-222222222222";
const OTHER_ID = "33333333-3333-4333-8333-333333333333";
const URL_A = "https://a.example/secret-config/manifest.json";
const URL_B = "https://b.example/manifest.json";

const prismaMock = {
  profileAddon: {
    findMany: vi.fn(),
    findFirst: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    deleteMany: vi.fn(),
  },
  profileDocument: { findUnique: vi.fn(), upsert: vi.fn() },
  $transaction: vi.fn(),
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

vi.mock("../lib/profile.js", () => ({
  resolveProfileParam: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));

const outboundScopeForProfile = vi.fn<(profileId: string) => Promise<{ allowPrivateNetwork: boolean }>>(
  async () => ({ allowPrivateNetwork: false })
);
vi.mock("../lib/integration-owner.js", () => ({
  outboundScopeForProfile: (profileId: string) => outboundScopeForProfile(profileId),
}));

const fetchManifest = vi.fn();
vi.mock("../lib/addon-manifest.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/addon-manifest.js")>()),
  fetchManifest: (...args: unknown[]) => fetchManifest(...args),
}));

const pruneHomeLayoutForAddons = vi.fn(async () => undefined);
vi.mock("../lib/home-layout.js", () => ({ pruneHomeLayoutForAddons: () => pruneHomeLayoutForAddons() }));

const CACHED = {
  id: "com.example",
  name: "Example",
  version: "1.0.0",
  description: null,
  logo: null,
  types: ["movie"],
  resources: ["stream", "catalog"],
  catalogs: [{ type: "movie", id: "top", name: "Top" }],
  configurable: true,
  configurationRequired: false,
};

const row = (overrides: Record<string, unknown> = {}) => ({
  id: ADDON_ID,
  manifestUrl: URL_A,
  manifestUrlKey: null,
  position: 0,
  enabled: true,
  addonId: "com.example",
  name: "Example",
  version: "1.0.0",
  description: null,
  logo: null,
  manifest: CACHED,
  manifestFetchedAt: new Date("2026-10-26T12:00:00Z"),
  createdAt: new Date("2026-10-26T12:00:00Z"),
  updatedAt: new Date("2026-10-26T12:00:00Z"),
  profileId: PROFILE_ID,
  ...overrides,
});

const buildApp = (): Promise<FastifyInstance> => buildRouteApp(() => import("./profile-addons.js"));

describe("profile addon routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.profileAddon.findMany.mockResolvedValue([]);
    prismaMock.profileDocument.findUnique.mockResolvedValue(null);
    prismaMock.$transaction.mockImplementation(async (ops: unknown[]) => ops);
    prismaMock.profileAddon.create.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => row(data));
    prismaMock.profileAddon.update.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => row(data));
  });

  it("lists addons in order with what the manifest said", async () => {
    prismaMock.profileAddon.findMany.mockResolvedValue([row()]);
    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: `/profiles/${PROFILE_ID}/addons` });

    expect(response.statusCode).toBe(200);
    expect(prismaMock.profileAddon.findMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: { profileId: PROFILE_ID } })
    );
    expect(response.json().addons[0]).toMatchObject({
      id: ADDON_ID,
      name: "Example",
      catalogs: [{ type: "movie", id: "top", name: "Top" }],
      configurable: true,
      configureUrl: "https://a.example/secret-config/configure",
      reachable: true,
    });
    expect(response.json().updatedAt).toBeNull();
  });

  it("says when the list last changed, once anything has changed it", async () => {
    prismaMock.profileDocument.findUnique.mockResolvedValue({
      document: {},
      version: 3,
      updatedAt: new Date("2026-10-27T09:00:00Z"),
    });
    const app = await buildApp();
    const response = await app.inject({ method: "GET", url: `/profiles/${PROFILE_ID}/addons` });
    expect(response.json().updatedAt).toBe("2026-10-27T09:00:00.000Z");
  });

  describe("POST /profiles/:id/addons", () => {
    it("fetches the manifest and installs the addon at the end", async () => {
      prismaMock.profileAddon.findMany.mockResolvedValue([{ manifestUrl: URL_B, position: 4 }]);
      fetchManifest.mockResolvedValue({ ok: true, manifest: CACHED });

      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: { manifestUrl: "stremio://a.example/secret-config/manifest.json" },
      });

      expect(response.statusCode).toBe(201);
      // Under the profile's own account's outbound scope.
      expect(outboundScopeForProfile).toHaveBeenCalledWith(PROFILE_ID);
      expect(fetchManifest).toHaveBeenCalledWith(URL_A, { allowPrivateNetwork: false });
      expect(prismaMock.profileAddon.create).toHaveBeenCalledWith({
        data: expect.objectContaining({ profileId: PROFILE_ID, manifestUrl: URL_A, position: 5, addonId: "com.example" }),
      });
    });

    it("rejects an address that isn't a manifest without fetching it", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: { manifestUrl: "https://a.example/" },
      });
      expect(response.statusCode).toBe(400);
      expect(fetchManifest).not.toHaveBeenCalled();
    });

    it("rejects a manifest that doesn't answer, with the reason and without the URL", async () => {
      fetchManifest.mockResolvedValue({ ok: false, reason: "The addon didn't answer." });
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: { manifestUrl: URL_A },
      });
      expect(response.statusCode).toBe(400);
      expect(response.json()).toEqual({ error: "The addon didn't answer.", code: "manifest_invalid" });
      expect(response.body).not.toContain("secret-config");
      expect(prismaMock.profileAddon.create).not.toHaveBeenCalled();
    });

    it("falls back to a manifest the caller sent when the server can't reach the addon", async () => {
      fetchManifest.mockResolvedValue({ ok: false, reason: "The addon didn't answer." });
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: {
          manifestUrl: URL_A,
          manifest: { id: "com.example", name: "Example", version: "1.0.0", resources: ["catalog"] },
        },
      });
      expect(response.statusCode).toBe(201);
    });

    it("refuses an addon that is already installed", async () => {
      prismaMock.profileAddon.findMany.mockResolvedValue([{ manifestUrl: URL_A, position: 0 }]);
      const app = await buildApp();
      const response = await app.inject({
        method: "POST",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: { manifestUrl: URL_A },
      });
      expect(response.statusCode).toBe(409);
      expect(fetchManifest).not.toHaveBeenCalled();
    });
  });

  it("toggles an addon", async () => {
    prismaMock.profileAddon.findFirst.mockResolvedValue(row());
    const app = await buildApp();
    const response = await app.inject({
      method: "PATCH",
      url: `/profiles/${PROFILE_ID}/addons/${ADDON_ID}`,
      payload: { enabled: false },
    });
    expect(response.statusCode).toBe(200);
    expect(prismaMock.profileAddon.findFirst).toHaveBeenCalledWith({ where: { id: ADDON_ID, profileId: PROFILE_ID } });
    expect(prismaMock.profileAddon.update).toHaveBeenCalledWith({ where: { id: ADDON_ID }, data: { enabled: false } });
  });

  it("removes an addon and prunes its Home rows", async () => {
    prismaMock.profileAddon.deleteMany.mockResolvedValue({ count: 1 });
    const app = await buildApp();
    const response = await app.inject({ method: "DELETE", url: `/profiles/${PROFILE_ID}/addons/${ADDON_ID}` });
    expect(response.statusCode).toBe(204);
    expect(prismaMock.profileAddon.deleteMany).toHaveBeenCalledWith({ where: { id: ADDON_ID, profileId: PROFILE_ID } });
    expect(pruneHomeLayoutForAddons).toHaveBeenCalled();
    expect(prismaMock.profileDocument.upsert).toHaveBeenCalledWith(
      expect.objectContaining({ where: { profileId_kind: { profileId: PROFILE_ID, kind: "addon-list" } } })
    );
  });

  describe("PUT /profiles/:id/addons/order", () => {
    it("rewrites every position", async () => {
      prismaMock.profileAddon.findMany.mockResolvedValue([row(), row({ id: OTHER_ID, manifestUrl: URL_B })]);
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/addons/order`,
        payload: { ids: [OTHER_ID, ADDON_ID] },
      });
      expect(response.statusCode).toBe(200);
      expect(prismaMock.profileAddon.update).toHaveBeenNthCalledWith(1, { where: { id: OTHER_ID }, data: { position: 0 } });
      expect(prismaMock.profileAddon.update).toHaveBeenNthCalledWith(2, { where: { id: ADDON_ID }, data: { position: 1 } });
    });

    it("refuses an order that leaves an addon out", async () => {
      prismaMock.profileAddon.findMany.mockResolvedValue([row(), row({ id: OTHER_ID, manifestUrl: URL_B })]);
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/addons/order`,
        payload: { ids: [ADDON_ID] },
      });
      expect(response.statusCode).toBe(400);
    });
  });

  describe("PUT /profiles/:id/addons (the TV's whole-list sync)", () => {
    it("keeps known addons, adds new ones, drops the rest, in the order sent", async () => {
      prismaMock.profileAddon.findMany.mockResolvedValue([row()]);
      fetchManifest.mockResolvedValue({ ok: false, reason: "The addon didn't answer." });

      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: {
          addons: [
            { manifestUrl: URL_B, name: "Unreachable", enabled: false },
            { manifestUrl: URL_A },
            { manifestUrl: URL_B },
          ],
        },
      });

      expect(response.statusCode).toBe(200);
      expect(prismaMock.profileAddon.deleteMany).toHaveBeenCalledWith({
        where: { profileId: PROFILE_ID, id: { notIn: [ADDON_ID] } },
      });
      // The unreachable one is kept, named by what the TV called it.
      expect(prismaMock.profileAddon.create).toHaveBeenCalledWith({
        data: expect.objectContaining({ profileId: PROFILE_ID, position: 0, enabled: false, name: "Unreachable" }),
      });
      expect(prismaMock.profileAddon.update).toHaveBeenCalledWith({
        where: { id: ADDON_ID },
        data: { position: 1, enabled: true },
      });
      // Only the new addon is fetched.
      expect(fetchManifest).toHaveBeenCalledTimes(1);
      expect(pruneHomeLayoutForAddons).toHaveBeenCalled();
    });

    it("replaces a row whose URL can't be read any more", async () => {
      const original = process.env.API_TOKEN;
      try {
        process.env.API_TOKEN = "old-token-".padEnd(40, "x");
        const locked = encryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, URL_A);
        process.env.API_TOKEN = "new-token-".padEnd(40, "y");
        prismaMock.profileAddon.findMany.mockResolvedValue([row({ manifestUrl: locked })]);
        fetchManifest.mockResolvedValue({ ok: true, manifest: CACHED });

        const app = await buildApp();
        const response = await app.inject({
          method: "PUT",
          url: `/profiles/${PROFILE_ID}/addons`,
          payload: { addons: [{ manifestUrl: URL_A }] },
        });

        expect(response.statusCode).toBe(200);
        expect(prismaMock.profileAddon.deleteMany).toHaveBeenCalledWith({
          where: { profileId: PROFILE_ID, id: { notIn: [] } },
        });
        expect(prismaMock.profileAddon.create).toHaveBeenCalledTimes(1);
      } finally {
        if (original === undefined) delete process.env.API_TOKEN;
        else process.env.API_TOKEN = original;
      }
    });

    it("rejects an entry that isn't a manifest address", async () => {
      const app = await buildApp();
      const response = await app.inject({
        method: "PUT",
        url: `/profiles/${PROFILE_ID}/addons`,
        payload: { addons: [{ manifestUrl: "https://nope.example/" }] },
      });
      expect(response.statusCode).toBe(400);
      expect(response.json().error).toContain("addons.0.manifestUrl");
    });
  });
  describe("manifest URLs at rest", () => {
    const original = process.env.API_TOKEN;
    const withToken = async (work: () => Promise<void>) => {
      process.env.API_TOKEN = "addon-route-token-".padEnd(48, "z");
      try {
        await work();
      } finally {
        if (original === undefined) delete process.env.API_TOKEN;
        else process.env.API_TOKEN = original;
      }
    };

    it("stores a new addon's URL encrypted, with a lookup key, and answers with it in full", () =>
      withToken(async () => {
        prismaMock.profileAddon.findMany.mockResolvedValue([]);
        fetchManifest.mockResolvedValue({ ok: true, manifest: CACHED });
        prismaMock.profileAddon.create.mockImplementation(async ({ data }: { data: Record<string, unknown> }) =>
          row(data)
        );

        const app = await buildApp();
        const response = await app.inject({
          method: "POST",
          url: `/profiles/${PROFILE_ID}/addons`,
          payload: { manifestUrl: URL_A },
        });

        expect(response.statusCode).toBe(201);
        const data = prismaMock.profileAddon.create.mock.calls[0]![0].data;
        expect(data.manifestUrl).not.toContain("secret-config");
        expect(decryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, data.manifestUrl)).toBe(URL_A);
        expect(data.manifestUrlKey).toBe(secretLookupKey(SECRET_CONTEXT.profileAddonManifestUrl, URL_A));
        expect(response.json().addon.manifestUrl).toBe(URL_A);
      }));

    it("refuses a second install of the same URL even though the ciphertext differs", () =>
      withToken(async () => {
        prismaMock.profileAddon.findMany.mockResolvedValue([
          row({ manifestUrl: encryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, URL_A) }),
        ]);
        const app = await buildApp();
        const response = await app.inject({
          method: "POST",
          url: `/profiles/${PROFILE_ID}/addons`,
          payload: { manifestUrl: URL_A },
        });
        expect(response.statusCode).toBe(409);
      }));

    it("leaves a row it can't decrypt out of the list", () =>
      withToken(async () => {
        const readable = row({ manifestUrl: encryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, URL_B) });
        process.env.API_TOKEN = "a-different-token-".padEnd(48, "q");
        const locked = row({ id: OTHER_ID, manifestUrl: encryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, URL_A) });
        process.env.API_TOKEN = "addon-route-token-".padEnd(48, "z");
        prismaMock.profileAddon.findMany.mockResolvedValue([readable, locked]);
        prismaMock.profileDocument.findUnique.mockResolvedValue(null);

        const app = await buildApp();
        const response = await app.inject({ method: "GET", url: `/profiles/${PROFILE_ID}/addons` });

        expect(response.statusCode).toBe(200);
        expect(response.json().addons.map((addon: { manifestUrl: string }) => addon.manifestUrl)).toEqual([URL_B]);
      }));
  });
});
