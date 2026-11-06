import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

// The pre-request check resolves through node:dns/promises; the connection
// resolves through node:dns. Stubbing them separately lets a test answer
// differently on each lookup, the way a rebinding resolver would. HOST stands
// for any name; the connection's lookup always lands on the local test server.
const HOST = "media.example.test";
const preCheckLookup = vi.fn();
vi.mock("node:dns/promises", async (importOriginal) => ({
  ...(await importOriginal<typeof import("node:dns/promises")>()),
  lookup: (...args: unknown[]) => preCheckLookup(...args),
}));
vi.mock("node:dns", async (importOriginal) => ({
  ...(await importOriginal<typeof import("node:dns")>()),
  lookup: (_host: string, _options: unknown, callback: (e: null, a: { address: string; family: number }[]) => void) =>
    callback(null, [{ address: "127.0.0.1", family: 4 }]),
}));

const { BlockedAddressError, PRIVATE_NETWORK_ALLOWED, PUBLIC_ONLY, guardedFetch } = await import("./ssrf.js");

let server: Server;
let port: number;

beforeAll(async () => {
  server = createServer((request, response) => {
    if (request.url === "/redirect") {
      response.writeHead(302, { location: "http://169.254.169.254/" }).end();
      return;
    }
    response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ ok: true }));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  port = (server.address() as AddressInfo).port;
});

afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

beforeEach(() => {
  preCheckLookup.mockReset();
});

describe("guardedFetch", () => {
  it("reaches an allowed server and returns its answer", async () => {
    preCheckLookup.mockResolvedValue([{ address: "127.0.0.1", family: 4 }]);
    const response = await guardedFetch(`http://${HOST}:${port}/`, PRIVATE_NETWORK_ALLOWED);
    expect(response?.status).toBe(200);
    expect(await response?.json()).toEqual({ ok: true });
  });

  it("makes no request for a URL the address check refuses", async () => {
    expect(await guardedFetch("http://169.254.169.254/latest/meta-data", PRIVATE_NETWORK_ALLOWED)).toBeNull();
    expect(await guardedFetch(`http://127.0.0.1:${port}/`, PUBLIC_ONLY)).toBeNull();
    expect(await guardedFetch("file:///etc/passwd", PRIVATE_NETWORK_ALLOWED)).toBeNull();
  });

  it("refuses a connection whose name resolves differently once checked (DNS rebinding)", async () => {
    // The pre-request lookup says public; the connection's own lookup lands on
    // loopback, which a public-only scope may not reach.
    preCheckLookup.mockResolvedValue([{ address: "93.184.215.14", family: 4 }]);
    const error = await guardedFetch(`http://${HOST}:${port}/`, PUBLIC_ONLY).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).cause).toBeInstanceOf(BlockedAddressError);
  });

  it("refuses to follow a redirect", async () => {
    preCheckLookup.mockResolvedValue([{ address: "127.0.0.1", family: 4 }]);
    await expect(guardedFetch(`http://${HOST}:${port}/redirect`, PRIVATE_NETWORK_ALLOWED)).rejects.toThrow();
  });
});
