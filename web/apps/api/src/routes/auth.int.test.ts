import { beforeAll, describe, expect, it } from "vitest";
import type { FastifyInstance, InjectOptions } from "fastify";
import { prisma } from "../lib/prisma.js";
import { buildIntApp, createProfile } from "../lib/test-fixtures/int-db.js";

// Accounts end to end, through the real auth gate: the first-run admin, invites,
// sign-in, and — the point of all of it — that one account can neither see nor
// touch another's profiles, while install-wide settings stay with the admin.

const API_TOKEN = "int-test-api-token";
process.env.API_TOKEN = API_TOKEN;

let app: FastifyInstance;

beforeAll(async () => {
  const [{ registerAuthGate }, auth, profiles, lists, settings, devices, syncOverview, profileAddons] =
    await Promise.all([
      import("../lib/auth-gate.js"),
      import("./auth.js"),
      import("./profiles.js"),
      import("./lists.js"),
      import("./settings.js"),
      import("./devices.js"),
      import("./sync-overview.js"),
      import("./profile-addons.js"),
    ]);
  app = await buildIntApp(async (instance) => {
    registerAuthGate(instance);
    await instance.register(auth.default);
    await instance.register(profiles.default);
    await instance.register(lists.default);
    await instance.register(settings.default);
    await instance.register(devices.default);
    await instance.register(syncOverview.default);
    await instance.register(profileAddons.default);
  });
});

const call = (bearer: string | null, options: InjectOptions) =>
  app.inject({
    ...options,
    headers: { ...(bearer ? { authorization: `Bearer ${bearer}` } : {}), ...options.headers },
  });

const bootstrapAdmin = async () => {
  const response = await call(API_TOKEN, {
    method: "POST",
    url: "/auth/bootstrap",
    payload: { username: "Owner", password: "owner-password" },
  });
  expect(response.statusCode).toBe(201);
  return response.json();
};

const inviteFriend = async (adminToken: string) => {
  const created = await call(adminToken, { method: "POST", url: "/auth/invites", payload: {} });
  expect(created.statusCode).toBe(201);
  const { invite } = created.json();
  const registered = await call(null, {
    method: "POST",
    url: "/auth/register",
    payload: { inviteCode: invite.code, username: "friend", password: "friend-password" },
  });
  expect(registered.statusCode).toBe(201);
  return { code: invite.code, ...(registered.json()) };
};

const profilesOf = async (token: string) =>
  (await call(token, { method: "GET", url: "/profiles" })).json().profiles as { id: string; name: string }[];

describe("first-run setup", () => {
  it("reports setup as required until the admin exists", async () => {
    expect((await call(null, { method: "GET", url: "/auth/status" })).json()).toEqual({ setupRequired: true });
    await bootstrapAdmin();
    expect((await call(null, { method: "GET", url: "/auth/status" })).json()).toEqual({ setupRequired: false });
  });

  it("makes the admin with API_TOKEN, lowercases the name and hands over existing profiles", async () => {
    const legacy = await createProfile("Living room");
    const { account } = await bootstrapAdmin();

    expect(account).toMatchObject({ username: "owner", isAdmin: true });
    expect((await prisma.profile.findUniqueOrThrow({ where: { id: legacy.id } })).accountId).toBe(account.id);
  });

  it("refuses to run twice, and refuses anything but API_TOKEN", async () => {
    const { token } = await bootstrapAdmin();

    const again = await call(API_TOKEN, {
      method: "POST",
      url: "/auth/bootstrap",
      payload: { username: "second", password: "second-password" },
    });
    expect(again.statusCode).toBe(409);

    const withSession = await call(token, {
      method: "POST",
      url: "/auth/bootstrap",
      payload: { username: "second", password: "second-password" },
    });
    expect(withSession.statusCode).toBe(403);

    const anonymous = await call(null, { method: "POST", url: "/auth/bootstrap", payload: {} });
    expect(anonymous.statusCode).toBe(401);
  });
});

describe("sign-in", () => {
  it("signs in with the right password, refuses the wrong one the same way as an unknown name", async () => {
    await bootstrapAdmin();

    const wrong = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "owner", password: "nope-nope-nope" },
    });
    const unknown = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "nobody", password: "owner-password" },
    });
    expect(wrong.statusCode).toBe(401);
    expect(unknown.statusCode).toBe(401);
    expect(wrong.json()).toEqual(unknown.json());

    const right = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "OWNER", password: "owner-password" },
    });
    expect(right.statusCode).toBe(200);
    const me = await call(right.json().token, { method: "GET", url: "/auth/me" });
    expect(me.json()).toMatchObject({ account: { username: "owner", isAdmin: true }, isAdmin: true });
  });

  it("locks the account after repeated wrong passwords", async () => {
    await bootstrapAdmin();
    for (let attempt = 0; attempt < 5; attempt += 1) {
      await call(null, { method: "POST", url: "/auth/login", payload: { username: "owner", password: "wrong-wrong" } });
    }
    const locked = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "owner", password: "owner-password" },
    });
    expect(locked.statusCode).toBe(429);
  });

  it("ends the session on sign-out", async () => {
    const { token } = await bootstrapAdmin();
    expect((await call(token, { method: "POST", url: "/auth/logout" })).statusCode).toBe(204);
    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
  });

  it("signs other devices out when the password changes", async () => {
    const { token: first } = await bootstrapAdmin();
    const second = (
      await call(null, { method: "POST", url: "/auth/login", payload: { username: "owner", password: "owner-password" } })
    ).json().token as string;

    const changed = await call(first, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "owner-password", newPassword: "a-new-password" },
    });
    expect(changed.statusCode).toBe(204);
    expect((await call(first, { method: "GET", url: "/auth/me" })).statusCode).toBe(200);
    expect((await call(second, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
  });
});

describe("invites", () => {
  it("gives the invited person an account with a profile of their own", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    const friendProfiles = await profilesOf(friend.token);
    expect(friendProfiles).toEqual([expect.objectContaining({ name: "friend" })]);
    const me = await call(friend.token, { method: "GET", url: "/auth/me" });
    expect(me.json()).toMatchObject({ account: { username: "friend", isAdmin: false }, isAdmin: false });
  });

  it("works once", async () => {
    const admin = await bootstrapAdmin();
    const { code } = await inviteFriend(admin.token);

    const reused = await call(null, {
      method: "POST",
      url: "/auth/register",
      payload: { inviteCode: code, username: "someone", password: "someone-password" },
    });
    expect(reused.statusCode).toBe(400);
    expect(reused.json().code).toBe("invite_invalid");
  });

  it("can only be made by the admin", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    const attempt = await call(friend.token, { method: "POST", url: "/auth/invites", payload: {} });
    expect(attempt.statusCode).toBe(403);
  });

  it("refuses a name that is taken, and leaves the invite usable", async () => {
    const admin = await bootstrapAdmin();
    const created = await call(admin.token, { method: "POST", url: "/auth/invites", payload: {} });
    const code = created.json().invite.code as string;

    const taken = await call(null, {
      method: "POST",
      url: "/auth/register",
      payload: { inviteCode: code, username: "owner", password: "another-password" },
    });
    expect(taken.statusCode).toBe(409);

    const retry = await call(null, {
      method: "POST",
      url: "/auth/register",
      payload: { inviteCode: code, username: "friend", password: "friend-password" },
    });
    expect(retry.statusCode).toBe(201);
  });
});

describe("one account's data is invisible to another", () => {
  it("lists only your own profiles", async () => {
    const admin = await bootstrapAdmin();
    await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Owner's profile" } });
    const friend = await inviteFriend(admin.token);

    expect((await profilesOf(admin.token)).map((p) => p.name)).toEqual(["Owner's profile"]);
    expect((await profilesOf(friend.token)).map((p) => p.name)).toEqual(["friend"]);
    // API_TOKEN is the operator, and sees the whole install.
    expect(await profilesOf(API_TOKEN)).toHaveLength(2);
  });

  it("answers another account's profile as if it didn't exist", async () => {
    const admin = await bootstrapAdmin();
    const ownerProfile = (
      await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Owner's profile" } })
    ).json().profile as { id: string };
    const friend = await inviteFriend(admin.token);

    const lists = await call(friend.token, {
      method: "GET",
      url: "/lists",
      headers: { "x-profile-id": ownerProfile.id },
    });
    expect(lists.statusCode).toBe(404);

    const rename = await call(friend.token, {
      method: "PATCH",
      url: `/profiles/${ownerProfile.id}`,
      payload: { name: "mine now" },
    });
    expect(rename.statusCode).toBe(404);

    const remove = await call(friend.token, { method: "DELETE", url: `/profiles/${ownerProfile.id}`, payload: {} });
    expect(remove.statusCode).toBe(404);
    expect(await prisma.profile.findUnique({ where: { id: ownerProfile.id } })).not.toBeNull();
  });

  it("picks your only profile when no profile header is sent", async () => {
    const admin = await bootstrapAdmin();
    await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Owner's profile" } });
    const friend = await inviteFriend(admin.token);

    const created = await call(friend.token, {
      method: "POST",
      url: "/lists",
      payload: { name: "Friend's picks", kind: "custom" },
    });
    expect(created.statusCode).toBe(201);
    const list = await prisma.list.findFirstOrThrow({ where: { name: "Friend's picks" }, include: { profile: true } });
    expect(list.profile.accountId).toBe(friend.account.id);
  });
});

describe("install-wide settings", () => {
  it("are read by everyone and changed only by the admin", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    expect((await call(friend.token, { method: "GET", url: "/settings/preferences" })).statusCode).toBe(200);

    const friendWrite = await call(friend.token, {
      method: "POST",
      url: "/settings/preferences",
      payload: { language: "fr-FR" },
    });
    expect(friendWrite.statusCode).toBe(403);
    expect(friendWrite.json().code).toBe("admin_required");

    const adminWrite = await call(admin.token, {
      method: "POST",
      url: "/settings/preferences",
      payload: { language: "fr-FR" },
    });
    expect(adminWrite.statusCode).toBe(200);
  });
});

describe("removing an account", () => {
  it("takes its profiles and their data with it, and can't remove the admin", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    expect(
      (await call(admin.token, { method: "DELETE", url: `/auth/accounts/${admin.account.id}` })).statusCode
    ).toBe(400);

    const removed = await call(admin.token, { method: "DELETE", url: `/auth/accounts/${friend.account.id}` });
    expect(removed.statusCode).toBe(204);
    expect(await prisma.profile.count({ where: { accountId: friend.account.id } })).toBe(0);
    expect((await call(friend.token, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
  });
});

/** Pairs a TV the way the TV and the website do, confirmed by `confirmer`. */
const pairTv = async (confirmer: string, name = "Living room") => {
  const started = await call(null, { method: "POST", url: "/devices/pair/start", payload: { name } });
  expect(started.statusCode).toBe(201);
  const { code, pollSecret } = started.json() as { code: string; pollSecret: string };
  expect((await call(confirmer, { method: "POST", url: "/devices/pair/confirm", payload: { code } })).statusCode).toBe(200);
  const polled = await call(null, {
    method: "GET",
    url: "/devices/pair/poll",
    headers: { "x-pairing-secret": pollSecret },
  });
  expect(polled.statusCode).toBe(200);
  return polled.json() as { token: string; device: { id: string } };
};

describe("a paired TV belongs to the account that paired it", () => {
  it("sees that account's profiles and nobody else's", async () => {
    const admin = await bootstrapAdmin();
    const ownerProfile = (
      await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Owner's profile" } })
    ).json().profile as { id: string };
    const friend = await inviteFriend(admin.token);
    const tv = await pairTv(friend.token);

    expect((await profilesOf(tv.token)).map((p) => p.name)).toEqual(["friend"]);
    const slotted = (await call(tv.token, { method: "GET", url: "/devices/me/profiles" })).json().profiles as {
      name: string;
    }[];
    expect(slotted.map((p) => p.name)).toEqual(["friend"]);

    const ownerAddons = await call(tv.token, { method: "GET", url: `/profiles/${ownerProfile.id}/addons` });
    expect(ownerAddons.statusCode).toBe(404);
  });

  it("is listed and managed only by its own account", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    const friendsTv = await pairTv(friend.token, "Friend's TV");
    await pairTv(admin.token, "Owner's TV");

    const namesFor = async (token: string) =>
      ((await call(token, { method: "GET", url: "/devices" })).json().devices as { name: string }[]).map((d) => d.name);
    expect(await namesFor(admin.token)).toEqual(["Owner's TV"]);
    expect(await namesFor(friend.token)).toEqual(["Friend's TV"]);

    const revoke = await call(admin.token, { method: "DELETE", url: `/devices/${friendsTv.device.id}` });
    expect(revoke.statusCode).toBe(404);
    expect((await call(friendsTv.token, { method: "GET", url: "/devices/me" })).statusCode).toBe(200);
  });

  it("goes to the admin when it was paired before accounts existed", async () => {
    const legacy = await prisma.device.create({ data: { name: "Old TV", tokenHash: "legacy-hash" } });
    const { account } = await bootstrapAdmin();

    expect((await prisma.device.findUniqueOrThrow({ where: { id: legacy.id } })).accountId).toBe(account.id);
  });

  it("can't change the server's settings", async () => {
    const admin = await bootstrapAdmin();
    const tv = await pairTv(admin.token);

    const write = await call(tv.token, { method: "POST", url: "/settings/preferences", payload: { language: "fr-FR" } });
    expect(write.statusCode).toBe(403);
  });
});

describe("the sync overview", () => {
  it("shows an account its own profiles and TVs, and the install's jobs only to the admin", async () => {
    const admin = await bootstrapAdmin();
    await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Owner's profile" } });
    await pairTv(admin.token, "Owner's TV");
    const friend = await inviteFriend(admin.token);

    const overview = (await call(friend.token, { method: "GET", url: "/sync/overview" })).json() as {
      profiles: { name: string }[];
      devices: unknown[];
      server: { jobs: unknown[] };
    };
    expect(overview.profiles.map((p) => p.name)).toEqual(["friend"]);
    expect(overview.devices).toEqual([]);
    expect(overview.server.jobs).toEqual([]);
  });
});
