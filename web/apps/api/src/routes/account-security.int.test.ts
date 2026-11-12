import { beforeAll, describe, expect, it } from "vitest";
import type { FastifyInstance, InjectOptions } from "fastify";
import { prisma } from "../lib/prisma.js";
import { buildIntApp } from "../lib/test-fixtures/int-db.js";

// Account security and recovery end to end (#540, #541, #542): where an
// account is signed in and ending those sign-ins, the activity log, changing
// a username, deleting your own account, the admin's recovery actions, and the
// `fetch` command an operator runs on the server.

const API_TOKEN = "int-test-api-token";
process.env.API_TOKEN = API_TOKEN;

let app: FastifyInstance;

beforeAll(async () => {
  const [{ registerAuthGate }, auth, profiles, devices, exportRoutes, syncOverview] = await Promise.all([
    import("../lib/auth-gate.js"),
    import("./auth.js"),
    import("./profiles.js"),
    import("./devices.js"),
    import("./export.js"),
    import("./sync-overview.js"),
  ]);
  app = await buildIntApp(async (instance) => {
    registerAuthGate(instance);
    await instance.register(auth.default);
    await instance.register(profiles.default);
    await instance.register(devices.default);
    await instance.register(exportRoutes.default);
    await instance.register(syncOverview.default);
  });
});

const call = (bearer: string | null, options: InjectOptions) =>
  app.inject({
    ...options,
    headers: { ...(bearer ? { authorization: `Bearer ${bearer}` } : {}), ...options.headers },
  });

const login = async (username: string, password: string, userAgent = "Test browser") =>
  call(null, { method: "POST", url: "/auth/login", payload: { username, password }, headers: { "user-agent": userAgent } });

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

const pairTv = async (token: string, name = "Living room") => {
  const started = (await call(null, { method: "POST", url: "/devices/pair/start", payload: { name } })).json();
  await call(token, { method: "POST", url: "/devices/pair/confirm", payload: { code: started.code } });
  const polled = await call(null, {
    method: "GET",
    url: "/devices/pair/poll",
    headers: { "x-pairing-secret": started.pollSecret },
  });
  expect(polled.statusCode).toBe(200);
  return polled.json() as { token: string; device: { id: string } };
};

type SignIn = { kind: "browser" | "tv"; id: string; current: boolean; userAgent?: string | null; name?: string };
const signInsOf = async (token: string) => (await call(token, { method: "GET", url: "/auth/sessions" })).json().sessions as SignIn[];
const activityOf = async (token: string) =>
  ((await call(token, { method: "GET", url: "/auth/activity" })).json().events as { kind: string; detail: string | null }[]);

describe("where you're signed in", () => {
  it("lists every browser and TV, marks this browser, and ends one sign-in", async () => {
    const { token } = await bootstrapAdmin();
    const other = (await login("owner", "owner-password", "Firefox on Linux")).json().token as string;
    await pairTv(token);

    const signIns = await signInsOf(token);
    expect(signIns.filter((s) => s.kind === "browser")).toHaveLength(2);
    expect(signIns.find((s) => s.kind === "tv")?.name).toBe("Living room");
    expect(signIns.filter((s) => s.current)).toHaveLength(1);
    const firefox = signIns.find((s) => s.userAgent === "Firefox on Linux")!;
    expect(firefox.current).toBe(false);

    expect((await call(token, { method: "DELETE", url: `/auth/sessions/${firefox.id}` })).statusCode).toBe(204);
    expect((await call(other, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(200);
  });

  it("won't end another account's sign-in", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    const friendsSession = (await signInsOf(friend.token)).find((s) => s.current)!;

    expect((await call(admin.token, { method: "DELETE", url: `/auth/sessions/${friendsSession.id}` })).statusCode).toBe(404);
    expect((await call(friend.token, { method: "GET", url: "/auth/me" })).statusCode).toBe(200);
  });

  it("signs out every other browser, and TVs only when asked", async () => {
    const { token } = await bootstrapAdmin();
    const other = (await login("owner", "owner-password")).json().token as string;
    const tv = await pairTv(token);

    const browsersOnly = await call(token, { method: "POST", url: "/auth/sessions/revoke-others", payload: {} });
    expect(browsersOnly.json()).toEqual({ browsers: 1, tvs: 0 });
    expect((await call(other, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
    expect((await call(tv.token, { method: "GET", url: "/devices/me" })).statusCode).toBe(200);

    const everything = await call(token, {
      method: "POST",
      url: "/auth/sessions/revoke-others",
      payload: { includeTvs: true },
    });
    expect(everything.json()).toEqual({ browsers: 0, tvs: 1 });
    expect((await call(tv.token, { method: "GET", url: "/devices/me" })).statusCode).toBe(401);
    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(200);
  });

  it("signs TVs out with a password change when asked to", async () => {
    const { token } = await bootstrapAdmin();
    const kept = await pairTv(token, "Kept");

    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "owner-password", newPassword: "second-password" },
    });
    expect((await call(kept.token, { method: "GET", url: "/devices/me" })).statusCode).toBe(200);

    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "second-password", newPassword: "third-password", signOutTvs: true },
    });
    expect((await call(kept.token, { method: "GET", url: "/devices/me" })).statusCode).toBe(401);
  });

  it("leaves a sign-in past its absolute lifetime off the list, whatever its stored expiry", async () => {
    const { token } = await bootstrapAdmin();
    const old = (await login("owner", "owner-password", "Old browser")).json().token as string;
    void old;
    await prisma.session.updateMany({
      where: { userAgent: "Old browser" },
      data: { createdAt: new Date(Date.now() - 91 * 24 * 60 * 60 * 1000), expiresAt: new Date(Date.now() + 86_400_000) },
    });
    const listed = (await signInsOf(token)).filter((s) => s.kind === "browser");
    expect(listed.map((s) => s.userAgent)).not.toContain("Old browser");
  });

  it("ends a sign-in past its absolute lifetime, however recently it was used", async () => {
    const { token } = await bootstrapAdmin();
    await prisma.session.updateMany({ data: { createdAt: new Date(Date.now() - 91 * 24 * 60 * 60 * 1000) } });
    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
  });
});

describe("recent activity", () => {
  it("records sign-ins, failed attempts, TV pairings and password changes, newest first", async () => {
    const { token } = await bootstrapAdmin();
    await login("owner", "wrong-password");
    await pairTv(token, "Bedroom");
    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "owner-password", newPassword: "another-password" },
    });

    const kinds = (await activityOf(token)).map((event) => event.kind);
    expect(kinds).toEqual(["password_changed", "tv_paired", "sign_in_failed", "sign_in"]);
  });

  it("is each account's own", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    expect((await activityOf(friend.token)).map((event) => event.kind)).toEqual(["sign_in"]);
  });
});

describe("your own account", () => {
  it("changes its username with the password, and refuses a taken one", async () => {
    const admin = await bootstrapAdmin();
    await inviteFriend(admin.token);

    const wrong = await call(admin.token, {
      method: "PATCH",
      url: "/auth/me",
      payload: { username: "boss", currentPassword: "nope-nope" },
    });
    expect(wrong.statusCode).toBe(401);

    const taken = await call(admin.token, {
      method: "PATCH",
      url: "/auth/me",
      payload: { username: "friend", currentPassword: "owner-password" },
    });
    expect(taken.statusCode).toBe(409);

    const renamed = await call(admin.token, {
      method: "PATCH",
      url: "/auth/me",
      payload: { username: "Boss", currentPassword: "owner-password" },
    });
    expect(renamed.json().account.username).toBe("boss");
    expect((await login("boss", "owner-password")).statusCode).toBe(200);
  });

  it("deletes a member's own account with its password; the admin hands admin on first", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    const adminDelete = await call(admin.token, {
      method: "DELETE",
      url: "/auth/me",
      payload: { currentPassword: "owner-password" },
    });
    expect(adminDelete.json().code).toBe("admin_cannot_delete");

    const wrong = await call(friend.token, { method: "DELETE", url: "/auth/me", payload: { currentPassword: "nope-nope" } });
    expect(wrong.statusCode).toBe(401);

    const deleted = await call(friend.token, {
      method: "DELETE",
      url: "/auth/me",
      payload: { currentPassword: "friend-password" },
    });
    expect(deleted.statusCode).toBe(204);
    expect(await prisma.account.count({ where: { username: "friend" } })).toBe(0);
    expect(await prisma.profile.count({ where: { name: "friend" } })).toBe(0);
  });
});

describe("the admin's recovery actions", () => {
  it("gives a member a temporary password, signing them out and asking for a new one", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    const wrongAdminPassword = await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/password`,
      payload: { newPassword: "temporary-pass", adminPassword: "nope-nope" },
    });
    expect(wrongAdminPassword.statusCode).toBe(401);

    const reset = await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/password`,
      payload: { newPassword: "temporary-pass", adminPassword: "owner-password" },
    });
    expect(reset.statusCode).toBe(204);
    expect((await call(friend.token, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);

    const signedIn = await login("friend", "temporary-pass");
    expect(signedIn.json().account.mustChangePassword).toBe(true);
    const token = signedIn.json().token as string;
    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "temporary-pass", newPassword: "friends-own-password" },
    });
    expect((await call(token, { method: "GET", url: "/auth/me" })).json().account.mustChangePassword).toBe(false);
  });

  it("keeps a temporary password to changing it until a new one is chosen", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/password`,
      payload: { newPassword: "temporary-pass", adminPassword: "owner-password" },
    });
    const token = (await login("friend", "temporary-pass")).json().token as string;

    const blocked = await call(token, { method: "GET", url: "/profiles" });
    expect(blocked.statusCode).toBe(403);
    expect(blocked.json().code).toBe("password_change_required");
    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(200);

    await call(token, {
      method: "POST",
      url: "/auth/password",
      payload: { currentPassword: "temporary-pass", newPassword: "friends-own-password" },
    });
    expect((await call(token, { method: "GET", url: "/profiles" })).statusCode).toBe(200);
  });

  it("can't be used by a member", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    const attempt = await call(friend.token, {
      method: "POST",
      url: `/auth/accounts/${admin.account.id}/password`,
      payload: { newPassword: "taken-over!", adminPassword: "friend-password" },
    });
    expect(attempt.statusCode).toBe(403);
  });

  it("hands admin to another account, which stops being the caller's", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);

    const handed = await call(admin.token, {
      method: "POST",
      url: `/auth/accounts/${friend.account.id}/make-admin`,
      payload: { adminPassword: "owner-password" },
    });
    expect(handed.statusCode).toBe(204);
    expect((await call(admin.token, { method: "GET", url: "/auth/me" })).json().isAdmin).toBe(false);
    expect((await call(friend.token, { method: "GET", url: "/auth/me" })).json().isAdmin).toBe(true);
    expect((await call(admin.token, { method: "GET", url: "/auth/accounts" })).statusCode).toBe(403);
    expect(await prisma.account.count({ where: { isAdmin: true } })).toBe(1);
  });

  it("leaves exactly one admin when two transfers race", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    const { invite } = (await call(admin.token, { method: "POST", url: "/auth/invites", payload: {} })).json();
    const third = (
      await call(null, {
        method: "POST",
        url: "/auth/register",
        payload: { inviteCode: invite.code, username: "third", password: "third-password" },
      })
    ).json() as { account: { id: string } };

    const results = await Promise.all(
      [friend.account.id, third.account.id].map((id) =>
        call(admin.token, {
          method: "POST",
          url: `/auth/accounts/${id}/make-admin`,
          payload: { adminPassword: "owner-password" },
        })
      )
    );
    expect(results.map((r) => r.statusCode).sort()).toEqual([204, 403]);
    expect(await prisma.account.count({ where: { isAdmin: true } })).toBe(1);
  });

  it("clears a forgotten PIN on a profile in their own account, with the account password", async () => {
    const admin = await bootstrapAdmin();
    const profile = (
      await call(admin.token, { method: "POST", url: "/profiles", payload: { name: "Locked", pin: "4321" } })
    ).json().profile as { id: string };

    const wrong = await call(admin.token, {
      method: "POST",
      url: `/profiles/${profile.id}/clear-pin`,
      payload: { password: "nope-nope" },
    });
    expect(wrong.statusCode).toBe(401);

    const cleared = await call(admin.token, {
      method: "POST",
      url: `/profiles/${profile.id}/clear-pin`,
      payload: { password: "owner-password" },
    });
    expect(cleared.statusCode).toBe(200);
    expect((await prisma.profile.findUniqueOrThrow({ where: { id: profile.id } })).pinHash).toBeNull();
    expect((await activityOf(admin.token)).map((event) => event.kind)).toContain("pin_cleared");
  });

  it("won't clear a PIN for a member, nor on another account's profile", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    const friendsProfile = await prisma.profile.findFirstOrThrow({ where: { accountId: friend.account.id } });
    await prisma.profile.update({ where: { id: friendsProfile.id }, data: { pinHash: "scrypt$whatever" } });

    const byMember = await call(friend.token, {
      method: "POST",
      url: `/profiles/${friendsProfile.id}/clear-pin`,
      payload: { password: "friend-password" },
    });
    expect(byMember.statusCode).toBe(403);

    const byAdmin = await call(admin.token, {
      method: "POST",
      url: `/profiles/${friendsProfile.id}/clear-pin`,
      payload: { password: "owner-password" },
    });
    expect(byAdmin.statusCode).toBe(404);
  });
});

describe("clearing a profile's data", () => {
  it("clears history or ratings and leaves the rest", async () => {
    const { token } = await bootstrapAdmin();
    const profile = await prisma.profile.create({ data: { name: "Mine", accountId: (await prisma.account.findFirstOrThrow()).id } });
    await prisma.watchEvent.create({ data: { profileId: profile.id, type: "movie", imdbId: "tt0000001", watchedAt: new Date() } });
    await prisma.rating.create({ data: { profileId: profile.id, type: "movie", imdbId: "tt0000001", rating: 8, ratedAt: new Date() } });
    const headers = { "x-profile-id": profile.id };

    const history = await call(token, { method: "POST", url: "/data/clear", payload: { history: true }, headers });
    expect(history.json().cleared.watchEvents).toBe(1);
    expect(await prisma.watchEvent.count({ where: { profileId: profile.id } })).toBe(0);
    expect(await prisma.rating.count({ where: { profileId: profile.id } })).toBe(1);

    const ratings = await call(token, { method: "POST", url: "/data/clear", payload: { ratings: true }, headers });
    expect(ratings.json().cleared.ratings).toBe(1);
    expect(await prisma.rating.count({ where: { profileId: profile.id } })).toBe(0);

    expect((await call(token, { method: "POST", url: "/data/clear", payload: {}, headers })).statusCode).toBe(400);
  });
});

describe("the TV list", () => {
  it("names the profile a TV last said it was using", async () => {
    const { token } = await bootstrapAdmin();
    const tv = await pairTv(token);
    const profile = (await call(token, { method: "POST", url: "/profiles", payload: { name: "Alex" } })).json()
      .profile as { id: string; name: string };

    await call(tv.token, {
      method: "PUT",
      url: "/devices/me/report",
      payload: { appVersion: "2.0.0", ok: true },
      headers: { "x-profile-id": profile.id },
    });

    const [device] = (await call(token, { method: "GET", url: "/devices" })).json().devices;
    expect(device).toMatchObject({ appVersion: "2.0.0", lastSyncOk: true, currentProfile: { id: profile.id, name: profile.name } });
  });
});

describe("the fetch command", () => {
  const runCli = async (...argv: string[]) => {
    const { run } = await import("../cli.js");
    const lines: string[] = [];
    // The command disconnects when it finishes; the suite's client reconnects
    // on its next query.
    const code = await run(argv, (line) => lines.push(line));
    return { code, output: lines.join("\n") };
  };

  it("resets a forgotten password from the server", async () => {
    const { token } = await bootstrapAdmin();
    const { code, output } = await runCli("reset-password", "OWNER");
    expect(code).toBe(0);
    const password = /Temporary password: (\S+)/.exec(output)?.[1];
    expect(password).toBeTruthy();

    expect((await call(token, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
    const signedIn = await login("owner", password!);
    expect(signedIn.json().account).toMatchObject({ isAdmin: true, mustChangePassword: true });
  });

  it("clears a forgotten PIN by name", async () => {
    const { token } = await bootstrapAdmin();
    await call(token, { method: "POST", url: "/profiles", payload: { name: "Kids", pin: "1234" } });

    const { code } = await runCli("clear-pin", "kids");
    expect(code).toBe(0);
    expect((await prisma.profile.findFirstOrThrow({ where: { name: "Kids" } })).pinHash).toBeNull();
  });

  it("refuses a misspelt option or an unquoted name instead of guessing", async () => {
    const { token } = await bootstrapAdmin();
    await call(token, { method: "POST", url: "/profiles", payload: { name: "Kids", pin: "1234" } });

    expect((await runCli("clear-pin", "Kids", "--accout", "owner")).code).toBe(2);
    expect((await runCli("clear-pin", "Kids", "Room")).code).toBe(2);
    expect((await runCli("accounts", "extra")).code).toBe(2);
    expect((await runCli("clear-pin", "Kids", "--__proto__", "owner")).code).toBe(2);
    expect((await runCli("clear-pin", "Kids", "--account", "")).code).toBe(2);
    expect((await prisma.profile.findFirstOrThrow({ where: { name: "Kids" } })).pinHash).not.toBeNull();
  });

  it("refuses an unknown account or an ambiguous profile name", async () => {
    const admin = await bootstrapAdmin();
    const friend = await inviteFriend(admin.token);
    await prisma.profile.create({ data: { name: "friend", accountId: admin.account.id, pinHash: "scrypt$x" } });
    await prisma.profile.updateMany({ where: { accountId: friend.account.id }, data: { pinHash: "scrypt$y" } });

    expect((await runCli("reset-password", "nobody")).code).toBe(2);
    expect((await runCli("clear-pin", "friend")).code).toBe(2);
    expect((await runCli("clear-pin", "friend", "--account", "friend")).code).toBe(0);
    expect((await prisma.profile.findFirstOrThrow({ where: { accountId: friend.account.id } })).pinHash).toBeNull();
  });
});
