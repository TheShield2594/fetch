import type { Event } from "@sentry/node";
import { describe, expect, it } from "vitest";
import { scrubSentryBreadcrumb, scrubSentryEvent } from "./sentry.js";

describe("scrubSentryEvent (#636)", () => {
  it("masks path secrets and the webhook token in the request URL", () => {
    const event: Event = {
      request: { url: "https://fetch.example/api/addon/stremio/s3cret/manifest.json?token=hook&x=1" },
    };
    expect(scrubSentryEvent(event).request?.url).toBe(
      "https://fetch.example/api/addon/stremio/REDACTED/manifest.json?token=REDACTED&x=1",
    );
  });

  it("masks the query string in each shape Sentry uses", () => {
    expect(scrubSentryEvent({ request: { query_string: "token=a&b=2" } }).request?.query_string).toBe(
      "token=REDACTED&b=2",
    );
    expect(scrubSentryEvent({ request: { query_string: { secret: "a", b: "2" } } }).request?.query_string).toEqual({
      secret: "REDACTED",
      b: "2",
    });
    expect(
      scrubSentryEvent({
        request: {
          query_string: [
            ["api_key", "a"],
            ["b", "2"],
          ],
        },
      }).request?.query_string,
    ).toEqual([
      ["api_key", "REDACTED"],
      ["b", "2"],
    ]);
  });

  it("drops auth and cookie headers and keeps the rest", () => {
    const event = scrubSentryEvent({
      request: {
        headers: {
          Authorization: "Bearer t",
          cookie: "s=1",
          "x-webhook-secret": "w",
          "x-fetch-session": "s",
          "x-fetch-stremio-profile-key": "k",
          "user-agent": "Stremio",
        },
        cookies: { s: "1" },
      },
    });
    expect(event.request?.headers).toEqual({ "user-agent": "Stremio" });
    expect(event.request?.cookies).toBeUndefined();
  });

  it("masks the transaction name and breadcrumb URLs", () => {
    const event = scrubSentryEvent({
      transaction: "GET /u/abc/k3y/manifest.json",
      breadcrumbs: [{ category: "http", data: { url: "/addon/stremio/s3cret/manifest.json" } }],
    });
    expect(event.transaction).toBe("GET /u/abc/REDACTED/manifest.json");
    expect(event.breadcrumbs?.[0]?.data?.["url"]).toBe("/addon/stremio/REDACTED/manifest.json");
  });

  it("leaves an event without a request alone", () => {
    expect(scrubSentryEvent({ message: "boom" })).toEqual({ message: "boom" });
  });

  it("masks a lone breadcrumb", () => {
    expect(scrubSentryBreadcrumb({ data: { url: "/u/a/b?pin=1234" } }).data?.["url"]).toBe("/u/a/REDACTED?pin=REDACTED");
  });
});
