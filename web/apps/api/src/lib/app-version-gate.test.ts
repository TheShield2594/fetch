import { afterEach, describe, expect, it } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { registerAppVersionGate } from "./app-version-gate.js";
import { MIN_TV_APP } from "./version.js";

let app: FastifyInstance | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

const build = async () => {
  app = Fastify();
  registerAppVersionGate(app);
  app.get("/profiles", async () => ({ ok: true }));
  app.get("/version", async () => ({ ok: true }));
  app.put("/devices/me/report", async () => ({ ok: true }));
  await app.ready();
  return app;
};

describe("app version gate", () => {
  it("lets requests without the header through, and names the server", async () => {
    const res = await (await build()).inject({ method: "GET", url: "/profiles" });
    expect(res.statusCode).toBe(200);
    expect(res.headers["x-fetch-server-version"]).toBeTruthy();
  });

  it("lets a current TV through", async () => {
    const res = await (await build()).inject({
      method: "GET",
      url: "/profiles",
      headers: { "x-fetch-app-version": MIN_TV_APP },
    });
    expect(res.statusCode).toBe(200);
  });

  it("answers 426 with a code to a TV below the minimum", async () => {
    const res = await (await build()).inject({
      method: "GET",
      url: "/profiles?x=1",
      headers: { "x-fetch-app-version": "0.1.0" },
    });
    expect(res.statusCode).toBe(426);
    expect(res.json()).toMatchObject({ code: "tv_app_too_old", minTvApp: MIN_TV_APP });
  });

  it("still answers /version and the sync report for an old TV", async () => {
    const server = await build();
    const headers = { "x-fetch-app-version": "0.1.0" };
    expect((await server.inject({ method: "GET", url: "/version", headers })).statusCode).toBe(200);
    expect((await server.inject({ method: "PUT", url: "/devices/me/report", headers })).statusCode).toBe(200);
  });
});
