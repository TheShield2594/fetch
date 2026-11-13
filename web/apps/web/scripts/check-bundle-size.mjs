#!/usr/bin/env node
// Fails the build when the code a cold start downloads grows past its budget,
// or when that code includes anything from Settings.
//
// "A cold start" is what dist/index.html asks for before the app can paint: the
// entry script and every chunk it lists as a modulepreload. Lazy chunks are not
// counted — a page nobody opens costs nothing — which is also why a Settings
// module showing up here is an error rather than a size question: it means a
// static import has dragged a lazy page's code into the entry graph, the way
// the profile sheet once rode in on `api/types.ts` (#531).
//
// Sizes are gzip, since that is what crosses the wire. The budgets sit a little
// above the measured size so routine growth passes and a regression of the
// kind above (tens of kB) does not; raise them on purpose, with the reason in
// the commit, rather than to make a red build go green.
//
// Usage: node scripts/check-bundle-size.mjs [distDir] [--report]   (run after `vite build`;
// --report also lists every chunk by gzipped size)
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { gzipSync } from "node:zlib";

const BUDGET_KB = {
  // The entry script alone.
  entry: 135,
  // Entry plus every modulepreload: everything fetched before the first paint.
  initial: 145,
};

// Chunk names that must never be on the cold-start path. A chunk is named after
// one of its modules, so this only catches a leak that brings its namesake
// along; the #531 leak arrived in a chunk named `exportDownload`, which is what
// the size budgets are for (it added 29 files and ~15 kB).
const FORBIDDEN = /^(SettingsPage|ProfilePage|ProfileSheet|[A-Za-z]+Settings)-/;

const args = process.argv.slice(2);
const report = args.includes("--report");
const distDir = resolve(args.find((arg) => !arg.startsWith("--")) ?? "dist");
const html = readFileSync(join(distDir, "index.html"), "utf8");

const entryMatch = html.match(/<script[^>]+type="module"[^>]+src="\/?([^"]+)"/);
if (!entryMatch) {
  console.error("check-bundle-size: no module entry script found in index.html");
  process.exit(1);
}
const entry = entryMatch[1];
const preloads = [...html.matchAll(/<link[^>]+rel="modulepreload"[^>]+href="\/?([^"]+)"/g)].map((m) => m[1]);

const gzipKb = (file) => gzipSync(readFileSync(join(distDir, file))).length / 1024;
const fmt = (kb) => `${kb.toFixed(1)} kB`;

const entryKb = gzipKb(entry);
const files = [entry, ...preloads.filter((file) => file !== entry)];
const initialKb = files.reduce((sum, file) => sum + gzipKb(file), 0);

const problems = [];
const base = (file) => file.split("/").pop();
const forbidden = files.filter((file) => FORBIDDEN.test(base(file)));
if (forbidden.length > 0) {
  problems.push(`settings code is on the cold-start path: ${forbidden.map(base).join(", ")}`);
}
if (entryKb > BUDGET_KB.entry) {
  problems.push(`entry chunk is ${fmt(entryKb)} gzipped, over its ${BUDGET_KB.entry} kB budget`);
}
if (initialKb > BUDGET_KB.initial) {
  problems.push(`cold start is ${fmt(initialKb)} gzipped, over its ${BUDGET_KB.initial} kB budget`);
}

console.log(`entry ${base(entry)}: ${fmt(entryKb)} gzip (budget ${BUDGET_KB.entry} kB)`);
console.log(`cold start, ${files.length} files: ${fmt(initialKb)} gzip (budget ${BUDGET_KB.initial} kB)`);
for (const file of preloads) console.log(`  preload ${base(file)}: ${fmt(gzipKb(file))}`);

// Every chunk, largest first, so a CI log answers "what grew" without a rebuild.
if (report) {
  const all = readdirSync(join(distDir, "assets"))
    .filter((f) => f.endsWith(".js"))
    .map((f) => [f, gzipKb(join("assets", f))])
    .sort((a, b) => b[1] - a[1]);
  for (const [f, kb] of all) console.log(`  ${f}: ${fmt(kb)}`);
}

if (problems.length > 0) {
  for (const problem of problems) console.error(`check-bundle-size: ${problem}`);
  process.exit(1);
}
