import Fastify from "fastify";
import { afterEach, describe, expect, it } from "vitest";
import { registerOpenApi } from "./openapi.js";

describe("GET /openapi.json", () => {
  const app = Fastify();
  afterEach(() => app.close());

  it("describes the routes registered after it", async () => {
    registerOpenApi(app, "1.2.3");
    // In a plugin, as every route of the API is: the spec learns of routes
    // registered after the plugin that builds it has loaded.
    app.register(async (routes) => {
      routes.get("/lists/:id", async () => ({}));
      routes.post(
        "/watch",
        { schema: { body: { type: "object", required: ["imdbId"], properties: { imdbId: { type: "string" } } } } },
        async () => ({})
      );
    });

    const spec = (await app.inject({ method: "GET", url: "/openapi.json" })).json();

    expect(spec.openapi).toBe("3.0.3");
    expect(spec.info.version).toBe("1.2.3");
    expect(spec.components.securitySchemes.bearer).toMatchObject({ type: "http", scheme: "bearer" });
    expect(spec.paths["/lists/{id}"].get.tags).toEqual(["lists"]);
    expect(spec.paths["/lists/{id}"].get.parameters[0]).toMatchObject({ name: "id", in: "path" });
    expect(spec.paths["/watch"].post.requestBody.content["application/json"].schema.required).toEqual(["imdbId"]);
    // The spec doesn't list itself.
    expect(spec.paths["/openapi.json"]).toBeUndefined();
  });
});
