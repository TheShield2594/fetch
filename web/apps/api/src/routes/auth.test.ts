import { beforeEach, describe, expect, it, vi } from "vitest";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

// Sign-in against a real database is auth.int.test.ts. This is only about how
// much hashing an attempt costs, which is what its timing gives away (#545).

const prismaMock = {
  account: { findUnique: vi.fn() },
  session: { create: vi.fn() },
  kV: { findUnique: vi.fn(), upsert: vi.fn(), deleteMany: vi.fn() },
  $transaction: vi.fn(),
  $executeRaw: vi.fn(),
};
vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

const pinHashMock = { pinMatches: vi.fn() };
vi.mock("../lib/pin-hash.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lib/pin-hash.js")>()),
  hashPin: async (value: string) => `hash-of:${value}`,
  ...pinHashMock,
}));

const ACCOUNT = { id: "acc-1", username: "owner", isAdmin: true, passwordHash: "hash-of:correct-horse" };

const login = async (payload: object) => {
  const app = await buildRouteApp(() => import("./auth.js"));
  return app.inject({ method: "POST", url: "/auth/login", payload });
};

describe("POST /auth/login", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.account.findUnique.mockImplementation(async ({ where }: { where: { username: string } }) =>
      where.username === ACCOUNT.username ? ACCOUNT : null
    );
    prismaMock.kV.findUnique.mockResolvedValue(null);
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 0 });
    prismaMock.session.create.mockResolvedValue({});
    prismaMock.$transaction.mockImplementation(async (callback: (tx: typeof prismaMock) => unknown) => callback(prismaMock));
    pinHashMock.pinMatches.mockImplementation(async (value: string, hash: string) => hash === `hash-of:${value}`);
  });

  const attempts: Array<[string, object]> = [
    ["a known username and a wrong password", { username: "owner", password: "wrong-wrong" }],
    ["a known username and no password", { username: "owner", password: "" }],
    ["a known username and an over-long password", { username: "owner", password: "x".repeat(300) }],
    ["a known username and a password that isn't text", { username: "owner", password: 12345678 }],
    ["an unknown username and a wrong password", { username: "nobody", password: "wrong-wrong" }],
    ["an unknown username and no password", { username: "nobody", password: "" }],
    ["a username that can't exist", { username: "!!", password: "wrong-wrong" }],
  ];

  it.each(attempts)("compares exactly one hash for %s", async (_label, payload) => {
    const response = await login(payload);

    expect(response.statusCode).toBe(401);
    expect(pinHashMock.pinMatches).toHaveBeenCalledTimes(1);
  });

  it("compares a known username against its own hash, even with no password", async () => {
    await login({ username: "owner", password: "" });

    expect(pinHashMock.pinMatches).toHaveBeenCalledWith("x", ACCOUNT.passwordHash);
  });

  it("never signs in on the stand-in password an empty one is compared as", async () => {
    prismaMock.account.findUnique.mockResolvedValue({ ...ACCOUNT, passwordHash: "hash-of:x" });

    const response = await login({ username: "owner", password: "" });

    expect(response.statusCode).toBe(401);
  });

  it("signs in with the right password after one comparison", async () => {
    const response = await login({ username: "owner", password: "correct-horse" });

    expect(response.statusCode).toBe(200);
    expect(response.json().account).toMatchObject({ username: "owner" });
    expect(pinHashMock.pinMatches).toHaveBeenCalledTimes(1);
  });
});
