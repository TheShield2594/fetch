import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyReply, FastifyRequest } from "fastify";

class FakeReply {
  headers: Record<string, string | string[]> = {};
  header(name: string, value: string | string[]) {
    this.headers[name] = value;
    return this;
  }
  getHeader(name: string) {
    return this.headers[name];
  }
}

const makeRequest = ({
  method = "GET",
  protocol = "http",
  headers = {},
}: { method?: string; protocol?: string; headers?: Record<string, string> } = {}): FastifyRequest =>
  ({ method, protocol, headers: { host: "fetch.example.com", ...headers } }) as unknown as FastifyRequest;

const load = async (env: Record<string, string | undefined> = {}) => {
  vi.resetModules();
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  return import("./session-cookie.js");
};

const setCookies = (reply: FakeReply) => [reply.headers["set-cookie"] ?? []].flat();

describe("session cookies", () => {
  const original = { ...process.env };
  afterEach(() => {
    process.env = { ...original };
  });

  it("parses a Cookie header, decoding values and keeping the first of a duplicate", async () => {
    const { parseCookies } = await load();
    expect(parseCookies("a=1; b=hello%20world; a=2; junk; c=\"q\"")).toEqual({ a: "1", b: "hello world", c: "q" });
    expect(parseCookies(undefined)).toEqual({});
  });

  it("sets a plain, non-Secure cookie over plain HTTP so a LAN install can sign in", async () => {
    const { setSessionCookie } = await load();
    const reply = new FakeReply();

    setSessionCookie(makeRequest(), reply as unknown as FastifyReply, "fs1_x", 60);

    expect(setCookies(reply)).toEqual(["fetch_session=fs1_x; Path=/; Max-Age=60; HttpOnly; SameSite=Lax"]);
  });

  it.each([
    ["the socket", { protocol: "https" }],
    ["a proxy's X-Forwarded-Proto", { headers: { "x-forwarded-proto": "https" } }],
    ["an https page origin", { headers: { origin: "https://fetch.example.com" } }],
  ])("sets a Secure __Host- cookie when HTTPS is signalled by %s", async (_label, request) => {
    const { setSessionCookie } = await load();
    const reply = new FakeReply();

    setSessionCookie(makeRequest(request), reply as unknown as FastifyReply, "fs1_x", 60);

    expect(setCookies(reply)).toEqual([
      "__Host-fetch_session=fs1_x; Path=/; Max-Age=60; HttpOnly; SameSite=Lax; Secure",
    ]);
  });

  it("expires both cookies, in both spellings over HTTPS, without dropping other Set-Cookies", async () => {
    const { clearAuthCookies } = await load();
    const reply = new FakeReply();
    reply.header("set-cookie", "other=1");

    clearAuthCookies(makeRequest({ protocol: "https" }), reply as unknown as FastifyReply);

    const cookies = setCookies(reply);
    expect(cookies[0]).toBe("other=1");
    expect(cookies.map((c) => c.split("=")[0])).toEqual([
      "other",
      "fetch_session",
      "__Host-fetch_session",
      "fetch_profile",
      "__Host-fetch_profile",
    ]);
    for (const cookie of cookies.slice(1)) expect(cookie).toMatch(/Max-Age=0; Expires=Thu, 01 Jan 1970/);
  });

  describe("CSRF check", () => {
    it("lets reads through without any header", async () => {
      const { passesCsrfCheck } = await load();
      expect(passesCsrfCheck(makeRequest({ method: "GET" }))).toBe(true);
      expect(passesCsrfCheck(makeRequest({ method: "HEAD" }))).toBe(true);
    });

    it("requires the website's header on a write", async () => {
      const { passesCsrfCheck } = await load();
      expect(passesCsrfCheck(makeRequest({ method: "POST" }))).toBe(false);
      expect(passesCsrfCheck(makeRequest({ method: "POST", headers: { "x-fetch-client": "web" } }))).toBe(true);
    });

    it("accepts FETCH_WEB_PUBLIC's origin, a CORS-allowed one, and the API's own host", async () => {
      const { passesCsrfCheck } = await load({
        FETCH_WEB_PUBLIC: "http://192.168.1.25:7002/",
        FETCH_ALLOWED_ORIGINS: "https://app.example.com",
      });
      const write = (origin: string) =>
        passesCsrfCheck(makeRequest({ method: "PUT", headers: { "x-fetch-client": "web", origin } }));

      expect(write("http://192.168.1.25:7002")).toBe(true);
      expect(write("https://app.example.com")).toBe(true);
      expect(write("https://fetch.example.com")).toBe(true);
      expect(write("https://evil.example")).toBe(false);
      expect(write("null")).toBe(false);
    });
  });
});
