import { beforeEach, describe, expect, it, vi } from "vitest";
import { createHash } from "node:crypto";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const hashSecret = (value: string) => createHash("sha256").update(value).digest("hex");
const HANDOFF = hashSecret("handoff");

vi.mock("../lib/prisma.js", () => ({ prisma: {} }));

const buildApp = () => buildRouteApp(() => import("./pairing-hub.js"));

describe("pairing hub routes", () => {
  beforeEach(async () => {
    vi.stubEnv("FETCH_PAIRING_HUB_LINK_BASE", "https://watchfetch.tv/");
    const { resetPairingHub } = await import("../lib/pairing-hub.js");
    resetPairingHub();
  });

  it("starts a pairing with a code, a link to the hub's /link page and a poll secret", async () => {
    const app = await buildApp();

    const response = await app.inject({ method: "POST", url: "/hub/pair/start", payload: { name: "Shield", handoffHash: HANDOFF } });

    expect(response.statusCode).toBe(201);
    const body = response.json();
    expect(body.code).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/);
    expect(body.linkUrl).toBe(`https://watchfetch.tv/link?code=${body.code}`);
    expect(body.pollSecret).toEqual(expect.any(String));
    expect(body.intervalSec).toBe(3);
  });

  it("refuses a start without a SHA-256 handoff hash", async () => {
    const app = await buildApp();

    const response = await app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: "plain-secret" } });

    expect(response.statusCode).toBe(400);
  });

  it("is pending until a website claims the code, then tells the TV where the website is", async () => {
    const app = await buildApp();
    const started = (await app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: HANDOFF } })).json();
    const poll = () => app.inject({ method: "GET", url: "/hub/pair/poll", headers: { "x-pairing-secret": started.pollSecret } });

    expect((await poll()).statusCode).toBe(202);

    const claim = await app.inject({
      method: "POST",
      url: "/hub/pair/claim",
      payload: { code: started.code.toLowerCase(), api: "https://fetch.example/api", web: "https://fetch.example" },
    });
    expect(claim.statusCode).toBe(200);
    // The website gets the hash to store, never a secret the hub could use.
    expect(claim.json()).toEqual({ handoffHash: HANDOFF, name: "TV" });

    const linked = await poll();
    expect(linked.statusCode).toBe(200);
    expect(linked.json()).toEqual({ status: "linked", api: "https://fetch.example/api", web: "https://fetch.example" });
  });

  it("lets one website claim a code, and answers it again on a retry", async () => {
    const app = await buildApp();
    const started = (await app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: HANDOFF } })).json();
    const claim = (api: string) =>
      app.inject({ method: "POST", url: "/hub/pair/claim", payload: { code: started.code, api } });

    expect((await claim("https://a.example/api")).statusCode).toBe(200);
    expect((await claim("https://a.example/api")).json()).toEqual({ handoffHash: HANDOFF, name: "TV" });
    expect((await claim("https://b.example/api")).statusCode).toBe(409);
  });

  it("forgets expired codes on the sweep, without another start", async () => {
    const app = await buildApp();
    const started = (await app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: HANDOFF } })).json();
    const { sweepPairingHub, HUB_CODE_TTL_MS } = await import("../lib/pairing-hub.js");

    sweepPairingHub(Date.now() + HUB_CODE_TTL_MS + 1);

    const claim = await app.inject({ method: "POST", url: "/hub/pair/claim", payload: { code: started.code, api: "https://a.example" } });
    expect(claim.statusCode).toBe(404);
  });

  it("answers 404 for an unknown code and an unknown poll secret", async () => {
    const app = await buildApp();

    const claim = await app.inject({ method: "POST", url: "/hub/pair/claim", payload: { code: "ZZZZ-ZZZZ", api: "https://a.example" } });
    const poll = await app.inject({ method: "GET", url: "/hub/pair/poll", headers: { "x-pairing-secret": "nope" } });

    expect(claim.statusCode).toBe(404);
    expect(poll.statusCode).toBe(404);
  });

  it("refuses a claim whose address isn't an http(s) URL", async () => {
    const app = await buildApp();
    const started = (await app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: HANDOFF } })).json();

    for (const api of ["javascript:alert(1)", "https://user:pass@a.example", "not a url"]) {
      const response = await app.inject({ method: "POST", url: "/hub/pair/claim", payload: { code: started.code, api } });
      expect(response.statusCode).toBe(400);
    }
  });

  it("caps how many unclaimed codes one address can hold", async () => {
    const app = await buildApp();
    const start = () => app.inject({ method: "POST", url: "/hub/pair/start", payload: { handoffHash: HANDOFF } });

    for (let i = 0; i < 6; i += 1) expect((await start()).statusCode).toBe(201);
    expect((await start()).statusCode).toBe(429);
  });
});

describe("pairingHubUrl", () => {
  it("defaults to watchfetch.tv, and can be pointed elsewhere or turned off", async () => {
    const { pairingHubUrl } = await import("../lib/pairing-hub.js");
    delete process.env.FETCH_PAIRING_HUB_URL;
    expect(pairingHubUrl()).toBe("https://watchfetch.tv/api");
    vi.stubEnv("FETCH_PAIRING_HUB_URL", "https://hub.example/api/");
    expect(pairingHubUrl()).toBe("https://hub.example/api");
    vi.stubEnv("FETCH_PAIRING_HUB_URL", "off");
    expect(pairingHubUrl()).toBeNull();
  });
});
