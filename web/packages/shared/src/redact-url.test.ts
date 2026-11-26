import { describe, expect, it } from "vitest";
import { redactUrl } from "./redact-url.js";

// The query-parameter cases live with the Fastify wiring test in apps/api.
describe("redactUrl: secrets in the path (#636)", () => {
  it("masks the profile key in the addon's signed route and keeps the profile id", () => {
    expect(redactUrl("/u/2f1c3c1e-0000-4000-8000-000000000000/k3y-secret/manifest.json")).toBe(
      "/u/2f1c3c1e-0000-4000-8000-000000000000/REDACTED/manifest.json",
    );
  });

  it("masks the API's Stremio add-on secret", () => {
    expect(redactUrl("/addon/stremio/s3cret/catalog/movie/top.json")).toBe(
      "/addon/stremio/REDACTED/catalog/movie/top.json",
    );
  });

  it("masks it behind a path prefix and in a full URL", () => {
    expect(redactUrl("/api/addon/stremio/s3cret/manifest.json")).toBe("/api/addon/stremio/REDACTED/manifest.json");
    expect(redactUrl("https://fetch.example/u/abc/k3y/catalog/movie/x.json")).toBe(
      "https://fetch.example/u/abc/REDACTED/catalog/movie/x.json",
    );
  });

  it("masks a key that ends the path and one followed by a query", () => {
    expect(redactUrl("/addon/stremio/s3cret")).toBe("/addon/stremio/REDACTED");
    expect(redactUrl("/u/abc/k3y?token=t&x=1")).toBe("/u/abc/REDACTED?token=REDACTED&x=1");
  });

  it("leaves other paths alone", () => {
    expect(redactUrl("/p/abc/manifest.json")).toBe("/p/abc/manifest.json");
    expect(redactUrl("/u/abc")).toBe("/u/abc");
    expect(redactUrl("/profiles/abc/addon-url/verify")).toBe("/profiles/abc/addon-url/verify");
  });
});
