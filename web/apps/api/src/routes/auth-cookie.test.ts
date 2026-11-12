import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Fastify, { type FastifyInstance, type InjectOptions, type LightMyRequestResponse } from "fastify";
import { createHash } from "node:crypto";

// Signing in with a cookie, end to end through the real auth gate and the real
// session lookup, with Prisma mocked underneath. The database-backed behaviour
// of accounts and invites is in auth.int.test.ts; this is about how the
// credential travels: an HttpOnly cookie for the website, a bearer token for
// everything else, and the CSRF check on cookie-authenticated writes.

const API_TOKEN = "unit-test-api-token";
const WEB_ORIGIN = "http://192.168.1.25:7002";

const ACCOUNT = { id: "acc-1", username: "ben", isAdmin: false, passwordHash: "", mustChangePassword: false };
const SESSION_ID = "sess-1";

const sha256 = (value: string) => createHash("sha256").update(value).digest("hex");

/** Session rows by token hash, which is how sessions.ts looks them up. */
const sessions = new Map<string, { id: string; createdAt: Date; expiresAt: Date; lastUsedAt: Date }>();

const prismaMock = {
  account: { findUnique: vi.fn(), count: vi.fn() },
  profile: { findUnique: vi.fn() },
  session: {
    create: vi.fn(async ({ data }: { data: { tokenHash: string; expiresAt: Date } }) => {
      sessions.set(data.tokenHash, {
        id: SESSION_ID,
        createdAt: new Date(),
        expiresAt: data.expiresAt,
        lastUsedAt: new Date(),
      });
      return { id: SESSION_ID };
    }),
    findUnique: vi.fn(async ({ where }: { where: { tokenHash: string } }) => {
      const row = sessions.get(where.tokenHash);
      return row
        ? { ...row, account: { id: ACCOUNT.id, isAdmin: ACCOUNT.isAdmin, mustChangePassword: ACCOUNT.mustChangePassword } }
        : null;
    }),
    updateMany: vi.fn(async () => ({ count: 1 })),
    deleteMany: vi.fn(async ({ where }: { where: { id: string } }) => {
      for (const [hash, row] of sessions) if (row.id === where.id) sessions.delete(hash);
      return { count: 1 };
    }),
  },
  kV: { findUnique: vi.fn(async () => null), upsert: vi.fn(), deleteMany: vi.fn() },
  accountEvent: { create: vi.fn(async () => ({})) },
};

vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));

let app: FastifyInstance;

const buildApp = async () => {
  vi.resetModules();
  process.env.API_TOKEN = API_TOKEN;
  process.env.FETCH_ALLOWED_ORIGINS = WEB_ORIGIN;
  const [{ registerAuthGate }, { default: authRoutes }, { hashPin }] = await Promise.all([
    import("../lib/auth-gate.js"),
    import("./auth.js"),
    import("../lib/pin-hash.js"),
  ]);
  ACCOUNT.passwordHash = await hashPin("correct horse");
  prismaMock.account.findUnique.mockImplementation(async ({ where }: { where: { id?: string; username?: string } }) =>
    where.id === ACCOUNT.id || where.username === ACCOUNT.username ? ACCOUNT : null
  );

  const instance = Fastify({ logger: false });
  registerAuthGate(instance);
  await instance.register(authRoutes);
  await instance.ready();
  return instance;
};

beforeEach(async () => {
  sessions.clear();
  ACCOUNT.mustChangePassword = false;
  vi.clearAllMocks();
  app = await buildApp();
});

afterEach(async () => {
  await app.close();
});

const web = { "x-fetch-client": "web", origin: WEB_ORIGIN };

const setCookies = (response: LightMyRequestResponse): string[] => {
  const header = response.headers["set-cookie"];
  return header === undefined ? [] : [header].flat().map(String);
};

const sessionCookieOf = (response: LightMyRequestResponse): string | undefined =>
  setCookies(response).find((cookie) => cookie.startsWith("fetch_session="));

const login = (headers: Record<string, string> = {}) =>
  app.inject({
    method: "POST",
    url: "/auth/login",
    headers,
    payload: { username: "ben", password: "correct horse" },
  });

/** The session token inside a Set-Cookie line. */
const tokenIn = (setCookie: string) => decodeURIComponent(setCookie.split(";")[0]?.split("=")[1] ?? "");

const withCookie = (token: string, options: InjectOptions): Promise<LightMyRequestResponse> =>
  app.inject({ ...options, headers: { cookie: `fetch_session=${token}`, ...options.headers } });

describe("signing in from the website", () => {
  it("sets an HttpOnly cookie and keeps the token out of the body", async () => {
    const response = await login(web);

    expect(response.statusCode).toBe(200);
    const body = response.json();
    expect(body).not.toHaveProperty("token");
    expect(body).toMatchObject({ account: { id: ACCOUNT.id, username: "ben" }, sessionId: SESSION_ID });

    const cookie = sessionCookieOf(response);
    expect(cookie).toMatch(/^fetch_session=fs1_[^;]+; Path=\/; Max-Age=2592000; HttpOnly; SameSite=Lax$/);
    // A plain-HTTP LAN install: no Secure, or the browser would drop it.
    expect(cookie).not.toContain("Secure");
    // A new account's sign-in drops whatever profile cookie the browser had.
    expect(setCookies(response)).toEqual(expect.arrayContaining([expect.stringMatching(/^fetch_profile=; .*Max-Age=0/)]));
  });

  it("marks the cookie Secure (and __Host-) behind a TLS proxy", async () => {
    const response = await login({ ...web, origin: "https://fetch.example.com", "x-forwarded-proto": "https" });

    expect(setCookies(response)).toEqual(
      expect.arrayContaining([expect.stringMatching(/^__Host-fetch_session=fs1_[^;]+; .*HttpOnly; SameSite=Lax; Secure$/)])
    );
  });

  it("still hands other clients a bearer token, with no cookie", async () => {
    const response = await login();

    expect(response.statusCode).toBe(200);
    expect(response.json().token).toMatch(/^fs1_/);
    expect(sessionCookieOf(response)).toBeUndefined();
  });
});

describe("a cookie-authenticated browser", () => {
  const signIn = async () => tokenIn(sessionCookieOf(await login(web)) ?? "");

  it("is recognised from the cookie alone", async () => {
    const token = await signIn();

    const response = await withCookie(token, { method: "GET", url: "/auth/me" });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ account: { username: "ben" }, sessionId: SESSION_ID });
  });

  it("is refused a write that lacks the website's header", async () => {
    const token = await signIn();

    const response = await withCookie(token, {
      method: "POST",
      url: "/auth/logout",
      headers: { origin: WEB_ORIGIN },
    });

    expect(response.statusCode).toBe(403);
    expect(response.json()).toMatchObject({ code: "csrf_rejected" });
    // Refused before the handler: the session is still there.
    expect(sessions.size).toBe(1);
  });

  it("is refused a write from another origin even with the header", async () => {
    const token = await signIn();

    const response = await withCookie(token, {
      method: "POST",
      url: "/auth/logout",
      headers: { "x-fetch-client": "web", origin: "https://evil.example" },
    });

    expect(response.statusCode).toBe(403);
    expect(sessions.size).toBe(1);
  });

  it("signs out: the session is deleted server-side and the cookies are expired", async () => {
    const token = await signIn();

    const response = await withCookie(token, { method: "POST", url: "/auth/logout", headers: web });

    expect(response.statusCode).toBe(204);
    expect(sessions.size).toBe(0);
    expect(setCookies(response)).toEqual(
      expect.arrayContaining([
        expect.stringMatching(/^fetch_session=; Path=\/; Max-Age=0; /),
        expect.stringMatching(/^fetch_profile=; Path=\/; Max-Age=0; /),
      ])
    );

    // And the old cookie no longer works — answered with the bearer challenge
    // the web client treats as "signed out", and expired again.
    const after = await withCookie(token, { method: "GET", url: "/auth/me" });
    expect(after.statusCode).toBe(401);
    expect(after.headers["www-authenticate"]).toBe("Bearer");
    expect(sessionCookieOf(after)).toMatch(/Max-Age=0/);
  });
});

describe("a password someone else set", () => {
  it("holds a cookie session to changing it, as it does a bearer one", async () => {
    const token = tokenIn(sessionCookieOf(await login(web)) ?? "");
    ACCOUNT.mustChangePassword = true;

    const blocked = await withCookie(token, { method: "GET", url: "/auth/invites" });
    expect(blocked.statusCode).toBe(403);
    expect(blocked.json()).toMatchObject({ code: "password_change_required" });

    const me = await withCookie(token, { method: "GET", url: "/auth/me" });
    expect(me.statusCode).toBe(200);

    const bearer = await app.inject({ method: "GET", url: "/auth/invites", headers: { authorization: `Bearer ${token}` } });
    expect(bearer.statusCode).toBe(403);
  });

  it("still lets a stored pre-cookie token move into the cookie, so the browser can reach the change screen", async () => {
    const token = (await login()).json().token as string;
    ACCOUNT.mustChangePassword = true;

    const response = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: `Bearer ${token}` },
    });

    expect(response.statusCode).toBe(200);
    expect(sessionCookieOf(response)).toBeDefined();
  });
});

describe("bearer tokens", () => {
  it("still work for the TV and other API clients, with no CSRF header", async () => {
    const token = (await login()).json().token as string;

    const me = await app.inject({ method: "GET", url: "/auth/me", headers: { authorization: `Bearer ${token}` } });
    expect(me.statusCode).toBe(200);
    expect(me.json()).toMatchObject({ account: { username: "ben" } });

    const logout = await app.inject({
      method: "POST",
      url: "/auth/logout",
      headers: { authorization: `Bearer ${token}`, origin: "https://anywhere.example" },
    });
    expect(logout.statusCode).toBe(204);
    expect(sessions.size).toBe(0);
  });

  it("accepts API_TOKEN", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/auth/me",
      headers: { authorization: `Bearer ${API_TOKEN}` },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ account: null, isAdmin: true });
  });
});

describe("moving a pre-cookie session into the cookie", () => {
  it("sets the cookie for the same session from the bearer token", async () => {
    const token = (await login()).json().token as string;

    const response = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: `Bearer ${token}` },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ sessionId: SESSION_ID, account: { username: "ben" } });
    // Not re-issued: the same session, so its expiry and revocation are unchanged.
    expect(tokenIn(sessionCookieOf(response) ?? "")).toBe(token);
    expect(sessions.size).toBe(1);
    expect(sessions.has(sha256(token))).toBe(true);
  });

  it("moves a still-valid PIN profile token into its cookie too", async () => {
    const token = (await login()).json().token as string;
    const { mintProfileToken } = await import("../lib/profile-token.js");
    const profileId = "11111111-1111-4111-8111-111111111111";
    const pinHash = "scrypt$stored";
    prismaMock.profile.findUnique.mockResolvedValue({ id: profileId, pinHash, accountId: ACCOUNT.id });
    const profileToken = mintProfileToken(profileId, pinHash);

    const response = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: `Bearer ${token}`, "x-profile-id": profileId, "x-profile-token": profileToken },
    });

    expect(setCookies(response)).toEqual(
      expect.arrayContaining([`fetch_profile=${encodeURIComponent(profileToken)}; Path=/; Max-Age=43200; HttpOnly; SameSite=Lax`])
    );

    const forged = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: `Bearer ${token}`, "x-profile-id": profileId, "x-profile-token": "v1.9999999999999.bad" },
    });
    expect(setCookies(forged).some((cookie) => cookie.startsWith("fetch_profile="))).toBe(false);

    // Nor for another account's profile, or once the PIN has changed.
    for (const row of [
      { id: profileId, pinHash, accountId: "someone-else" },
      { id: profileId, pinHash: "scrypt$changed", accountId: ACCOUNT.id },
    ]) {
      prismaMock.profile.findUnique.mockResolvedValue(row);
      const refused = await app.inject({
        method: "POST",
        url: "/auth/session",
        headers: { ...web, authorization: `Bearer ${token}`, "x-profile-id": profileId, "x-profile-token": profileToken },
      });
      expect(setCookies(refused).some((cookie) => cookie.startsWith("fetch_profile="))).toBe(false);
    }
  });

  it("refuses a dead token", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: "Bearer fs1_revoked" },
    });

    expect(response.statusCode).toBe(401);
    expect(sessionCookieOf(response)).toBeUndefined();
  });

  it("refuses API_TOKEN, which is not a session", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/auth/session",
      headers: { ...web, authorization: `Bearer ${API_TOKEN}` },
    });

    expect(response.statusCode).toBe(400);
    expect(sessionCookieOf(response)).toBeUndefined();
  });
});
