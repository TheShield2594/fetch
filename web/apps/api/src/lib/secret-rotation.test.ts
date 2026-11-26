import { beforeEach, describe, expect, it, vi } from "vitest";

const model = () => ({
  findMany: vi.fn(async (): Promise<unknown[]> => []),
  update: vi.fn<(args: { where: unknown; data: unknown }) => Promise<object>>(async () => ({})),
});

/** The `data` of a model's first update. */
const dataOf = <T>(m: ReturnType<typeof model>): T => {
  const call = m.update.mock.calls[0];
  if (!call) throw new Error("expected an update");
  return call[0].data as T;
};
const tx = {
  kV: model(),
  traktToken: model(),
  notificationChannel: model(),
  profileAddon: model(),
  profileConnection: model(),
  account: model(),
};
const prismaMock = { $transaction: vi.fn(async (fn: (client: typeof tx) => Promise<unknown>) => fn(tx)) };

vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const { RotationError, rotateStoredSecrets } = await import("./secret-rotation.js");
const { SECRET_CONTEXT, decryptSecretUnder, encryptSecretUnder, kvSecretContext, secretLookupKeyUnder } = await import(
  "./secret-box.js"
);

const OLD = "o".repeat(64);
const NEW = "n".repeat(64);
const under = (root: string, context: string, value: string) => encryptSecretUnder(root, context, value);

beforeEach(() => {
  vi.clearAllMocks();
  for (const m of Object.values(tx)) m.findMany.mockResolvedValue([]);
});

describe("rotateStoredSecrets (#638)", () => {
  it("re-encrypts every column from the old root to the new one", async () => {
    tx.kV.findMany.mockResolvedValue([{ key: "tmdb:apiKey", value: under(OLD, kvSecretContext("tmdb:apiKey"), "tmdb") }]);
    tx.traktToken.findMany.mockResolvedValue([
      {
        id: "p1",
        accessToken: under(OLD, SECRET_CONTEXT.traktAccessToken, "access"),
        refreshToken: under(OLD, SECRET_CONTEXT.traktRefreshToken, "refresh"),
      },
    ]);
    tx.notificationChannel.findMany.mockResolvedValue([
      { id: "c1", name: "ntfy", url: under(OLD, SECRET_CONTEXT.notificationChannelUrl, "https://ntfy.sh/t"), token: null },
    ]);
    tx.profileAddon.findMany.mockResolvedValue([
      { id: "a1", name: "Torrentio", manifestUrl: under(OLD, SECRET_CONTEXT.profileAddonManifestUrl, "https://t/manifest.json") },
    ]);
    tx.account.findMany.mockResolvedValue([
      { id: "u1", username: "admin", totpSecret: under(OLD, SECRET_CONTEXT.accountTotpSecret, "TOTP"), totpPendingSecret: null },
    ]);

    expect(await rotateStoredSecrets(OLD, NEW)).toEqual({ reencrypted: 5 });

    const kvData = dataOf<{ value: string }>(tx.kV);
    expect(decryptSecretUnder(NEW, kvSecretContext("tmdb:apiKey"), kvData.value)).toBe("tmdb");
    expect(decryptSecretUnder(OLD, kvSecretContext("tmdb:apiKey"), kvData.value)).toBeNull();

    const trakt = dataOf<{ accessToken: string; refreshToken: string }>(tx.traktToken);
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.traktAccessToken, trakt.accessToken)).toBe("access");
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.traktRefreshToken, trakt.refreshToken)).toBe("refresh");

    const channel = dataOf<{ url: string; token: string | null }>(tx.notificationChannel);
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.notificationChannelUrl, channel.url)).toBe("https://ntfy.sh/t");
    expect(channel.token).toBeNull();

    const addon = dataOf<{ manifestUrl: string; manifestUrlKey: string }>(tx.profileAddon);
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.profileAddonManifestUrl, addon.manifestUrl)).toBe("https://t/manifest.json");
    expect(addon.manifestUrlKey).toBe(
      secretLookupKeyUnder(NEW, SECRET_CONTEXT.profileAddonManifestUrl, "https://t/manifest.json")
    );

    const account = dataOf<{ totpSecret: string; totpPendingSecret: null }>(tx.account);
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.accountTotpSecret, account.totpSecret)).toBe("TOTP");
    expect(account.totpPendingSecret).toBeNull();
  });

  it("encrypts leftover plaintext under the new root", async () => {
    tx.profileConnection.findMany.mockResolvedValue([{ id: "x", provider: "simkl", credentials: "{\"token\":\"t\"}" }]);
    await rotateStoredSecrets(OLD, NEW);
    const data = dataOf<{ credentials: string }>(tx.profileConnection);
    expect(decryptSecretUnder(NEW, SECRET_CONTEXT.profileConnectionCredentials, data.credentials)).toBe("{\"token\":\"t\"}");
  });

  it("writes nothing, and names the credential, when one can't be opened with the old root", async () => {
    tx.traktToken.findMany.mockResolvedValue([
      {
        id: "p1",
        accessToken: under(OLD, SECRET_CONTEXT.traktAccessToken, "access"),
        refreshToken: under(OLD, SECRET_CONTEXT.traktRefreshToken, "refresh"),
      },
    ]);
    tx.notificationChannel.findMany.mockResolvedValue([
      { id: "c1", name: "Gotify", url: under("x".repeat(64), SECRET_CONTEXT.notificationChannelUrl, "u"), token: null },
    ]);

    const error = await rotateStoredSecrets(OLD, NEW).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(RotationError);
    expect((error as InstanceType<typeof RotationError>).unreadable).toEqual(["Notification channel URL (Gotify)"]);
    expect(tx.traktToken.update).not.toHaveBeenCalled();
    expect(tx.notificationChannel.update).not.toHaveBeenCalled();
  });

  it("says --old is wrong when it opens nothing at all", async () => {
    tx.kV.findMany.mockResolvedValue([{ key: "omdb:apiKey", value: under(NEW, kvSecretContext("omdb:apiKey"), "k") }]);
    await expect(rotateStoredSecrets(OLD, NEW)).rejects.toThrow(/not the key they were saved under/);
    expect(tx.kV.update).not.toHaveBeenCalled();
  });
});
