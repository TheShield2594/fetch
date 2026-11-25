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
if (!env.VITE_API_BASE) {
  console.error(
    "Neither FETCH_PUBLIC_URL nor VITE_API_BASE is set: the page will look for the API at the address it is " +
      "opened at (port 7000 next to 7002, or /api behind a proxy). Set FETCH_PUBLIC_URL in .env to be explicit."
  );
}

// Single-quoted for sh, with any ' closed, escaped and reopened.
const quote = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`;
for (const name of NAMES) {
  process.stdout.write(`export ${name}=${quote(env[name] ?? "")}\n`);
}
