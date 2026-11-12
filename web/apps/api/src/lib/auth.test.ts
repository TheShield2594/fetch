import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyReply, FastifyRequest } from "fastify";

const prismaMock = { device: { findUnique: vi.fn(), update: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

class FakeReply {
  statusCode?: number;
  body?: unknown;
  headers: Record<string, string | string[]> = {};
  code(code: number) {
    this.statusCode = code;
    return this;
  }
  header(name: string, value: string | string[]) {
    this.headers[name] = value;
    return this;
  }
  getHeader(name: string) {
    return this.headers[name];
  }
  send(payload: unknown) {
    this.body = payload;
    return this;
  }
}

const makeRequest = (
  authorization?: string,
  { method = "GET", headers = {} }: { method?: string; headers?: Record<string, string> } = {}
): FastifyRequest =>
  ({
    method,
    protocol: "http",
    headers: { authorization, host: "192.168.1.25:7000", ...headers },
    log: { error: vi.fn() },
  }) as unknown as FastifyRequest;

const findSession = vi.fn();
vi.mock("./sessions.js", () => ({ findSession, SESSION_COOKIE_MAX_AGE_SEC: 2592000 }));

const loadVerifyToken = async () => (await import("./auth.js")).verifyToken;

describe("verifyToken", () => {
  const originalToken = process.env.API_TOKEN;

  afterEach(() => {
    process.env.API_TOKEN = originalToken;
    vi.resetModules();
  });

  it("returns 500 when API_TOKEN is not configured", async () => {
    delete process.env.API_TOKEN;
    vi.resetModules();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest("Bearer anything"), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(500);
  });

  it("returns 401 when no Authorization header is present", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest(undefined), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(401);
    expect(reply.headers["WWW-Authenticate"]).toBe("Bearer");
  });

  it("returns 401 for a malformed Authorization header", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest("secret-token"), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(401);
  });

  it("returns 401 for an incorrect token", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest("Bearer wrong-token"), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(401);
    expect(reply.headers["WWW-Authenticate"]).toBe("Bearer");
  });

  it("lets the request through for the correct token", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    const request = makeRequest("Bearer secret-token");
    await verifyToken(request, reply as unknown as FastifyReply);

    expect(reply.statusCode).toBeUndefined();
    expect(request.auth).toEqual({ kind: "token" });
  });

  it("signs a session token in as its account", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    const session = { kind: "session", sessionId: "s1", accountId: "a1", isAdmin: false };
    findSession.mockResolvedValueOnce(session);
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    const request = makeRequest("Bearer fs1_abc");
    await verifyToken(request, reply as unknown as FastifyReply);

    expect(findSession).toHaveBeenCalledWith("fs1_abc");
    expect(reply.statusCode).toBeUndefined();
    expect(request.auth).toEqual(session);
  });

  it("returns 401 for an unknown or expired session token", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    findSession.mockResolvedValueOnce(null);
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest("Bearer fs1_gone"), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(401);
    expect(reply.headers["WWW-Authenticate"]).toBe("Bearer");
  });

  it("never looks up a session for a token without the session prefix", async () => {
    process.env.API_TOKEN = "secret-token";
    vi.resetModules();
    findSession.mockClear();
    const verifyToken = await loadVerifyToken();
    const reply = new FakeReply();

    await verifyToken(makeRequest("Bearer wrong-token"), reply as unknown as FastifyReply);

    expect(reply.statusCode).toBe(401);
    expect(findSession).not.toHaveBeenCalled();
  });

  describe("the website's session cookie", () => {
    const session = { kind: "session", sessionId: "s1", accountId: "a1", isAdmin: false };
    const cookie = { cookie: "theme=dark; fetch_session=fs1_cookie" };
    const setCookies = (reply: FakeReply) => [reply.headers["set-cookie"] ?? []].flat();

    const load = async () => {
      process.env.API_TOKEN = "secret-token";
      process.env.FETCH_ALLOWED_ORIGINS = "http://192.168.1.25:7002";
      vi.resetModules();
      findSession.mockReset();
      return loadVerifyToken();
    };

    it("signs a read in from the cookie when there is no Authorization header", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest(undefined, { headers: cookie });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(findSession).toHaveBeenCalledWith("fs1_cookie");
      expect(reply.statusCode).toBeUndefined();
      expect(request.auth).toEqual(session);
      expect(request.authVia).toBe("cookie");
      // Nothing to re-issue when the session wasn't renewed.
      expect(setCookies(reply)).toEqual([]);
    });

    it("prefers the __Host- cookie set over HTTPS", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const request = makeRequest(undefined, {
        headers: { cookie: "fetch_session=fs1_plain; __Host-fetch_session=fs1_secure" },
      });

      await verifyToken(request, new FakeReply() as unknown as FastifyReply);

      expect(findSession).toHaveBeenCalledWith("fs1_secure");
    });

    it("re-issues the cookie with a fresh lifetime when the session was renewed", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: true, expiresAt: new Date(Date.now() + 2592000_000) });
      const reply = new FakeReply();

      await verifyToken(makeRequest(undefined, { headers: cookie }), reply as unknown as FastifyReply);

      expect(setCookies(reply)).toEqual([
        expect.stringMatching(/^fetch_session=fs1_cookie; Path=\/; Max-Age=259(1999|2000); HttpOnly; SameSite=Lax$/),
      ]);
    });

    it("never lets the re-issued cookie outlive the session's absolute lifetime", async () => {
      const verifyToken = await load();
      // Renewed near the end of its 90 days: only an hour left, whatever the idle window says.
      findSession.mockResolvedValueOnce({ ...session, renewed: true, expiresAt: new Date(Date.now() + 3600_000) });
      const reply = new FakeReply();

      await verifyToken(makeRequest(undefined, { headers: cookie }), reply as unknown as FastifyReply);

      expect(setCookies(reply)).toEqual([expect.stringMatching(/; Max-Age=(3599|3600); /)]);
    });

    it("lets a bearer header win over a cookie, so TVs and scripts are unaffected", async () => {
      const verifyToken = await load();
      const request = makeRequest("Bearer secret-token", { method: "POST", headers: cookie });

      await verifyToken(request, new FakeReply() as unknown as FastifyReply);

      expect(request.auth).toEqual({ kind: "token" });
      expect(request.authVia).toBe("bearer");
      expect(findSession).not.toHaveBeenCalled();
    });

    it("exempts bearer-authenticated writes from the CSRF check", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest("Bearer fs1_tv", { method: "POST", headers: { origin: "https://evil.example" } });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBeUndefined();
      expect(request.auth).toEqual(session);
    });

    it("never accepts API_TOKEN from a cookie", async () => {
      const verifyToken = await load();
      const reply = new FakeReply();

      await verifyToken(
        makeRequest(undefined, { headers: { cookie: "fetch_session=secret-token" } }),
        reply as unknown as FastifyReply
      );

      expect(reply.statusCode).toBe(401);
      expect(findSession).not.toHaveBeenCalled();
    });

    it("answers a dead cookie 401 and expires it", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce(null);
      const reply = new FakeReply();

      await verifyToken(makeRequest(undefined, { headers: cookie }), reply as unknown as FastifyReply);

      expect(reply.statusCode).toBe(401);
      expect(reply.headers["WWW-Authenticate"]).toBe("Bearer");
      expect(setCookies(reply)).toEqual(
        expect.arrayContaining([expect.stringMatching(/^fetch_session=; Path=\/; Max-Age=0; /)])
      );
    });

    it("accepts a cookie write from the website, with its header and origin", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest(undefined, {
        method: "POST",
        headers: { ...cookie, "x-fetch-client": "web", origin: "http://192.168.1.25:7002" },
      });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBeUndefined();
      expect(request.auth).toEqual(session);
    });

    it("accepts a cookie write from the API's own origin (the same-origin proxy layout)", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest(undefined, {
        method: "DELETE",
        headers: { ...cookie, "x-fetch-client": "web", origin: "http://192.168.1.25:7000" },
      });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBeUndefined();
    });

    it("refuses a cookie write without the website's header (a cross-site form post)", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest(undefined, {
        method: "POST",
        headers: { ...cookie, origin: "http://192.168.1.25:7002" },
      });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBe(403);
      expect(reply.body).toMatchObject({ code: "csrf_rejected" });
      expect(request.auth).toBeUndefined();
    });

    it("refuses a cookie write from an origin that isn't the website", async () => {
      const verifyToken = await load();
      findSession.mockResolvedValueOnce({ ...session, renewed: false });
      const reply = new FakeReply();
      const request = makeRequest(undefined, {
        method: "PATCH",
        headers: { ...cookie, "x-fetch-client": "web", origin: "https://evil.example" },
      });

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBe(403);
      expect(reply.body).toMatchObject({ code: "csrf_rejected" });
    });
  });

  describe("device tokens", () => {
    it("accepts a paired device's token and records which device it was", async () => {
      process.env.API_TOKEN = "secret-token";
      vi.resetModules();
      prismaMock.device.findUnique.mockResolvedValue({ id: "device-1", name: "Living room" });
      prismaMock.device.update.mockResolvedValue({});
      const verifyToken = await loadVerifyToken();
      const reply = new FakeReply();
      const request = makeRequest("Bearer fdt_known");

      await verifyToken(request, reply as unknown as FastifyReply);

      expect(reply.statusCode).toBeUndefined();
      expect(request.deviceId).toBe("device-1");
    });

    it("answers a revoked or unknown device token 401 with the bearer challenge", async () => {
      process.env.API_TOKEN = "secret-token";
      vi.resetModules();
      prismaMock.device.findUnique.mockResolvedValue(null);
      const verifyToken = await loadVerifyToken();
      const reply = new FakeReply();

      await verifyToken(makeRequest("Bearer fdt_revoked"), reply as unknown as FastifyReply);

      expect(reply.statusCode).toBe(401);
      expect(reply.headers["WWW-Authenticate"]).toBe("Bearer");
    });

    it("never looks up a token without the device prefix", async () => {
      process.env.API_TOKEN = "secret-token";
      vi.resetModules();
      prismaMock.device.findUnique.mockClear();
      const verifyToken = await loadVerifyToken();

      await verifyToken(makeRequest("Bearer wrong-token"), new FakeReply() as unknown as FastifyReply);

      expect(prismaMock.device.findUnique).not.toHaveBeenCalled();
    });
  });
});
