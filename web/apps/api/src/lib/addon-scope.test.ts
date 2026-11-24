import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { deriveAddonKey, deriveServiceToken, parseAddonKey, SERVICE_TOKEN_HEADER } from "@fetch/shared";

// The gate reaches Prisma through `lib/stremio-secret.ts` and `lib/sessions.ts`;
// nothing here needs a database.
vi.mock("./prisma.js", () => ({ prisma: { profile: { findMany: vi.fn() } } }));

const API_TOKEN = "a".repeat(64);
const SERVICE_TOKEN = deriveServiceToken(API_TOKEN);
const PROFILE_ID = "11111111-1111-4111-8111-111111111111";

/** Routes the add-on calls, and a spread of ones it must never reach. */
const ALLOWED = [
  ["GET", "/profiles", "/profiles"],
  ["GET", "/profiles/:id/addon-url/verify", `/profiles/${PROFILE_ID}/addon-url/verify`],
  ["GET", "/lists", "/lists"],
  ["GET", "/lists/:listId/items", "/lists/abc/items?type=movie"],
  ["GET", "/meta/:type/:imdbId", "/meta/movie/tt0111161"],
  ["GET", "/rpdb/config", "/rpdb/config"],
  ["POST", "/scrobble/start", "/scrobble/start"],
] as const;

const REFUSED = [
  // Writes next to reads it may make.
  ["POST", "/lists", "/lists"],
  ["POST", "/lists/:listId/items", "/lists/abc/items"],
  ["POST", "/settings/preferences", "/settings/preferences"],
  ["POST", "/profiles", "/profiles"],
  ["DELETE", "/profiles/:id", `/profiles/${PROFILE_ID}`],
  ["POST", "/profiles/:id/addon-url/rotate", `/profiles/${PROFILE_ID}/addon-url/rotate`],
  // The operator's and the admin's.
  ["POST", "/auth/bootstrap", "/auth/bootstrap"],
  ["GET", "/settings/job-status", "/settings/job-status"],
  ["PUT", "/settings/keys", "/settings/keys"],
  ["GET", "/export", "/export"],
  ["POST", "/import", "/import"],
  ["GET", "/scrobble/now-playing", "/scrobble/now-playing"],
  // Superseded by `…/verify`, and of no use without `API_TOKEN`.
  ["GET", "/profiles/:id/addon-url/generation", `/profiles/${PROFILE_ID}/addon-url/generation`],
] as const;

let app: FastifyInstance;

const buildApp = async () => {
  vi.resetModules();
  process.env.API_TOKEN = API_TOKEN;
  const { registerAuthGate } = await import("./auth-gate.js");
  const { accountScope, isAdminRequest } = await import("./request-auth.js");

  const instance = Fastify({ logger: false });
  registerAuthGate(instance);
  // Each stub answers with who the gate decided the caller is.
  for (const [method, pattern] of [...ALLOWED, ...REFUSED]) {
    instance.route({
      method,
      url: pattern,
      handler: async (request) => ({
        kind: request.auth?.kind ?? null,
        admin: isAdminRequest(request),
        accountScope: accountScope(request),
      }),
    });
  }
  await instance.ready();
  return instance;
};

const call = (method: string, url: string, headers: Record<string, string>) =>
  app.inject({ method: method as "GET", url, headers, ...(method === "GET" ? {} : { payload: {} }) });

beforeEach(async () => {
  app = await buildApp();
});

afterEach(async () => {
  await app.close();
  delete process.env.API_TOKEN;
});

describe("the add-on service's own key (#637)", () => {
  it("is accepted on every route the add-on calls, as a principal that is not the admin", async () => {
    for (const [method, , url] of ALLOWED) {
      const response = await call(method, url, { [SERVICE_TOKEN_HEADER]: SERVICE_TOKEN });
      expect(response.statusCode, `${method} ${url}`).toBe(200);
      // Not scoped to an account (it serves every account's install URLs),
      // but never the admin either.
      expect(response.json(), `${method} ${url}`).toEqual({ kind: "addon", admin: false, accountScope: null });
    }
  });

  it("is refused everywhere else, admin and mutation routes included", async () => {
    for (const [method, , url] of REFUSED) {
      const response = await call(method, url, { [SERVICE_TOKEN_HEADER]: SERVICE_TOKEN });
      expect(response.statusCode, `${method} ${url}`).toBe(403);
      expect(response.json().code).toBe("addon_scope");
    }
  });

  it("is refused on a route that doesn't exist, rather than falling through", async () => {
    const response = await call("GET", "/no/such/route", { [SERVICE_TOKEN_HEADER]: SERVICE_TOKEN });
    expect(response.statusCode).toBe(403);
  });

  it("turns a wrong service token away as unauthenticated", async () => {
    const wrong = await call("GET", "/lists", { [SERVICE_TOKEN_HEADER]: deriveServiceToken("another-token") });
    expect(wrong.statusCode).toBe(401);
    const raw = await call("GET", "/lists", { [SERVICE_TOKEN_HEADER]: API_TOKEN });
    expect(raw.statusCode).toBe(401);
  });

  it("is what FETCH_ADDON_KEY carries, and the half the add-on sends", () => {
    expect(parseAddonKey(deriveAddonKey(API_TOKEN))?.serviceToken).toBe(SERVICE_TOKEN);
  });

  it("can't be sent as a bearer token", async () => {
    const response = await call("GET", "/lists", { authorization: `Bearer ${SERVICE_TOKEN}` });
    expect(response.statusCode).toBe(401);
  });
});

describe("API_TOKEN, unchanged", () => {
  it("is still the operator everywhere", async () => {
    for (const [method, , url] of [...ALLOWED, ...REFUSED]) {
      const response = await call(method, url, { authorization: `Bearer ${API_TOKEN}` });
      expect(response.statusCode, `${method} ${url}`).toBe(200);
      expect(response.json()).toEqual({ kind: "token", admin: true, accountScope: null });
    }
  });

  it("is still the operator for an add-on configured the old way, which sends both", async () => {
    const response = await call("POST", "/auth/bootstrap", {
      authorization: `Bearer ${API_TOKEN}`,
      [SERVICE_TOKEN_HEADER]: SERVICE_TOKEN,
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().kind).toBe("token");
  });
});

// The allowlist names routes by pattern, so a renamed route would quietly shut
// the add-on out of it. Every entry has to be a route some module registers.
describe("ADDON_SERVICE_ROUTES", () => {
  it("names only routes that exist", async () => {
    const { ADDON_SERVICE_ROUTES } = await import("./addon-scope.js");
    const routesDir = fileURLToPath(new URL("../routes/", import.meta.url));
    const sources = readdirSync(routesDir)
      .filter((file) => file.endsWith(".ts") && !file.includes(".test."))
      .map((file) => readFileSync(join(routesDir, file), "utf8"))
      .join("\n");
    for (const entry of ADDON_SERVICE_ROUTES) {
      const [method, pattern] = entry.split(" ") as [string, string];
      const registration = new RegExp(`app\\.${method.toLowerCase()}(<[^(]*>)?\\(\\s*"${pattern.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&")}"`);
      expect(sources, entry).toMatch(registration);
    }
  });
});
