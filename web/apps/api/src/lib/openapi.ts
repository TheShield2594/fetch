import { readFileSync } from "node:fs";
import type { FastifyInstance } from "fastify";
import swagger from "@fastify/swagger";

/**
 * An OpenAPI 3 description of this API at `GET /openapi.json`, generated from
 * the routes as they are registered — so it can't list a route that isn't
 * there or miss one that is, which a hand-kept list eventually does.
 *
 * A route that declares a request schema is described down to its body and
 * parameters. Most routes validate by hand and are listed by method, path and
 * path parameters only; `docs/api.md` is still where their bodies and answers
 * are written out. The spec is behind the same sign-in as everything else.
 *
 * Has to be registered before the routes: it learns of them as they are added.
 */
/** This package's version, for the spec's `info`. "unknown" rather than a failed start if it can't be read. */
export const apiVersion = (): string => {
  try {
    // One directory up from `lib/` in both `src/` and the built `dist/`, then the package root.
    const manifest = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as { version?: unknown };
    return typeof manifest.version === "string" ? manifest.version : "unknown";
  } catch {
    return "unknown";
  }
};

export const registerOpenApi = (app: FastifyInstance, version: string = apiVersion()): void => {
  void app.register(swagger, {
    openapi: {
      openapi: "3.0.3",
      info: {
        title: "Fetch API",
        version,
        description:
          "The API behind the Fetch website, TV and desktop apps. Send a session token, a personal access token " +
          "(Settings → Access tokens) or API_TOKEN as a bearer token, and name the profile to act on in " +
          "`x-profile-id`. Written documentation: docs/api.md.",
      },
      components: {
        securitySchemes: {
          bearer: {
            type: "http",
            scheme: "bearer",
            description: "A session token (fs1_…), a personal access token (fpat_…), a device token or API_TOKEN.",
          },
        },
      },
      security: [{ bearer: [] }],
    },
    // Grouped by the first path segment, which is how docs/api.md is laid out.
    transform: ({ schema, url }) => {
      const group = url.split("/")[1] || "root";
      return { schema: { ...schema, tags: schema?.tags ?? [group] }, url };
    },
  });

  app.get("/openapi.json", { schema: { hide: true } }, async (_request, reply) => {
    reply.header("Cache-Control", "no-store");
    return app.swagger();
  });
};
