import { beforeEach, describe, expect, it, vi } from "vitest";
import { first } from "./test-fixtures/present.js";

const prismaMock = {
  kV: { create: vi.fn(), deleteMany: vi.fn(), findFirst: vi.fn() },
};

vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const STATE_PREFIX = "trakt:oauth:state:";
const VERIFIER = "v".repeat(43);
const FLOW = JSON.stringify({ owner: "acct-1", codeVerifier: VERIFIER });

describe("trakt OAuth state", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.kV.create.mockResolvedValue({});
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 0 });
  });

  it("mints a 256-bit state and persists it under its own key", async () => {
    const { createOAuthState } = await import("./trakt-oauth-state.js");

    const { state } = await createOAuthState("acct-1");

    expect(state).toMatch(/^[0-9a-f]{64}$/);
    const { data } = first(prismaMock.kV.create.mock.calls, "prismaMock.kV.create call")[0];
    expect(data.key).toBe(`${STATE_PREFIX}${state}`);
    expect(JSON.parse(data.value).owner).toBe("acct-1");
  });

  it("mints a PKCE verifier with it and returns only the S256 challenge", async () => {
    const { createOAuthState, codeChallengeFor } = await import("./trakt-oauth-state.js");

    const { codeChallenge } = await createOAuthState("acct-1");

    const { codeVerifier } = JSON.parse(first(prismaMock.kV.create.mock.calls, "prismaMock.kV.create call")[0].data.value);
    expect(codeVerifier).toMatch(/^[A-Za-z0-9\-._~]{43,128}$/);
    expect(codeChallenge).toBe(codeChallengeFor(codeVerifier));
    expect(codeChallenge).not.toBe(codeVerifier);
  });

  it("derives the challenge as RFC 7636 does", async () => {
    const { codeChallengeFor } = await import("./trakt-oauth-state.js");

    // The worked example from RFC 7636, appendix B.
    expect(codeChallengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe(
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    );
  });

  it("does not reuse a state between calls", async () => {
    const { createOAuthState } = await import("./trakt-oauth-state.js");

    const one = await createOAuthState("acct-1");
    const two = await createOAuthState("acct-1");
    expect(one.state).not.toBe(two.state);
    expect(one.codeChallenge).not.toBe(two.codeChallenge);
  });

  it("sweeps states older than the TTL when a new flow starts", async () => {
    const { createOAuthState, OAUTH_STATE_TTL_MS } = await import("./trakt-oauth-state.js");
    const before = Date.now();

    await createOAuthState("acct-1");

    const sweep = first(prismaMock.kV.deleteMany.mock.calls, "prismaMock.kV.deleteMany call")[0];
    expect(sweep.where.key).toEqual({ startsWith: STATE_PREFIX });
    expect(sweep.where.updatedAt.lt.getTime()).toBeGreaterThanOrEqual(before - OAUTH_STATE_TTL_MS);
    expect(sweep.where.updatedAt.lt.getTime()).toBeLessThanOrEqual(Date.now() - OAUTH_STATE_TTL_MS);
  });

  it("accepts a known, unexpired state, deletes it and says whose it was", async () => {
    prismaMock.kV.findFirst.mockResolvedValue({ value: FLOW });
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 1 });
    const { consumeOAuthState, OAUTH_STATE_TTL_MS } = await import("./trakt-oauth-state.js");
    const state = "a".repeat(64);

    await expect(consumeOAuthState(state)).resolves.toEqual({ owner: "acct-1", codeVerifier: VERIFIER });

    const call = first(prismaMock.kV.deleteMany.mock.calls, "prismaMock.kV.deleteMany call")[0];
    expect(call.where.key).toBe(`${STATE_PREFIX}${state}`);
    expect(call.where.updatedAt.gte.getTime()).toBeLessThanOrEqual(Date.now() - OAUTH_STATE_TTL_MS + 1000);
  });

  it("rejects a state no row matches — the row is gone, unknown or expired", async () => {
    prismaMock.kV.findFirst.mockResolvedValue(null);
    const { consumeOAuthState } = await import("./trakt-oauth-state.js");

    await expect(consumeOAuthState("b".repeat(64))).resolves.toBeNull();
  });

  it("refuses a state stored without a usable PKCE verifier", async () => {
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 1 });
    const { consumeOAuthState } = await import("./trakt-oauth-state.js");

    for (const value of ["acct-1", "", "{}", JSON.stringify({ owner: "acct-1" }), JSON.stringify({ owner: "acct-1", codeVerifier: "short" })]) {
      prismaMock.kV.findFirst.mockResolvedValue({ value });
      await expect(consumeOAuthState("d".repeat(64))).resolves.toBeNull();
    }
  });

  it("refuses a state a concurrent callback consumed first", async () => {
    prismaMock.kV.findFirst.mockResolvedValue({ value: FLOW });
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 0 });
    const { consumeOAuthState } = await import("./trakt-oauth-state.js");

    await expect(consumeOAuthState("c".repeat(64))).resolves.toBeNull();
  });

  it("rejects missing or malformed states without touching the database", async () => {
    const { consumeOAuthState } = await import("./trakt-oauth-state.js");

    for (const candidate of [undefined, null, "", "not-hex", "A".repeat(64), "a".repeat(63), ["a".repeat(64)]]) {
      await expect(consumeOAuthState(candidate)).resolves.toBeNull();
    }
    expect(prismaMock.kV.findFirst).not.toHaveBeenCalled();
    expect(prismaMock.kV.deleteMany).not.toHaveBeenCalled();
  });
});
