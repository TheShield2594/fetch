import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

// `.env` is where a Compose install keeps its API_TOKEN — docker-compose.yml
// uses the `${API_TOKEN:?}` form, so the stack refuses to start without one —
// and nothing here was reading it. README step 3 sends people straight from
// `docker compose up` to `pnpm smoke`, which then authenticated as the
// "dev-token" default below and got 401s from every token-bearing check, at
// the exact moment they were trying to confirm the install worked. Compose
// reads the same file from the repo root.
//
// Six lines rather than a dotenv dependency, because this parses only what the
// file actually contains: `KEY=value`, `#` comments, and optional quotes. A
// variable already in the environment wins, so `API_BASE=… pnpm smoke` still
// overrides the file.
const loadDotEnv = (): void => {
  let contents: string;
  try {
    contents = readFileSync(fileURLToPath(new URL("../.env", import.meta.url)), "utf8");
  } catch (error) {
    // No .env is a from-source dev run, where the defaults below are right.
    // Anything else — a file that exists but can't be read — is worth failing
    // on: falling back to "dev-token" would report the 401s that follow as the
    // problem, which is the misdiagnosis this whole change exists to fix.
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }

  for (const line of contents.split("\n")) {
    const match = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) continue;
    const [, key, rawValue] = match;
    if (key in process.env) continue;

    const value = rawValue.trim();
    const quoted = /^(['"])([\s\S]*)\1$/.exec(value);
    // Compose treats an unquoted `#` after whitespace as a trailing comment.
    const parsed = quoted ? quoted[2] : value.replace(/\s+#.*$/, "");
    // `KEY=` (the shape .env.example ships) is left unset rather than set to
    // "", so the defaults below still apply.
    if (parsed !== "") process.env[key] = parsed;
  }
};

loadDotEnv();

const apiBase = process.env.API_BASE ?? "http://localhost:7000";
const addonBase = process.env.ADDON_BASE ?? "http://localhost:7001";
const apiToken = process.env.API_TOKEN ?? "dev-token";

let failed = false;

// Answers what `run` returned, so a later check can build on it, or null when it failed.
const check = async <T>(name: string, run: () => Promise<T>): Promise<T | null> => {
  process.stdout.write(`• ${name}... `);
  try {
    const result = await run();
    process.stdout.write("PASS\n");
    return result;
  } catch (error) {
    const details = error instanceof Error ? error.message : String(error);
    process.stdout.write(`FAIL (${details})\n`);
    failed = true;
    return null;
  }
};

const getJson = async (url: string, init?: RequestInit): Promise<{ response: Response; body: unknown }> => {
  const response = await fetch(url, init);
  const text = await response.text();
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new Error(`Invalid JSON from ${url}: ${text.slice(0, 200)}`);
  }
  return { response, body };
};

// 1. GET /health on API — expect 200
await check("API /health", async () => {
  const { response, body } = await getJson(`${apiBase}/health`);
  if (response.status !== 200) {
    throw new Error(`Expected 200, got ${response.status}`);
  }
  if ((body as Record<string, unknown>)?.status !== "ok") {
    throw new Error(`Unexpected body: ${JSON.stringify(body)}`);
  }
});

// 2. GET /health/ready on API — expect 200, which means it reached the database
await check("API /health/ready", async () => {
  const { response, body } = await getJson(`${apiBase}/health/ready`);
  if (response.status !== 200) {
    throw new Error(`Expected 200, got ${response.status} (body: ${JSON.stringify(body)})`);
  }
  const ready = body as { status?: string; database?: { ok?: boolean } };
  if (ready?.status !== "ok" || ready.database?.ok !== true) {
    throw new Error(`Unexpected body: ${JSON.stringify(body)}`);
  }
});

const auth = { Authorization: `Bearer ${apiToken}` };

// 3. GET /profiles — pick the profile the profile-scoped checks below act as.
// Every one of them goes through the API's `resolveProfile`, which wants an
// `x-profile-id` as soon as the install has a second profile, and turns away a
// PIN-protected one that `API_TOKEN` alone hasn't unlocked — so the first
// profile without a PIN.
const profileId = await check("API /profiles", async () => {
  const { response, body } = await getJson(`${apiBase}/profiles`, { headers: auth });
  if (response.status !== 200) {
    throw new Error(`Expected 200, got ${response.status}`);
  }
  const profiles = (body as { profiles?: Array<{ id: string; hasPin?: boolean }> })?.profiles;
  if (!Array.isArray(profiles)) {
    throw new Error(`Response missing profiles array: ${JSON.stringify(body)}`);
  }
  const unlocked = profiles.find((profile) => !profile.hasPin);
  if (!unlocked) {
    throw new Error(
      profiles.length === 0 ? "No profiles yet" : "Every profile has a PIN, so API_TOKEN alone can't act as one"
    );
  }
  return unlocked.id;
});

const profileHeaders = (id: string) => ({ ...auth, "x-profile-id": id });

// 4. GET /addon/config, then the add-on's manifest at that profile's signed URL
// (`/u/<profileId>/<key>/manifest.json`) — expect 200 with a catalogs array.
// The add-on's un-prefixed `/manifest.json` 404s whenever signed URLs are
// required, which by default is as soon as a second account exists.
type Manifest = { catalogs?: Array<{ type: string; id: string; name: string }> };

const addonProfileBase = profileId
  ? await check("API /addon/config", async () => {
      const { response, body } = await getJson(`${apiBase}/addon/config`, { headers: profileHeaders(profileId) });
      if (response.status !== 200) {
        throw new Error(`Expected 200, got ${response.status}`);
      }
      const key = (body as { stremioProfileKey?: string | null })?.stremioProfileKey;
      if (!key) {
        throw new Error(`Response missing stremioProfileKey: ${JSON.stringify(body)}`);
      }
      return `${addonBase}/u/${profileId}/${key}`;
    })
  : null;

if (addonProfileBase) {
  const base = addonProfileBase;
  const manifest = await check("Addon /u/…/manifest.json", async () => {
    const { response, body } = await getJson(`${base}/manifest.json`);
    if (response.status !== 200) {
      throw new Error(`Expected 200, got ${response.status}`);
    }
    const m = body as Manifest;
    if (!Array.isArray(m?.catalogs)) {
      throw new Error(`Response missing catalogs array: ${JSON.stringify(body)}`);
    }
    return m;
  });

  // 5. GET /catalog/movie/{first catalog id}.json on the same signed URL — expect { metas: [...] }
  const firstMovieCatalog = manifest?.catalogs?.find((c) => c.type === "movie");
  if (firstMovieCatalog) {
    await check(`Addon /u/…/catalog/movie/${firstMovieCatalog.id}.json`, async () => {
      const { response, body } = await getJson(`${base}/catalog/movie/${firstMovieCatalog.id}.json`);
      if (response.status !== 200) {
        throw new Error(`Expected 200, got ${response.status}`);
      }
      const b = body as { metas?: unknown[] };
      if (!Array.isArray(b?.metas)) {
        throw new Error(`Expected { metas: [...] }, got: ${JSON.stringify(body)}`);
      }
    });
  } else {
    process.stdout.write("• Catalog movie check... SKIP (no movie catalog in manifest)\n");
  }
} else {
  process.stdout.write("• Addon manifest and catalog checks... SKIP (no profile or addon key)\n");
}

// 6. GET /trakt/status — read-only, unlike an import, which would pull the
// profile's whole Trakt history on every smoke run. With TRAKT_CLIENT_ID set,
// the API should also report Trakt as configured.
if (profileId) {
  await check("API /trakt/status", async () => {
    const { response, body } = await getJson(`${apiBase}/trakt/status`, { headers: profileHeaders(profileId) });
    if (response.status !== 200) {
      throw new Error(`Expected 200, got ${response.status}`);
    }
    const b = body as { connected?: unknown; configured?: unknown };
    if (typeof b?.connected !== "boolean" || typeof b.configured !== "boolean") {
      throw new Error(`Expected { connected, configured, … }, got: ${JSON.stringify(body)}`);
    }
    if (process.env.TRAKT_CLIENT_ID && !b.configured) {
      throw new Error("TRAKT_CLIENT_ID is set here but the API reports Trakt as not configured");
    }
  });
}

// 7. If STEAM_API_KEY and STEAM_ID are set, POST /games/steam/sync — expect
// 200 with a { total, created, updated, matched, unmatched } summary.
if (process.env.STEAM_API_KEY && process.env.STEAM_ID && profileId) {
  await check("POST /games/steam/sync", async () => {
    const { response, body } = await getJson(`${apiBase}/games/steam/sync`, {
      method: "POST",
      headers: profileHeaders(profileId),
    });
    if (response.status !== 200) {
      throw new Error(`Expected 200, got ${response.status}`);
    }
    const b = body as { total?: unknown };
    if (typeof b?.total !== "number") {
      throw new Error(`Expected { total, created, updated, matched, unmatched }, got: ${JSON.stringify(body)}`);
    }
  });
} else {
  process.stdout.write("• Steam sync check... SKIP (STEAM_API_KEY/STEAM_ID not set)\n");
}

if (failed) {
  process.stdout.write("\nSome smoke checks failed.\n");
  process.exit(1);
} else {
  process.stdout.write("\nAll smoke checks passed.\n");
}
