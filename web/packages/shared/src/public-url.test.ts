import { describe, expect, it } from "vitest";
import {
  defaultBasesForLocation,
  derivePublicUrls,
  describeExpansion,
  expandPublicUrlEnv,
  mentionsExampleHost,
  parsePublicUrl,
} from "./public-url.js";

// FETCH_PUBLIC_URL replaces six URL variables that had to agree (#644). What
// matters is that the derived values match the two layouts the stack actually
// ships (published ports on a LAN, /api and /addon behind a proxy), that an
// explicit variable always wins, and that a leftover example address is loud.

describe("parsePublicUrl", () => {
  it("accepts full URLs and bare hosts", () => {
    expect(parsePublicUrl("https://fetch.example.com/")?.origin).toBe("https://fetch.example.com");
    expect(parsePublicUrl("fetch.example.com")?.protocol).toBe("https:");
    expect(parsePublicUrl("10.0.0.5:7002")?.href).toBe("http://10.0.0.5:7002/");
  });

  it("rejects what can't be a site address", () => {
    expect(parsePublicUrl("")).toBeNull();
    expect(parsePublicUrl("ftp://fetch.example.com")).toBeNull();
    expect(parsePublicUrl("https://user:pw@fetch.example.com")).toBeNull();
    expect(parsePublicUrl("https://fetch.example.com/?x=1")).toBeNull();
  });
});

describe("derivePublicUrls", () => {
  it("uses the published ports when the website is on 7002", () => {
    expect(derivePublicUrls("http://10.0.0.5:7002/")).toEqual({
      layout: "ports",
      web: "http://10.0.0.5:7002",
      api: "http://10.0.0.5:7000",
      addon: "http://10.0.0.5:7001",
      allowedOrigins: ["http://10.0.0.5:7002", "http://localhost:7002", "http://127.0.0.1:7002"],
      traktRedirectUri: "http://10.0.0.5:7000/trakt/oauth/callback",
    });
  });

  it("keeps IPv6 literals bracketed", () => {
    expect(derivePublicUrls("http://[fd00::5]:7002")?.api).toBe("http://[fd00::5]:7000");
  });

  it("uses /api and /addon behind a proxy", () => {
    expect(derivePublicUrls("https://fetch.example.com")).toEqual({
      layout: "paths",
      web: "https://fetch.example.com",
      api: "https://fetch.example.com/api",
      addon: "https://fetch.example.com/addon",
      allowedOrigins: ["https://fetch.example.com"],
      traktRedirectUri: "https://fetch.example.com/api/trakt/oauth/callback",
    });
  });

  it("keeps a sub-path the proxy mounts the site under", () => {
    const urls = derivePublicUrls("https://home.example.com/fetch/");
    expect(urls?.api).toBe("https://home.example.com/fetch/api");
    expect(urls?.allowedOrigins).toEqual(["https://home.example.com"]);
  });
});

describe("expandPublicUrlEnv", () => {
  it("fills unset and empty variables, leaving explicit ones alone", () => {
    const env: Record<string, string | undefined> = {
      FETCH_PUBLIC_URL: "https://fetch.example.com",
      FETCH_API_PUBLIC: "",
      ADDON_PUBLIC_BASE: "https://addon.example.com",
    };
    const expansion = expandPublicUrlEnv(env);
    expect(env.FETCH_API_PUBLIC).toBe("https://fetch.example.com/api");
    expect(env.ADDON_PUBLIC_BASE).toBe("https://addon.example.com");
    expect(env.VITE_ADDON_BASE).toBe("https://addon.example.com");
    expect(env.FETCH_ALLOWED_ORIGINS).toBe("https://fetch.example.com");
    expect(expansion.filled).not.toContain("ADDON_PUBLIC_BASE");
    expect(expansion.warnings).toEqual([]);
  });

  it("carries an overridden API address to the web page and the Trakt redirect", () => {
    const env: Record<string, string | undefined> = {
      FETCH_PUBLIC_URL: "https://fetch.example.com",
      FETCH_API_PUBLIC: "https://api.example.com/",
    };
    expandPublicUrlEnv(env);
    expect(env.VITE_API_BASE).toBe("https://api.example.com");
    expect(env.TRAKT_REDIRECT_URI).toBe("https://api.example.com/trakt/oauth/callback");
  });

  it("allows an overridden website origin", () => {
    const env: Record<string, string | undefined> = {
      FETCH_PUBLIC_URL: "http://10.0.0.5:7002",
      FETCH_WEB_PUBLIC: "https://fetch.example.com",
    };
    expandPublicUrlEnv(env, ["FETCH_ALLOWED_ORIGINS"]);
    expect(env.FETCH_ALLOWED_ORIGINS?.split(",")).toEqual([
      "https://fetch.example.com",
      "http://10.0.0.5:7002",
      "http://localhost:7002",
      "http://127.0.0.1:7002",
    ]);
  });

  it("only touches the variables it is given", () => {
    const env: Record<string, string | undefined> = { FETCH_PUBLIC_URL: "https://fetch.example.com" };
    expandPublicUrlEnv(env, ["ADDON_PUBLIC_BASE"]);
    expect(Object.keys(env).sort()).toEqual(["ADDON_PUBLIC_BASE", "FETCH_PUBLIC_URL"]);
  });

  it("removes compose's empty strings when there is nothing to derive from", () => {
    const env: Record<string, string | undefined> = { FETCH_API_PUBLIC: "", TRAKT_REDIRECT_URI: " " };
    const expansion = expandPublicUrlEnv(env);
    expect("FETCH_API_PUBLIC" in env).toBe(false);
    expect("TRAKT_REDIRECT_URI" in env).toBe(false);
    expect(expansion.derived).toBeNull();
    expect(describeExpansion(expansion, env)).toBeNull();
  });

  it("warns about an unparseable FETCH_PUBLIC_URL", () => {
    const env: Record<string, string | undefined> = { FETCH_PUBLIC_URL: "not a url at all" };
    expect(expandPublicUrlEnv(env).warnings[0]).toContain("is not a URL");
  });

  it("warns while anything still holds the example address", () => {
    const env: Record<string, string | undefined> = {
      FETCH_PUBLIC_URL: "https://fetch.example.com",
      FETCH_ALLOWED_ORIGINS: "https://fetch.example.com,http://192.168.1.25:7002",
    };
    const { warnings } = expandPublicUrlEnv(env);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toMatch(/^FETCH_ALLOWED_ORIGINS still points at 192\.168\.1\.25/);
  });

  it("names FETCH_PUBLIC_URL itself when it is the example", () => {
    const env: Record<string, string | undefined> = { FETCH_PUBLIC_URL: "http://192.168.1.25:7002" };
    const { warnings } = expandPublicUrlEnv(env, ["FETCH_API_PUBLIC"]);
    expect(warnings[0]).toMatch(/^FETCH_PUBLIC_URL, FETCH_API_PUBLIC still point at/);
  });

  it("says what it derived", () => {
    const env: Record<string, string | undefined> = { FETCH_PUBLIC_URL: "https://fetch.example.com" };
    const expansion = expandPublicUrlEnv(env, ["FETCH_API_PUBLIC"]);
    expect(describeExpansion(expansion, env)).toBe(
      "FETCH_PUBLIC_URL (paths layout) → FETCH_API_PUBLIC=https://fetch.example.com/api"
    );
  });
});

describe("mentionsExampleHost", () => {
  it("matches the host, not a longer address", () => {
    expect(mentionsExampleHost("http://192.168.1.25:7000")).toBe(true);
    expect(mentionsExampleHost("http://192.168.1.250:7000")).toBe(false);
    expect(mentionsExampleHost(undefined)).toBe(false);
  });
});

describe("defaultBasesForLocation", () => {
  it("follows the same two layouts from the page's address", () => {
    expect(
      defaultBasesForLocation({ protocol: "http:", hostname: "10.0.0.5", port: "7002", origin: "http://10.0.0.5:7002" })
    ).toEqual({ api: "http://10.0.0.5:7000", addon: "http://10.0.0.5:7001" });
    expect(
      defaultBasesForLocation({
        protocol: "https:",
        hostname: "fetch.example.com",
        port: "",
        origin: "https://fetch.example.com",
      })
    ).toEqual({ api: "https://fetch.example.com/api", addon: "https://fetch.example.com/addon" });
  });
});
