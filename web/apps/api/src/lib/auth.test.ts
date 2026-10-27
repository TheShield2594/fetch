import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyReply, FastifyRequest } from "fastify";

const prismaMock = { device: { findUnique: vi.fn(), update: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

class FakeReply {
  statusCode?: number;
  body?: unknown;
  headers: Record<string, string> = {};
  code(code: number) {
    this.statusCode = code;
    return this;
  }
  header(name: string, value: string) {
    this.headers[name] = value;
    return this;
  }
  send(payload: unknown) {
    this.body = payload;
    return this;
  }
}

const makeRequest = (authorization?: string): FastifyRequest =>
  ({
    headers: { authorization },
    log: { error: vi.fn() },
  }) as unknown as FastifyRequest;

const findSession = vi.fn();
vi.mock("./sessions.js", () => ({ findSession }));

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
