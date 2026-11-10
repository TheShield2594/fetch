import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance, InjectOptions } from "fastify";
import { prisma } from "../lib/prisma.js";
import { buildIntApp } from "../lib/test-fixtures/int-db.js";
import { setMailTransportForTests } from "../lib/mailer.js";

// Email on accounts, end to end through the real auth gate: an address given
// at sign-up, signing in with it, confirming it from the mailed link, a
// forgotten password reset by mail, and invites sent by email. The SMTP
// connection is replaced, so what would have been sent is read back here.

const API_TOKEN = "int-test-api-token";
process.env.API_TOKEN = API_TOKEN;
process.env.FETCH_WEB_PUBLIC = "https://fetch.test";
process.env.SMTP_HOST = "smtp.test";

type SentMail = { to: string; subject: string; text: string };
const sent: SentMail[] = [];
const sendMail = vi.fn(async (mail: SentMail) => {
  sent.push(mail);
  return {};
});

let app: FastifyInstance;

beforeAll(async () => {
  setMailTransportForTests({ sendMail } as never);
  const [{ registerAuthGate }, auth] = await Promise.all([import("../lib/auth-gate.js"), import("./auth.js")]);
  app = await buildIntApp(async (instance) => {
    registerAuthGate(instance);
    await instance.register(auth.default);
  });
});

afterAll(() => setMailTransportForTests(null));

beforeEach(() => {
  sent.length = 0;
  sendMail.mockClear();
});

const call = (bearer: string | null, options: InjectOptions) =>
  app.inject({
    ...options,
    headers: { ...(bearer ? { authorization: `Bearer ${bearer}` } : {}), ...options.headers },
  });

/** The secret at the end of the link in the last mail, after `prefix`. */
const linkSecret = (prefix: string): string => {
  const mail = sent.at(-1);
  const match = mail ? new RegExp(`https://fetch\\.test/${prefix}/([A-Za-z0-9_-]+)`).exec(mail.text) : null;
  if (!match?.[1]) throw new Error(`no /${prefix}/ link in the last mail`);
  return match[1];
};

const bootstrapAdmin = async (email?: string) => {
  const response = await call(API_TOKEN, {
    method: "POST",
    url: "/auth/bootstrap",
    payload: { username: "owner", password: "owner-password", ...(email ? { email } : {}) },
  });
  expect(response.statusCode).toBe(201);
  return response.json<{ token: string; account: Record<string, unknown>; verificationSent: boolean }>();
};

describe("status", () => {
  it("says email is available when SMTP and the website address are set", async () => {
    expect((await call(null, { method: "GET", url: "/auth/status" })).json()).toEqual({
      setupRequired: true,
      emailEnabled: true,
    });
  });
});

describe("an address at sign-up", () => {
  it("stores it lowercased, unconfirmed, and mails a confirmation link", async () => {
    const { account, verificationSent } = await bootstrapAdmin("Owner@Example.com");
    expect(account).toMatchObject({ email: "owner@example.com", emailVerified: false, emailNotifications: false });
    expect(verificationSent).toBe(true);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.to).toBe("owner@example.com");
  });

  it("refuses something that isn't an address", async () => {
    const response = await call(API_TOKEN, {
      method: "POST",
      url: "/auth/bootstrap",
      payload: { username: "owner", password: "owner-password", email: "not-an-address" },
    });
    expect(response.statusCode).toBe(400);
  });

  it("is optional", async () => {
    const { account, verificationSent } = await bootstrapAdmin();
    expect(account.email).toBeNull();
    expect(verificationSent).toBe(false);
    expect(sent).toHaveLength(0);
  });

  it("confirms from the mailed link, once", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    const secret = linkSecret("verify-email");

    const verified = await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: secret } });
    expect(verified.statusCode).toBe(204);
    const me = await call(token, { method: "GET", url: "/auth/me" });
    expect(me.json().account).toMatchObject({ email: "owner@example.com", emailVerified: true });

    const again = await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: secret } });
    expect(again.statusCode).toBe(400);
  });
});

describe("signing in by email", () => {
  it("accepts the address in place of the username", async () => {
    await bootstrapAdmin("owner@example.com");
    const response = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "OWNER@example.com", password: "owner-password" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().account.username).toBe("owner");
  });

  it("answers an unknown address like a wrong password", async () => {
    await bootstrapAdmin("owner@example.com");
    const response = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "nobody@example.com", password: "owner-password" },
    });
    expect(response.statusCode).toBe(401);
    expect(response.json().error).toBe("Incorrect username or password");
  });
});

describe("changing the address", () => {
  it("needs the current password, and un-confirms the new address", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: linkSecret("verify-email") } });

    const wrong = await call(token, {
      method: "PUT",
      url: "/auth/email",
      payload: { email: "new@example.com", currentPassword: "nope-nope-nope" },
    });
    expect(wrong.statusCode).toBe(401);

    const changed = await call(token, {
      method: "PUT",
      url: "/auth/email",
      payload: { email: "new@example.com", currentPassword: "owner-password" },
    });
    expect(changed.statusCode).toBe(200);
    expect(changed.json()).toMatchObject({
      account: { email: "new@example.com", emailVerified: false },
      verificationSent: true,
    });
    expect(sent.at(-1)?.to).toBe("new@example.com");
  });

  it("refuses an address another account has", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    await prisma.account.create({ data: { username: "friend", passwordHash: "x", email: "friend@example.com" } });
    const response = await call(token, {
      method: "PUT",
      url: "/auth/email",
      payload: { email: "friend@example.com", currentPassword: "owner-password" },
    });
    expect(response.statusCode).toBe(409);
    expect(response.json().code).toBe("email_taken");
  });

  it("removes it, and turns email notifications off with it", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: linkSecret("verify-email") } });
    const on = await call(token, { method: "PUT", url: "/auth/email/notifications", payload: { enabled: true } });
    expect(on.json().account.emailNotifications).toBe(true);

    const removed = await call(token, {
      method: "PUT",
      url: "/auth/email",
      payload: { email: null, currentPassword: "owner-password" },
    });
    expect(removed.json().account).toMatchObject({ email: null, emailNotifications: false });
  });
});

describe("email notifications", () => {
  it("can only be turned on for a confirmed address", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    const early = await call(token, { method: "PUT", url: "/auth/email/notifications", payload: { enabled: true } });
    expect(early.statusCode).toBe(400);
    expect(early.json().code).toBe("email_unverified");
  });
});

describe("forgotten password", () => {
  it("mails a reset link to a confirmed address, which sets the password and signs out everywhere", async () => {
    const { token: oldSession } = await bootstrapAdmin("owner@example.com");
    await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: linkSecret("verify-email") } });

    const forgot = await call(null, { method: "POST", url: "/auth/password/forgot", payload: { email: "owner@example.com" } });
    expect(forgot.statusCode).toBe(202);
    await vi.waitFor(() => expect(sent.at(-1)?.subject).toBe("Reset your Fetch password"));
    const secret = linkSecret("reset-password");

    const reset = await call(null, {
      method: "POST",
      url: "/auth/password/reset",
      payload: { token: secret, password: "brand-new-password" },
    });
    expect(reset.statusCode).toBe(200);
    expect(reset.json().token).toMatch(/^fs1_/);

    expect((await call(oldSession, { method: "GET", url: "/auth/me" })).statusCode).toBe(401);
    const login = await call(null, {
      method: "POST",
      url: "/auth/login",
      payload: { username: "owner", password: "brand-new-password" },
    });
    expect(login.statusCode).toBe(200);

    const reused = await call(null, {
      method: "POST",
      url: "/auth/password/reset",
      payload: { token: secret, password: "another-password" },
    });
    expect(reused.statusCode).toBe(400);
  });

  it("answers the same for an unknown or unconfirmed address, and mails nothing", async () => {
    await bootstrapAdmin("owner@example.com");
    sent.length = 0;
    for (const email of ["owner@example.com", "nobody@example.com"]) {
      const response = await call(null, { method: "POST", url: "/auth/password/forgot", payload: { email } });
      expect(response.statusCode).toBe(202);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(sent).toHaveLength(0);
  });

  it("refuses an expired link", async () => {
    await bootstrapAdmin("owner@example.com");
    await call(null, { method: "POST", url: "/auth/email/verify", payload: { token: linkSecret("verify-email") } });
    await call(null, { method: "POST", url: "/auth/password/forgot", payload: { email: "owner@example.com" } });
    await vi.waitFor(() => expect(sent.at(-1)?.subject).toBe("Reset your Fetch password"));
    await prisma.account.updateMany({ data: { passwordResetExpiresAt: new Date(Date.now() - 1000) } });

    const response = await call(null, {
      method: "POST",
      url: "/auth/password/reset",
      payload: { token: linkSecret("reset-password"), password: "brand-new-password" },
    });
    expect(response.statusCode).toBe(400);
  });
});

describe("emailed invites", () => {
  it("mails the link, and signing up with that address confirms it", async () => {
    const { token } = await bootstrapAdmin();
    const created = await call(token, {
      method: "POST",
      url: "/auth/invites",
      payload: { email: "Friend@Example.com" },
    });
    expect(created.statusCode).toBe(201);
    expect(created.json()).toMatchObject({ emailed: true, invite: { email: "friend@example.com" } });
    expect(sent.at(-1)).toMatchObject({ to: "friend@example.com", subject: "You're invited to Fetch" });
    expect(sent.at(-1)?.text).toContain("owner has invited you");

    const registered = await call(null, {
      method: "POST",
      url: "/auth/register",
      payload: {
        inviteCode: linkSecret("invite"),
        username: "friend",
        password: "friend-password",
        email: "friend@example.com",
      },
    });
    expect(registered.statusCode).toBe(201);
    expect(registered.json()).toMatchObject({
      account: { email: "friend@example.com", emailVerified: true },
      verificationSent: false,
    });
  });

  it("refuses to register with an address another account has", async () => {
    const { token } = await bootstrapAdmin("owner@example.com");
    const { invite } = (await call(token, { method: "POST", url: "/auth/invites", payload: {} })).json();
    const response = await call(null, {
      method: "POST",
      url: "/auth/register",
      payload: { inviteCode: invite.code, username: "friend", password: "friend-password", email: "owner@example.com" },
    });
    expect(response.statusCode).toBe(409);
    expect(response.json().code).toBe("email_taken");
  });
});
