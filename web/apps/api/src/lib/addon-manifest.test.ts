import { afterEach, describe, expect, it, vi } from "vitest";
import {
  configureUrlFor,
  fetchManifest,
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

const publicHost = async () => ["93.184.216.34"];

afterEach(() => {
  vi.unstubAllGlobals();
});

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
  const stubFetch = (response: Response | Error) => {
    const fetchMock = vi.fn(async () => {
      if (response instanceof Error) throw response;
      return response;
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  };

  it("fetches and parses a manifest without following redirects", async () => {
    const fetchMock = stubFetch(new Response(JSON.stringify(MANIFEST), { status: 200 }));
    const result = await fetchManifest("https://addon.example/manifest.json", { resolveHost: publicHost });
    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(URL),
      expect.objectContaining({ redirect: "manual" })
    );
  });

  it("refuses a cloud metadata host before fetching", async () => {
    const fetchMock = stubFetch(new Response("{}"));
    const result = await fetchManifest("http://169.254.169.254/manifest.json", { resolveHost: publicHost });
    expect(result).toMatchObject({ ok: false });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("says when the addon redirects, errors, isn't JSON or doesn't answer", async () => {
    const url = "https://addon.example/cfg/manifest.json";

    stubFetch(new Response(null, { status: 302, headers: { location: "https://elsewhere/" } }));
    expect(await fetchManifest(url, { resolveHost: publicHost })).toMatchObject({
      ok: false,
      reason: expect.stringContaining("redirects"),
    });

    stubFetch(new Response("nope", { status: 404 }));
    expect(await fetchManifest(url, { resolveHost: publicHost })).toMatchObject({
      ok: false,
      reason: "The addon answered with HTTP 404.",
    });

    stubFetch(new Response("<html>", { status: 200 }));
    expect(await fetchManifest(url, { resolveHost: publicHost })).toMatchObject({
      ok: false,
      reason: expect.stringContaining("didn't return JSON"),
    });

    stubFetch(new TypeError("fetch failed"));
    const unreachable = await fetchManifest(url, { resolveHost: publicHost });
    expect(unreachable).toMatchObject({ ok: false, reason: expect.stringContaining("didn't answer") });
    // The reason is shown to the user and may be logged: never the configured path.
    expect(JSON.stringify(unreachable)).not.toContain("cfg");
  });

  it("refuses a manifest that is too large", async () => {
    stubFetch(new Response("x", { status: 200, headers: { "content-length": String(10 * 1024 * 1024) } }));
    expect(await fetchManifest("https://addon.example/manifest.json", { resolveHost: publicHost })).toMatchObject({
      ok: false,
      reason: expect.stringContaining("too large"),
    });
  });
});
