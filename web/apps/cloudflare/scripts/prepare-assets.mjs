#!/usr/bin/env node
// Finishes apps/web/dist for Workers static assets. This does at build time
// what apps/web/docker-entrypoint.sh does at container start for a self-hosted
// install, since there is no container start for static assets:
//
//   - dist/config.js, telling the app where its API is;
//   - dist/_headers, carrying the same Content-Security-Policy and security
//     headers the `serve` config does, rendered by the same code.
//
// Run after `pnpm --filter @fetch/web build`. PUBLIC_ORIGIN defaults to the
// hosted site; SENTRY_DSN is optional.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { buildConnectSrc, buildScriptSrc, renderServeConfig } from "../../web/scripts/csp.mjs";

const webDir = fileURLToPath(new URL("../../web/", import.meta.url));
const distDir = `${webDir}dist/`;

export const renderAssets = ({ origin, sentryDsn = "", template, html }) => {
  const apiBase = `${origin}/api`;
  const addonBase = `${origin}/addon`;

  const config = [
    `window.__CATALOGGY_API_BASE__ = ${JSON.stringify(apiBase)};`,
    `window.__CATALOGGY_ADDON_BASE__ = ${JSON.stringify(addonBase)};`,
    `window.__CATALOGGY_SENTRY_DSN__ = ${JSON.stringify(sentryDsn)};`,
    "",
  ].join("\n");

  const connectSrc = buildConnectSrc({ apiBase, addonBase, sentryDsn });
  const serve = JSON.parse(renderServeConfig(template, connectSrc, buildScriptSrc(html)));

  // serve.json's "**" rule, written in _headers syntax.
  const lines = [];
  for (const rule of serve.headers) {
    if (rule.source !== "**") throw new Error(`Unexpected serve.json source ${rule.source}`);
    lines.push("/*");
    for (const { key, value } of rule.headers) lines.push(`  ${key}: ${value}`);
  }
  // Browsers must recheck these on every load, or a new release never reaches
  // an installed PWA: the service worker and the page that registers it, and
  // config.js, which says where the API is.
  for (const path of ["/", "/index.html", "/sw.js", "/config.js"]) {
    lines.push(path, "  Cache-Control: no-cache");
  }
  return { config, headers: `${lines.join("\n")}\n` };
};

if (import.meta.url === `file://${process.argv[1]}`) {
  const origin = (process.env.PUBLIC_ORIGIN || "https://watchfetch.tv").replace(/\/+$/, "");
  const { config, headers } = renderAssets({
    origin,
    sentryDsn: process.env.SENTRY_DSN ?? "",
    template: readFileSync(`${webDir}serve.template.json`, "utf8"),
    html: readFileSync(`${distDir}index.html`, "utf8"),
  });
  writeFileSync(`${distDir}config.js`, config);
  writeFileSync(`${distDir}_headers`, headers);
  console.log(`Wrote ${distDir}config.js and ${distDir}_headers for ${origin}`);
}
