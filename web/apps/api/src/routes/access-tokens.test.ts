import { beforeEach, describe, expect, it, vi } from "vitest";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";
import type { AuthPrincipal } from "../lib/request-auth.js";

const tokens = {
  listAccessTokens: vi.fn(),
  createAccessToken: vi.fn(),
  revokeAccessToken: vi.fn(),
  MAX_ACCESS_TOKEN_DAYS: 3650,
  MAX_ACCESS_TOKEN_NAME_LENGTH: 60,
  MAX_ACCESS_TOKENS_PER_ACCOUNT: 25,
};
vi.mock("../lib/access-tokens.js", () => tokens);
const recordAccountEvent = vi.fn();
vi.mock("../lib/account-events.js", () => ({ recordAccountEvent: (...a: unknown[]) => recordAccountEvent(...a) }));

const SESSION: AuthPrincipal = { kind: "session", sessionId: "s1", accountId: "a1", isAdmin: false };
const TOKEN_ID = "11111111-1111-4111-8111-111111111111";
const buildApp = (auth: AuthPrincipal = SESSION) => buildRouteApp(() => import("./access-tokens.js"), {}, auth);

describe("access token routes", () => {
  beforeEach(() => vi.clearAllMocks());

  it("lists the signed-in account's tokens", async () => {
    tokens.listAccessTokens.mockResolvedValue([{ id: TOKEN_ID, name: "Script" }]);

    const response = await (await buildApp()).inject({ method: "GET", url: "/auth/tokens" });

    expect(response.json()).toEqual({ tokens: [{ id: TOKEN_ID, name: "Script" }] });
    expect(tokens.listAccessTokens).toHaveBeenCalledWith("a1");
  });

  it("creates a token, returns it once and records it", async () => {
    tokens.createAccessToken.mockResolvedValue({ ok: true, token: "fpat_x", accessToken: { id: TOKEN_ID, name: "Script" } });

    const response = await (await buildApp()).inject({
      method: "POST",
      url: "/auth/tokens",
      payload: { name: "  Script ", readOnly: true, expiresInDays: 90 },
    });

    expect(response.statusCode).toBe(201);
    expect(response.json().token).toBe("fpat_x");
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(tokens.createAccessToken).toHaveBeenCalledWith("a1", { name: "Script", readOnly: true, expiresInDays: 90 });
    expect(recordAccountEvent).toHaveBeenCalledWith("a1", "access_token_created", expect.objectContaining({ detail: "Script" }));
  });

  it.each([
    [{}],
    [{ name: "x".repeat(61) }],
    [{ name: "ok", readOnly: "yes" }],
    [{ name: "ok", expiresInDays: 0 }],
    [{ name: "ok", expiresInDays: 1.5 }],
  ])("rejects %j", async (payload) => {
    const response = await (await buildApp()).inject({ method: "POST", url: "/auth/tokens", payload });

    expect(response.statusCode).toBe(400);
    expect(tokens.createAccessToken).not.toHaveBeenCalled();
  });

  it("says so when the account is at its limit", async () => {
    tokens.createAccessToken.mockResolvedValue({ ok: false, reason: "limit" });

    const response = await (await buildApp()).inject({ method: "POST", url: "/auth/tokens", payload: { name: "ok" } });

    expect(response.statusCode).toBe(409);
    expect(response.json().code).toBe("access_token_limit");
  });

  it("revokes a token and 404s for one that isn't the account's", async () => {
    tokens.revokeAccessToken.mockResolvedValueOnce({ name: "Script" }).mockResolvedValueOnce(null);
    const app = await buildApp();

    expect((await app.inject({ method: "DELETE", url: `/auth/tokens/${TOKEN_ID}` })).statusCode).toBe(204);
    expect((await app.inject({ method: "DELETE", url: `/auth/tokens/${TOKEN_ID}` })).statusCode).toBe(404);
    expect(recordAccountEvent).toHaveBeenCalledTimes(1);
  });

  it.each<[string, AuthPrincipal]>([
    ["API_TOKEN", { kind: "token" }],
    ["a TV", { kind: "device", deviceId: "d1", accountId: "a1" }],
    ["another access token", { kind: "pat", tokenId: "t1", accountId: "a1", readOnly: false }],
  ])("is closed to %s", async (_label, auth) => {
    const app = await buildApp(auth);

    expect((await app.inject({ method: "GET", url: "/auth/tokens" })).statusCode).toBe(400);
    expect((await app.inject({ method: "POST", url: "/auth/tokens", payload: { name: "ok" } })).statusCode).toBe(400);
    expect(tokens.createAccessToken).not.toHaveBeenCalled();
  });
});
