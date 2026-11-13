import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, InjectOptions } from "fastify";
import { deriveServiceToken, deriveStremioProfileKey, SERVICE_TOKEN_HEADER } from "@fetch/shared";
import { prisma } from "../lib/prisma.js";
import { buildIntApp } from "../lib/test-fixtures/int-db.js";
import { base32Decode, timeStep, totpCodeAt } from "../lib/totp.js";

// Optional two-factor sign-in end to end (#564), and replacing a profile's
// Stremio addon URL (#565), against the real schema.

const API_TOKEN = "int-test-api-token";
process.env.API_TOKEN = API_TOKEN;

let app: FastifyInstance;

beforeAll(async () => {
  const [{ registerAuthGate }, auth, profiles, stremio] = await Promise.all([
    import("../lib/auth-gate.js"),
    import("./auth.js"),
    import("./profiles.js"),
    import("./stremio.js"),
  ]);
  app = await buildIntApp(async (instance) => {
    registerAuthGate(instance);
    await instance.register(auth.default);
    await instance.register(profiles.default);
    await instance.register(stremio.default);
  });
});

// Each test runs mid-way through one fixed 30-second step, so a step boundary
// can't land between making a code and the server checking it. Only Date is
// faked; timers run as normal.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(Math.floor(Date.now() / 30_000) * 30_000 + 15_000);
});

afterEach(() => {
  vi.useRealTimers();
});

const call = (bearer: string | null, options: InjectOptions) =>
  app.inject({
    ...options,
    headers: { ...(bearer ? { authorization: `Bearer ${bearer}` } : {}), ...options.headers },
  });

const login = (username: string, password: string) =>
  call(null, { method: "POST", url: "/auth/login", payload: { username, password } });

const bootstrapAdmin = async () => {
  const response = await call(API_TOKEN, {
    method: "POST",
    url: "/auth/bootstrap",
    payload: { username: "owner", password: "owner-password" },
  });
  expect(response.statusCode).toBe(201);
  return response.json() as { token: string; account: { id: string } };
};

const inviteFriend = async (adminToken: string) => {
  const { invite } = (await call(adminToken, { method: "POST", url: "/auth/invites", payload: {} })).json();
  const registered = await call(null, {
    method: "POST",
    url: "/auth/register",
    payload: { inviteCode: invite.code, username: "friend", password: "friend-password" },
  });
  expect(registered.statusCode).toBe(201);
  return registered.json() as { token: string; account: { id: string } };
};

/** A code for `secret` at a step offset from now; each offset can be used once. */
const codeFor = (secret: string, offset = 0) => totpCodeAt(base32Decode(secret)!, timeStep(Date.now()) + offset);

/** Turns two-factor on for the signed-in account and returns its secret and recovery codes. */
const enableTotp = async (token: string, password: string) => {
  const setup = await call(token, { method: "POST", url: "/auth/totp/setup", payload: { currentPassword: password } });
  expect(setup.statusCode).toBe(200);
  const { secret, uri } = setup.json() as { secret: string; uri: string };
  expect(uri).toContain(`secret=${secret}`);
  // Used up by enabling, so later sign-ins use the codes either side of it.
  const enabled = await call(token, { method: "POST", url: "/auth/totp/enable", payload: { code: codeFor(secret, -1) } });
  expect(enabled.statusCode).toBe(200);
  const { recoveryCodes } = enabled.json() as { recoveryCodes: string[] };
  expect(recoveryCodes).toHaveLength(10);
  return { secret, recoveryCodes };
};

const activityOf = async (token: string) =>
  ((await call(token, { method: "GET", url: "/auth/activity" })).json().events as { kind: string }[]).map((e) => e.kind);

describe("two-factor sign-in", () => {
  it("leaves accounts without it signing in exactly as before", async () => {
    await bootstrapAdmin();
    const response = await login("owner", "owner-password");
    expect(response.statusCode).toBe(200);
    expect(response.json().token).toBeTruthy();
    expect(response.json().account.totpEnabled).toBe(false);
  });

  it("asks for a code at every sign-in once turned on, and stores the secret encrypted", async () => {
    const { token, account } = await bootstrapAdmin();
    const { secret } = await enableTotp(token, "owner-password");

    const row = await prisma.account.findUniqueOrThrow({ where: { id: account.id } });
    expect(row.totpSecret).not.toContain(secret);
    expect(row.totpRecoveryCodes).toHaveLength(10);

    const first = await login("owner", "owner-password");
    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({ totpRequired: true });
    expect(first.json().token).toBeUndefined();
    const { challenge } = first.json() as { challenge: string };

    const wrong = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, code: "000000" } });
    expect(wrong.statusCode).toBe(401);

    const right = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, code: codeFor(secret) } });
    expect(right.statusCode).toBe(200);
    const session = right.json().token as string;
    expect((await call(session, { method: "GET", url: "/auth/me" })).json().account.totpEnabled).toBe(true);

    // The same code again is refused, even with a fresh challenge.
    const again = (await login("owner", "owner-password")).json().challenge as string;
    const replay = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge: again, code: codeFor(secret) } });
    expect(replay.statusCode).toBe(401);

    expect(await activityOf(session)).toEqual(expect.arrayContaining(["totp_enabled", "totp_failed", "sign_in"]));
  });

  it("refuses a forged or stale challenge", async () => {
    const { token } = await bootstrapAdmin();
    const { secret } = await enableTotp(token, "owner-password");
    const { challenge } = (await login("owner", "owner-password")).json() as { challenge: string };

    const forged = await call(null, {
      method: "POST",
      url: "/auth/login/totp",
      payload: { challenge: `${challenge}x`, code: codeFor(secret) },
    });
    expect(forged.statusCode).toBe(401);
    expect(forged.json().code).toBe("totp_challenge_invalid");

    // A password change voids challenges handed out before it.
    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "owner-password", newPassword: "a-new-password" },
    });
    const stale = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, code: codeFor(secret) } });
    expect(stale.json().code).toBe("totp_challenge_invalid");
  });

  it("counts wrong codes towards the same lockout as wrong passwords", async () => {
    const { token } = await bootstrapAdmin();
    const { secret } = await enableTotp(token, "owner-password");
    const { challenge } = (await login("owner", "owner-password")).json() as { challenge: string };

    let status = 0;
    for (let i = 0; i < 10 && status !== 429; i += 1) {
      status = (await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, code: "000000" } })).statusCode;
    }
    expect(status).toBe(429);
    // Locked out: even the right code waits.
    const right = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, code: codeFor(secret) } });
    expect(right.statusCode).toBe(429);
  });

  it("signs in with a recovery code once, and only once", async () => {
    const { token } = await bootstrapAdmin();
    const { recoveryCodes } = await enableTotp(token, "owner-password");
    const code = recoveryCodes[0]!.toUpperCase();

    const { challenge } = (await login("owner", "owner-password")).json() as { challenge: string };
    const used = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, recoveryCode: code } });
    expect(used.statusCode).toBe(200);

    const { challenge: next } = (await login("owner", "owner-password")).json() as { challenge: string };
    const reused = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge: next, recoveryCode: code } });
    expect(reused.statusCode).toBe(401);

    const status = (await call(used.json().token, { method: "GET", url: "/auth/totp" })).json();
    expect(status).toMatchObject({ enabled: true, recoveryCodesLeft: 9 });
    expect(await activityOf(used.json().token)).toContain("recovery_code_used");
  });

  it("uses up two different recovery codes used at the same moment, and neither comes back", async () => {
    const { token } = await bootstrapAdmin();
    const { recoveryCodes } = await enableTotp(token, "owner-password");
    const challenges = await Promise.all([
      login("owner", "owner-password").then((r) => r.json().challenge as string),
      login("owner", "owner-password").then((r) => r.json().challenge as string),
    ]);

    const results = await Promise.all(
      challenges.map((challenge, i) =>
        call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, recoveryCode: recoveryCodes[i] } })
      )
    );
    expect(results.map((r) => r.statusCode)).toEqual([200, 200]);
    expect((await call(token, { method: "GET", url: "/auth/totp" })).json().recoveryCodesLeft).toBe(8);

    const { challenge } = (await login("owner", "owner-password")).json() as { challenge: string };
    const again = await call(null, { method: "POST", url: "/auth/login/totp", payload: { challenge, recoveryCode: recoveryCodes[0] } });
    expect(again.statusCode).toBe(401);
  });

  it("doesn't let a right password reset the count of wrong codes when turning off", async () => {
    const { token } = await bootstrapAdmin();
    await enableTotp(token, "owner-password");

    let status = 0;
    for (let i = 0; i < 12 && status !== 429; i += 1) {
      status = (
        await call(token, {
          method: "POST",
          url: "/auth/totp/disable",
          payload: { currentPassword: "owner-password", code: "000000" },
        })
      ).statusCode;
    }
    expect(status).toBe(429);
  });

  it("turns off with the password and a current code", async () => {
    const { token } = await bootstrapAdmin();
    const { secret } = await enableTotp(token, "owner-password");

    const noCode = await call(token, { method: "POST", url: "/auth/totp/disable", payload: { currentPassword: "owner-password" } });
    expect(noCode.statusCode).toBe(401);
    const wrongPassword = await call(token, {
      method: "POST",
      url: "/auth/totp/disable",
      payload: { currentPassword: "nope-nope", code: codeFor(secret) },
    });
    expect(wrongPassword.statusCode).toBe(401);

    const off = await call(token, {
      method: "POST",
      url: "/auth/totp/disable",
      payload: { currentPassword: "owner-password", code: codeFor(secret) },
    });
    expect(off.statusCode).toBe(204);
    expect((await login("owner", "owner-password")).json().token).toBeTruthy();
    expect(await activityOf(token)).toContain("totp_disabled");
  });

  it("lets the admin remove a member's two-factor after entering their own password", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    await enableTotp(friend.token, "friend-password");

    const wrong = await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/totp/remove`,
      payload: { adminPassword: "wrong-password" },
    });
    expect(wrong.statusCode).toBe(401);

    const removed = await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/totp/remove`,
      payload: { adminPassword: "owner-password" },
    });
    expect(removed.statusCode).toBe(204);
    expect((await login("friend", "friend-password")).json().token).toBeTruthy();
  });

  it("is reset from the server with fetch reset-2fa", async () => {
    const { token } = await bootstrapAdmin();
    await enableTotp(token, "owner-password");

    const { run } = await import("../cli.js");
    const lines: string[] = [];
    expect(await run(["reset-2fa", "owner"], (line) => lines.push(line))).toBe(0);
    expect(lines.join("\n")).toContain("Turned off two-factor sign-in for owner");
    expect((await login("owner", "owner-password")).json().token).toBeTruthy();
  });
});

describe("replacing a profile's Stremio addon URL", () => {
  it("retires the old URL at once, for that profile only", async () => {
    const { token } = await bootstrapAdmin();
    const create = async (name: string) =>
      (await call(token, { method: "POST", url: "/profiles", payload: { name } })).json().profile as { id: string };
    const first = await create("First");
    const second = await create("Second");

    const oldKey = deriveStremioProfileKey(API_TOKEN, first.id);
    const otherKey = deriveStremioProfileKey(API_TOKEN, second.id);
    expect((await call(null, { method: "GET", url: `/addon/stremio/${oldKey}/manifest.json` })).statusCode).toBe(200);

    const rotated = await call(token, { method: "POST", url: `/profiles/${first.id}/addon-url/rotate`, payload: {} });
    expect(rotated.statusCode).toBe(200);
    const { stremioProfileKey } = rotated.json() as { stremioProfileKey: string };
    expect(stremioProfileKey).not.toBe(oldKey);

    expect((await call(null, { method: "GET", url: `/addon/stremio/${oldKey}/manifest.json` })).statusCode).toBe(404);
    expect((await call(null, { method: "GET", url: `/addon/stremio/${stremioProfileKey}/manifest.json` })).statusCode).toBe(200);
    expect((await call(null, { method: "GET", url: `/addon/stremio/${otherKey}/manifest.json` })).statusCode).toBe(200);

    // What the addon service asks before serving a signed URL.
    const generation = await call(API_TOKEN, {
      method: "GET",
      url: `/profiles/${first.id}/addon-url/generation`,
      headers: { [SERVICE_TOKEN_HEADER]: deriveServiceToken(API_TOKEN) },
    });
    expect(generation.json()).toEqual({ generation: 1 });

    expect(await activityOf(token)).toContain("addon_url_rotated");
  });
});
