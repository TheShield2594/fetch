import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { renderAssets } from "./prepare-assets.mjs";

const template = readFileSync(new URL("../../web/serve.template.json", import.meta.url), "utf8");
const html = "<html><head><script>window.x = 1;</script></head></html>";

test("config.js points the app at the same-origin API", () => {
  const { config } = renderAssets({ origin: "https://watchfetch.tv", template, html });
  assert.match(config, /__CATALOGGY_API_BASE__ = "https:\/\/watchfetch\.tv\/api";/);
  assert.match(config, /__CATALOGGY_SENTRY_DSN__ = "";/);
});

test("_headers carries the CSP, with the inline script hashed", () => {
  const { headers } = renderAssets({ origin: "https://watchfetch.tv", template, html });
  const csp = headers.split("\n").find((line) => line.startsWith("  Content-Security-Policy: "));
  assert.ok(csp, "no CSP line");
  assert.match(csp, /connect-src 'self' https:\/\/watchfetch\.tv/);
  assert.match(csp, /script-src 'self' 'sha256-/);
  assert.match(headers, /^\/\*\n {2}X-Content-Type-Options: nosniff$/m);
  assert.match(headers, /^\/sw\.js\n {2}Cache-Control: no-cache$/m);
});
