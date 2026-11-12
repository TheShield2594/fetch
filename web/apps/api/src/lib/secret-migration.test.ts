import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { first } from "./test-fixtures/present.js";

const prismaMock = {
  kV: { findMany: vi.fn(), update: vi.fn() },
  traktToken: { findMany: vi.fn(), update: vi.fn() },
  profileConnection: { findMany: vi.fn(async () => []), update: vi.fn() },
  notificationChannel: { findMany: vi.fn(), update: vi.fn() },
  profileAddon: { findMany: vi.fn(), update: vi.fn() },
};

vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const { encryptStoredSecrets } = await import("./secret-migration.js");
const { SECRET_CONTEXT, decryptSecret, encryptSecret, isEncryptedSecret, kvSecretContext, secretLookupKey } =
  await import("./secret-box.js");

/** A channel row whose URL is already encrypted, so only its token is in play. */
const channelRow = (row: { id: string; name: string; token: string | null }) => ({
  ...row,
  url: encryptSecret(SECRET_CONTEXT.notificationChannelUrl, "https://ntfy.sh/fetch"),
});

const logger = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
const run = () => encryptStoredSecrets(logger as never);

const originalToken = process.env.API_TOKEN;

beforeEach(() => {
  vi.clearAllMocks();
  process.env.API_TOKEN = "migration-token";
  prismaMock.kV.findMany.mockResolvedValue([]);
  prismaMock.traktToken.findMany.mockResolvedValue([]);
  prismaMock.notificationChannel.findMany.mockResolvedValue([]);
  prismaMock.profileConnection.findMany.mockResolvedValue([]);
  prismaMock.profileAddon.findMany.mockResolvedValue([]);
  for (const model of [prismaMock.kV, prismaMock.traktToken, prismaMock.notificationChannel, prismaMock.profileAddon]) {
    model.update.mockResolvedValue({});
  }
});

afterEach(() => {
  if (originalToken === undefined) delete process.env.API_TOKEN;
  else process.env.API_TOKEN = originalToken;
});

describe("encryptStoredSecrets", () => {
  it("encrypts credential rows left in plaintext, in place", async () => {
    prismaMock.kV.findMany.mockResolvedValue([{ key: "tmdb:apiKey", value: "tmdb-plain" }]);
    prismaMock.traktToken.findMany.mockResolvedValue([
      { id: "default", accessToken: "access-plain", refreshToken: "refresh-plain" },
    ]);
    prismaMock.notificationChannel.findMany.mockResolvedValue([
      channelRow({ id: "chan-1", name: "Gotify", token: "gotify-plain" }),
    ]);

    const sweep = await run();

    expect(sweep).toEqual({ encrypted: 4, unreadable: [] });

    const kvWrite = first(prismaMock.kV.update.mock.calls, "prismaMock.kV.update call")[0];
    expect(decryptSecret(kvSecretContext("tmdb:apiKey"), kvWrite.data.value)).toBe("tmdb-plain");

    const [accessWrite, refreshWrite] = prismaMock.traktToken.update.mock.calls.map((c) => c[0]);
    expect(decryptSecret(SECRET_CONTEXT.traktAccessToken, accessWrite.data.accessToken)).toBe("access-plain");
    expect(decryptSecret(SECRET_CONTEXT.traktRefreshToken, refreshWrite.data.refreshToken)).toBe("refresh-plain");

    const channelWrite = first(prismaMock.notificationChannel.update.mock.calls, "prismaMock.notificationChannel.update call")[0];
    expect(channelWrite.where).toEqual({ id: "chan-1" });
    expect(decryptSecret(SECRET_CONTEXT.notificationChannelToken, channelWrite.data.token)).toBe("gotify-plain");
  });

  it("leaves already-encrypted rows alone, so a restart isn't a rewrite", async () => {
    prismaMock.kV.findMany.mockResolvedValue([
      { key: "ai:config", value: encryptSecret(kvSecretContext("ai:config"), '{"url":"http://llm"}') },
    ]);

    const sweep = await run();

    expect(sweep).toEqual({ encrypted: 0, unreadable: [] });
    expect(prismaMock.kV.update).not.toHaveBeenCalled();
  });

  it("names every credential a rotated API_TOKEN has locked out, and does not overwrite them", async () => {
    prismaMock.kV.findMany.mockResolvedValue([
      { key: "tmdb:apiKey", value: encryptSecret(kvSecretContext("tmdb:apiKey"), "tmdb-key") },
    ]);
    prismaMock.notificationChannel.findMany.mockResolvedValue([
      channelRow({ id: "chan-1", name: "Gotify", token: encryptSecret(SECRET_CONTEXT.notificationChannelToken, "gotify") }),
    ]);
    process.env.API_TOKEN = "rotated-token";

    const sweep = await run();

    expect(sweep.encrypted).toBe(0);
    expect(sweep.unreadable).toEqual([
      "tmdb:apiKey",
      "Notification channel token (Gotify)",
      "Notification channel URL (Gotify)",
    ]);
    expect(prismaMock.kV.update).not.toHaveBeenCalled();
    expect(prismaMock.notificationChannel.update).not.toHaveBeenCalled();
    expect(logger.error).toHaveBeenCalledWith(
      { credentials: ["tmdb:apiKey", "Notification channel token (Gotify)", "Notification channel URL (Gotify)"] },
      expect.stringContaining("API_TOKEN has changed")
    );
  });

  it("warns and touches nothing when there is no API_TOKEN to derive a key from", async () => {
    delete process.env.API_TOKEN;
    prismaMock.kV.findMany.mockResolvedValue([{ key: "tmdb:apiKey", value: "tmdb-plain" }]);

    const sweep = await run();

    expect(sweep).toEqual({ encrypted: 0, unreadable: [] });
    expect(prismaMock.kV.findMany).not.toHaveBeenCalled();
    expect(logger.warn).toHaveBeenCalledWith(expect.stringContaining("plaintext"));
  });

  it("only sweeps the KV rows that hold credentials", async () => {
    await run();

    const { where } = first(prismaMock.kV.findMany.mock.calls, "prismaMock.kV.findMany call")[0];
    const [fixed, perAccount] = where.OR;
    expect(fixed.key.in).toEqual(["tmdb:apiKey", "omdb:apiKey", "rpdb:apiKey", "ai:config", "push:vapidKeys"]);
    expect(fixed.key.in).not.toContain("settings:language");
    // Each account's own AI provider config.
    expect(perAccount).toEqual({ key: { startsWith: "ai:config:" } });
  });

  it("writes ciphertext that later reads can actually open", async () => {
    prismaMock.kV.findMany.mockResolvedValue([{ key: "push:vapidKeys", value: '{"publicKey":"pub"}' }]);

    await run();

    const written = first(prismaMock.kV.update.mock.calls, "prismaMock.kV.update call")[0].data.value;
    expect(isEncryptedSecret(written)).toBe(true);
    expect(JSON.parse(decryptSecret(kvSecretContext("push:vapidKeys"), written)!)).toEqual({ publicKey: "pub" });
  });
  it("encrypts a channel's URL and an addon's manifest URL, and gives the addon its lookup key", async () => {
    prismaMock.notificationChannel.findMany.mockResolvedValue([
      { id: "chan-2", name: "Discord", token: null, url: "https://discord.com/api/webhooks/1/secret" },
    ]);
    const manifestUrl = "https://addon.example/debrid-key/manifest.json";
    prismaMock.profileAddon.findMany.mockResolvedValue([{ id: "addon-1", name: "Torrents", manifestUrl, manifestUrlKey: null }]);

    const sweep = await run();

    expect(sweep).toEqual({ encrypted: 2, unreadable: [] });
    const channelWrite = first(prismaMock.notificationChannel.update.mock.calls, "channel update")[0];
    expect(decryptSecret(SECRET_CONTEXT.notificationChannelUrl, channelWrite.data.url)).toBe(
      "https://discord.com/api/webhooks/1/secret"
    );
    const addonWrite = first(prismaMock.profileAddon.update.mock.calls, "addon update")[0];
    expect(addonWrite.where).toEqual({ id: "addon-1" });
    expect(decryptSecret(SECRET_CONTEXT.profileAddonManifestUrl, addonWrite.data.manifestUrl)).toBe(manifestUrl);
    expect(addonWrite.data.manifestUrlKey).toBe(secretLookupKey(SECRET_CONTEXT.profileAddonManifestUrl, manifestUrl));
  });

  it("leaves an encrypted addon alone, and names one a rotated API_TOKEN has locked out", async () => {
    const context = SECRET_CONTEXT.profileAddonManifestUrl;
    const url = "https://addon.example/manifest.json";
    const current = { id: "a", name: "Fine", manifestUrl: encryptSecret(context, url), manifestUrlKey: secretLookupKey(context, url) };
    process.env.API_TOKEN = "an-older-token";
    const locked = { id: "b", name: "Locked", manifestUrl: encryptSecret(context, url), manifestUrlKey: "old-key" };
    process.env.API_TOKEN = "migration-token";
    prismaMock.profileAddon.findMany.mockResolvedValue([current, locked]);

    const sweep = await run();

    expect(sweep).toEqual({ encrypted: 0, unreadable: ["Add-on URL (Locked)"] });
    expect(prismaMock.profileAddon.update).not.toHaveBeenCalled();
  });
});
