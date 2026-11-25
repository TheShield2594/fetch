#!/usr/bin/env node
// Prints `export VITE_API_BASE=… VITE_ADDON_BASE=…` for docker-entrypoint.sh,
// filled from FETCH_PUBLIC_URL when they aren't set (#644), and says on stderr
// what it derived and anything that looks wrong.
//
// In the image, public-url.mjs is @fetch/shared's compiled src/public-url.ts,
// copied next to this script by the Dockerfile because the runtime image has no
// node_modules; in the repo the package itself is used.
const { describeExpansion, expandPublicUrlEnv } = await import("./public-url.mjs").catch((error) => {
  if (error?.code !== "ERR_MODULE_NOT_FOUND") throw error;
  return import("@fetch/shared/public-url");
});

const NAMES = ["VITE_API_BASE", "VITE_ADDON_BASE"];

const env = { ...process.env };
const expansion = expandPublicUrlEnv(env, NAMES);

const summary = describeExpansion(expansion, env);
if (summary) console.error(summary);
for (const warning of expansion.warnings) console.error(`WARNING: ${warning}`);
const missing = NAMES.filter((name) => !env[name]);
if (missing.length > 0) {
  console.error(
    `WARNING: ${missing.join(" and ")} could not be worked out (no FETCH_PUBLIC_URL). Behind a reverse proxy ` +
      "the page still finds the API at /api and the add-on at /addon, but on the plain LAN layout (website on " +
      ":7002) browsers will block them on :7000/:7001. Set FETCH_PUBLIC_URL in .env to the address you open " +
      "the website at."
  );
}

// Single-quoted for sh, with any ' closed, escaped and reopened.
const quote = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`;
for (const name of NAMES) {
  process.stdout.write(`export ${name}=${quote(env[name] ?? "")}\n`);
}
