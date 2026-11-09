import { describe, expect, it, vi } from "vitest";
import { type GuardedRequest, type OutboundScope, PRIVATE_NETWORK_ALLOWED, PUBLIC_ONLY } from "./ssrf.js";
import {
  configureUrlFor,
  fetchManifest,
  MANIFEST_BLOCKED_REASON,
  MANIFEST_REJECTED_REASON,
  MANIFEST_UNREACHABLE_REASON,
  manifestUrlForLog,
  normalizeManifestUrl,
  parseManifest,
  readCachedManifest,
} from "./addon-manifest.js";

const MANIFEST = {
  id: "com.example.addon",
  name: "Example",
  version: "1.2.3",
  description: "Streams things",
  logo: "https://example.com/logo.png",
  types: ["movie", "series", "movie"],
  resources: ["stream", { name: "catalog", types: ["movie"] }, { name: "meta" }],
  catalogs: [
    { type: "movie", id: "top", name: "Top movies" },
    { type: "series", id: "new" },
    { type: "movie" },
  ],
  behaviorHints: { configurable: true },
};

describe("normalizeManifestUrl", () => {
  it("accepts an https manifest URL and drops the fragment", () => {
    expect(normalizeManifestUrl(" https://addon.example/abc/manifest.json#x ")).toBe(
      "https://addon.example/abc/manifest.json"
    );
  });

  it("turns stremio:// into https://", () => {
    expect(normalizeManifestUrl("stremio://addon.example/manifest.json")).toBe("https://addon.example/manifest.json");
  });

  it("rejects anything that isn't a manifest address", () => {
    expect(normalizeManifestUrl("https://addon.example/")).toBeNull();
    expect(normalizeManifestUrl("ftp://addon.example/manifest.json")).toBeNull();
    expect(normalizeManifestUrl("not a url")).toBeNull();
  });
});

describe("manifestUrlForLog", () => {
  it("keeps the host and drops the configured path and query", () => {
    const logged = manifestUrlForLog("https://addon.example/debrid=SECRETKEY/manifest.json?token=abc");
    expect(logged).toBe("https://addon.example/…/manifest.json");
    expect(logged).not.toContain("SECRET");
    expect(logged).not.toContain("abc");
  });
});

describe("configureUrlFor", () => {
  it("points at the addon's configure page", () => {
    expect(configureUrlFor("https://addon.example/cfg/manifest.json")).toBe("https://addon.example/cfg/configure");
  });
});

describe("parseManifest", () => {
  it("keeps what the website reads and normalizes resources and catalogs", () => {
    const result = parseManifest(MANIFEST);
    expect(result).toEqual({
      ok: true,
      manifest: {
        id: "com.example.addon",
        name: "Example",
        version: "1.2.3",
        description: "Streams things",
        logo: "https://example.com/logo.png",
        types: ["movie", "series"],
        resources: ["stream", "catalog", "meta"],
        catalogs: [
          { type: "movie", id: "top", name: "Top movies" },
          { type: "series", id: "new", name: "new" },
        ],
        configurable: true,
        configurationRequired: false,
      },
    });
  });

  it("explains what is missing", () => {
    expect(parseManifest([])).toMatchObject({ ok: false, reason: expect.stringContaining("JSON object") });
    expect(parseManifest({ name: "x", version: "1", resources: [] })).toMatchObject({
      ok: false,
      reason: expect.stringContaining("id, name or version"),
    });
    expect(parseManifest({ id: "a", name: "x", version: "1" })).toMatchObject({
      ok: false,
      reason: expect.stringContaining("resources"),
    });
  });

  it("round-trips through what is stored", () => {
    const result = parseManifest(MANIFEST);
    if (!result.ok) throw new Error("expected a manifest");
    expect(readCachedManifest(JSON.parse(JSON.stringify(result.manifest)))).toEqual(result.manifest);
    expect(readCachedManifest(null)).toBeNull();
  });
});

describe("fetchManifest", () => {
  const URL_CFG = "https://addon.example/cfg/manifest.json";

  const fetcherFor = (response: Response | Error | null) =>
    vi.fn<(url: string, scope: OutboundScope, init: GuardedRequest) => Promise<Response | null>>(async () => {
      if (response instanceof Error) throw response;
      return response;
    });

  it("fetches and parses a manifest under the scope it is given", async () => {
    const fetcher = fetcherFor(new Response(JSON.stringify(MANIFEST), { status: 200 }));
    const result = await fetchManifest(URL_CFG, PUBLIC_ONLY, { fetcher });
    expect(result.ok).toBe(true);
    expect(fetcher).toHaveBeenCalledWith(URL_CFG, PUBLIC_ONLY, expect.objectContaining({ headers: { Accept: "application/json" } }));
  });

  it("says the address is refused when the guard refuses it", async () => {
    const result = await fetchManifest(URL_CFG, PUBLIC_ONLY, { fetcher: fetcherFor(null) });
    expect(result).toEqual({ ok: false, reason: MANIFEST_BLOCKED_REASON });
  });

  it("refuses a private address for a public-only scope before connecting", async () => {
    // The real guardedFetch: a literal private host never reaches the network.
    const result = await fetchManifest("http://192.168.1.1/x/manifest.json", PUBLIC_ONLY);
    expect(result).toEqual({ ok: false, reason: MANIFEST_BLOCKED_REASON });
    expect(await fetchManifest("http://169.254.169.254/manifest.json", PRIVATE_NETWORK_ALLOWED)).toEqual({
      ok: false,
      reason: MANIFEST_BLOCKED_REASON,
    });
  });

  it("collapses what the target said into one verdict, with the detail kept for the log", async () => {
    const cases: [Response, string][] = [
      [new Response("nope", { status: 404 }), "HTTP 404"],
      [new Response("nope", { status: 401 }), "HTTP 401"],
      [new Response("<html>", { status: 200 }), "the body isn't JSON"],
      [new Response(JSON.stringify({ hello: "world" }), { status: 200 }), "missing its id"],
      [new Response("x".repeat(600 * 1024), { status: 200 }), "too large"],
    ];
    for (const [response, detail] of cases) {
      const result = await fetchManifest(URL_CFG, PUBLIC_ONLY, { fetcher: fetcherFor(response) });
      expect(result).toMatchObject({ ok: false, reason: MANIFEST_REJECTED_REASON, detail: expect.stringContaining(detail) });
    }
  });

  it("says the addon didn't answer for any connection failure, redirects included", async () => {
    const unreachable = await fetchManifest(URL_CFG, PUBLIC_ONLY, {
      fetcher: fetcherFor(new TypeError("fetch failed", { cause: new Error("connect ECONNREFUSED 10.0.0.5:22") })),
    });
    expect(unreachable).toMatchObject({ ok: false, reason: MANIFEST_UNREACHABLE_REASON });
    // The reason is shown to the user: never the configured path or the address.
    expect(unreachable.ok === false && unreachable.reason).not.toMatch(/cfg|10\.0\.0\.5/);
  });
});
