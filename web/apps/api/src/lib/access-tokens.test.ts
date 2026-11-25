import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = {
  accessToken: {
    findMany: vi.fn(),
    findUnique: vi.fn(),
    findFirst: vi.fn(),
    count: vi.fn(),
    create: vi.fn(),
    updateMany: vi.fn(),
    deleteMany: vi.fn(),
  },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const { createAccessToken, findAccessToken, MAX_ACCESS_TOKENS_PER_ACCOUNT, revokeAccessToken } = await import(
  "./access-tokens.js"
);
const { hashSecret } = await import("./request-auth.js");

describe("access tokens", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.accessToken.updateMany.mockResolvedValue({ count: 1 });
    prismaMock.accessToken.deleteMany.mockResolvedValue({ count: 1 });
  });

  it("stores only the hash of a new token, and returns the token once", async () => {
    prismaMock.accessToken.count.mockResolvedValue(0);
    prismaMock.accessToken.create.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => ({
      id: "t1",
      createdAt: new Date("2026-11-25T00:00:00Z"),
      lastUsedAt: null,
      ...data,
    }));

    const result = await createAccessToken("a1", { name: "Home Assistant", readOnly: true, expiresInDays: null });

    if (!result.ok) throw new Error("expected a token");
    expect(result.token).toMatch(/^fpat_[A-Za-z0-9_-]{43}$/);
    const stored = prismaMock.accessToken.create.mock.calls[0]![0].data;
    expect(stored.tokenHash).toBe(hashSecret(result.token));
    expect(JSON.stringify(stored)).not.toContain(result.token);
    expect(result.accessToken).toMatchObject({ name: "Home Assistant", readOnly: true, expiresAt: null });
    expect(result.accessToken).not.toHaveProperty("tokenHash");
  });

  it("refuses another once the account is at its limit", async () => {
    prismaMock.accessToken.count.mockResolvedValue(MAX_ACCESS_TOKENS_PER_ACCOUNT);

    expect(await createAccessToken("a1", { name: "x", readOnly: false, expiresInDays: 30 })).toEqual({
      ok: false,
      reason: "limit",
    });
    expect(prismaMock.accessToken.create).not.toHaveBeenCalled();
  });

  it("looks a token up by its hash and notes when it was used", async () => {
    prismaMock.accessToken.findUnique.mockResolvedValue({
      id: "t1",
      accountId: "a1",
      readOnly: false,
      expiresAt: null,
      lastUsedAt: null,
    });

    expect(await findAccessToken("fpat_abc")).toEqual({ id: "t1", accountId: "a1", readOnly: false });
    expect(prismaMock.accessToken.findUnique).toHaveBeenCalledWith(
      expect.objectContaining({ where: { tokenHash: hashSecret("fpat_abc") } })
    );
    expect(prismaMock.accessToken.updateMany).toHaveBeenCalledTimes(1);
  });

  it("doesn't write the last-used time on every request", async () => {
    prismaMock.accessToken.findUnique.mockResolvedValue({
      id: "t1",
      accountId: "a1",
      readOnly: false,
      expiresAt: null,
      lastUsedAt: new Date(),
    });

    await findAccessToken("fpat_abc");

    expect(prismaMock.accessToken.updateMany).not.toHaveBeenCalled();
  });

  it("refuses and removes an expired token", async () => {
    prismaMock.accessToken.findUnique.mockResolvedValue({
      id: "t1",
      accountId: "a1",
      readOnly: false,
      expiresAt: new Date(Date.now() - 1000),
      lastUsedAt: null,
    });

    expect(await findAccessToken("fpat_abc")).toBeNull();
    expect(prismaMock.accessToken.deleteMany).toHaveBeenCalledWith({ where: { id: "t1" } });
  });

  it("only revokes a token of the account asking", async () => {
    prismaMock.accessToken.findFirst.mockResolvedValue(null);

    expect(await revokeAccessToken("a2", "t1")).toBeNull();
    expect(prismaMock.accessToken.findFirst).toHaveBeenCalledWith(
      expect.objectContaining({ where: { id: "t1", accountId: "a2" } })
    );
    expect(prismaMock.accessToken.deleteMany).not.toHaveBeenCalled();
  });
});
