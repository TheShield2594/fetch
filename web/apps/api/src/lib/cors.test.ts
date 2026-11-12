import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyReply, FastifyRequest } from "fastify";

class FakeReply {
  headers: Record<string, string> = {};
  header(name: string, value: string) {
    this.headers[name] = value;
    return this;
  }
  getHeader(name: string) {
    return this.headers[name];
  }
}

const makeRequest = (origin?: string, method = "GET", url = "/watchlist"): FastifyRequest =>
  ({ headers: { origin }, method, url }) as unknown as FastifyRequest;

const loadCors = async () => import("./cors.js");

describe("cors", () => {
  const originalNodeEnv = process.env.NODE_ENV;
  const originalAllowedOrigins = process.env.FETCH_ALLOWED_ORIGINS;

  afterEach(() => {
    process.env.NODE_ENV = originalNodeEnv;
    process.env.FETCH_ALLOWED_ORIGINS = originalAllowedOrigins;
    vi.resetModules();
  });

  describe("development mode", () => {
    it("allows any origin, including none", async () => {
      process.env.NODE_ENV = "development";
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();
      const reply = new FakeReply();

      applyCorsHeaders(makeRequest("https://anything.example"), reply as unknown as FastifyReply);
      expect(reply.headers["Access-Control-Allow-Origin"]).toBe("https://anything.example");
      expect(reply.headers["Vary"]).toBe("Origin");
    });
  });

  describe("production mode", () => {
    it("only allows origins listed in FETCH_ALLOWED_ORIGINS", async () => {
      process.env.NODE_ENV = "production";
      process.env.FETCH_ALLOWED_ORIGINS = "https://app.example.com, https://other.example.com";
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();

      const otherReply = new FakeReply();
      applyCorsHeaders(makeRequest("https://other.example.com"), otherReply as unknown as FastifyReply);
      expect(otherReply.headers["Access-Control-Allow-Origin"]).toBe("https://other.example.com");

      const allowedReply = new FakeReply();
      applyCorsHeaders(makeRequest("https://app.example.com"), allowedReply as unknown as FastifyReply);
      expect(allowedReply.headers["Access-Control-Allow-Origin"]).toBe("https://app.example.com");
      // Without this a cache can hand one origin's allow-header to another.
      expect(allowedReply.headers["Vary"]).toBe("Origin");

      const blockedReply = new FakeReply();
      applyCorsHeaders(makeRequest("https://evil.example.com"), blockedReply as unknown as FastifyReply);
      expect(blockedReply.headers["Access-Control-Allow-Origin"]).toBeUndefined();
    });

    it("allows credentials for a listed origin only, and never with a wildcard", async () => {
      process.env.NODE_ENV = "production";
      process.env.FETCH_ALLOWED_ORIGINS = "https://app.example.com";
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();

      const allowed = new FakeReply();
      applyCorsHeaders(makeRequest("https://app.example.com", "OPTIONS"), allowed as unknown as FastifyReply);
      expect(allowed.headers["Access-Control-Allow-Credentials"]).toBe("true");
      expect(allowed.headers["Access-Control-Allow-Origin"]).toBe("https://app.example.com");
      // The website's CSRF and cache-partition headers survive a preflight.
      const headers = (allowed.headers["Access-Control-Allow-Headers"] ?? "").split(",");
      expect(headers).toEqual(expect.arrayContaining(["X-Fetch-Client", "X-Fetch-Session"]));

      const blocked = new FakeReply();
      applyCorsHeaders(makeRequest("https://evil.example.com", "OPTIONS"), blocked as unknown as FastifyReply);
      expect(blocked.headers["Access-Control-Allow-Credentials"]).toBeUndefined();
    });

    it("does not allow any origin when FETCH_ALLOWED_ORIGINS is unset", async () => {
      process.env.NODE_ENV = "production";
      delete process.env.FETCH_ALLOWED_ORIGINS;
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();

      const reply = new FakeReply();
      applyCorsHeaders(makeRequest("https://app.example.com"), reply as unknown as FastifyReply);
      expect(reply.headers["Access-Control-Allow-Origin"]).toBeUndefined();
    });

    it("allows every method the API routes use, so a preflight never blocks one", async () => {
      process.env.NODE_ENV = "production";
      process.env.FETCH_ALLOWED_ORIGINS = "https://app.example.com";
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();
      const reply = new FakeReply();

      applyCorsHeaders(makeRequest("https://app.example.com", "OPTIONS"), reply as unknown as FastifyReply);
      // PUT /playback is what the web app saves resume points with.
      const allowed = (reply.headers["Access-Control-Allow-Methods"] ?? "").split(",");
      expect(allowed).toEqual(expect.arrayContaining(["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]));
    });

    // Starting Trakt sign-in sets a cookie the callback checks, and a browser
    // only keeps a cross-origin cookie from a credentialed request. The session
    // cookie means every other route is credentialed too.
    it("allows credentials on the Trakt authorize route, as on every other", async () => {
      process.env.NODE_ENV = "production";
      process.env.FETCH_ALLOWED_ORIGINS = "https://app.example.com";
      vi.resetModules();
      const { applyCorsHeaders } = await loadCors();

      for (const method of ["OPTIONS", "GET"]) {
        const reply = new FakeReply();
        applyCorsHeaders(
          makeRequest("https://app.example.com", method, "/trakt/oauth/authorize"),
          reply as unknown as FastifyReply
        );
        expect(reply.headers["Access-Control-Allow-Credentials"]).toBe("true");
      }

      const other = new FakeReply();
      applyCorsHeaders(makeRequest("https://app.example.com"), other as unknown as FastifyReply);
      expect(other.headers["Access-Control-Allow-Credentials"]).toBe("true");

      const blocked = new FakeReply();
      applyCorsHeaders(
        makeRequest("https://evil.example.com", "GET", "/trakt/oauth/authorize"),
        blocked as unknown as FastifyReply
      );
      expect(blocked.headers["Access-Control-Allow-Credentials"]).toBeUndefined();
    });
  });
});
