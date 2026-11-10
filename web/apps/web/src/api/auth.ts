/**
 * Signing in: the first-run admin, username (or email) and password, invites,
 * the account's email address and the links mailed to it, and the admin's list
 * of accounts.
 *
 * Every call that signs someone in stores the session token it gets back, so a
 * caller only has to move on to the next screen.
 */

import { request } from "./client";
import { runtimeConfig } from "./runtime";

export type Account = {
  id: string;
  username: string;
  isAdmin: boolean;
  /** Optional. Older servers leave these out, so they are optional here too. */
  email?: string | null;
  emailVerified?: boolean;
  emailNotifications?: boolean;
};

export type AuthStatus = { setupRequired: boolean; emailEnabled?: boolean };

export type Invite = {
  id: string;
  createdAt: string;
  expiresAt: string;
  usedAt: string | null;
  usedBy: string | null;
  email?: string | null;
};

export type AccountSummary = Account & { createdAt: string; profileCount: number };

type SignedIn = { token: string; account: Account };

/**
 * Starts a session for `token`. The profile picked on this device belonged to
 * whoever was signed in before, so it goes too.
 */
const signIn = (result: SignedIn) => {
  runtimeConfig.clearProfileId();
  runtimeConfig.setToken(result.token);
  return result.account;
};

export const authApi = {
  getAuthStatus() {
    return request<AuthStatus>("/auth/status");
  },
  /** The first account, which becomes the admin. Needs the server's API token. */
  async bootstrapAdmin(apiToken: string, username: string, password: string, email = "") {
    return signIn(
      await request<SignedIn>("/auth/bootstrap", {
        method: "POST",
        headers: { Authorization: `Bearer ${apiToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({ username, password, ...(email ? { email } : {}) }),
      })
    );
  },
  /** `username` may be the account's email address instead. */
  async login(username: string, password: string) {
    return signIn(
      await request<SignedIn>("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) })
    );
  },
  async register(inviteCode: string, username: string, password: string, email = "") {
    return signIn(
      await request<SignedIn>("/auth/register", {
        method: "POST",
        body: JSON.stringify({ inviteCode, username, password, ...(email ? { email } : {}) }),
      })
    );
  },
  /** Sets, changes or (with an empty string) removes this account's address. */
  setEmail(email: string, currentPassword: string) {
    return request<{ account: Account; verificationSent: boolean }>("/auth/email", {
      method: "PUT",
      body: JSON.stringify({ email: email || null, currentPassword }),
    });
  },
  resendEmailVerification() {
    return request<void>("/auth/email/resend", { method: "POST" });
  },
  /** From the link in the confirmation mail. Works signed out. */
  verifyEmail(token: string) {
    return request<void>("/auth/email/verify", { method: "POST", body: JSON.stringify({ token }) });
  },
  setEmailNotifications(enabled: boolean) {
    return request<{ account: Account }>("/auth/email/notifications", {
      method: "PUT",
      body: JSON.stringify({ enabled }),
    });
  },
  /** Mails a reset link if the address belongs to an account. Answers the same either way. */
  forgotPassword(email: string) {
    return request<{ ok: true }>("/auth/password/forgot", { method: "POST", body: JSON.stringify({ email }) });
  },
  /** From the link in the reset mail: sets the password and signs this device in. */
  async resetPassword(token: string, password: string) {
    return signIn(
      await request<SignedIn>("/auth/password/reset", { method: "POST", body: JSON.stringify({ token, password }) })
    );
  },
  /** Ends this device's session. Signs out locally even if the server can't be reached. */
  async logout() {
    try {
      await request<void>("/auth/logout", { method: "POST" });
    } finally {
      runtimeConfig.clearProfileId();
      runtimeConfig.setToken("");
    }
  },
  getMe() {
    return request<{ account: Account | null; isAdmin: boolean }>("/auth/me");
  },
  changePassword(currentPassword: string, newPassword: string) {
    return request<void>("/auth/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    });
  },
  /** With `email`, the server also mails the link there. */
  createInvite(expiresInDays?: number, email?: string) {
    return request<{
      invite: { id: string; code: string; expiresAt: string };
      url: string | null;
      emailed?: boolean;
    }>("/auth/invites", {
      method: "POST",
      body: JSON.stringify({ ...(expiresInDays ? { expiresInDays } : {}), ...(email ? { email } : {}) }),
    });
  },
  getInvites() {
    return request<{ invites: Invite[] }>("/auth/invites");
  },
  revokeInvite(id: string) {
    return request<void>(`/auth/invites/${encodeURIComponent(id)}`, { method: "DELETE" });
  },
  getWebhookKey() {
    return request<{ configured: boolean; createdAt: string | null }>("/auth/webhook-key");
  },
  /** Makes this account's webhook key, replacing any it had. The key is shown this once. */
  createWebhookKey() {
    return request<{ key: string; createdAt: string }>("/auth/webhook-key", { method: "POST" });
  },
  removeWebhookKey() {
    return request<void>("/auth/webhook-key", { method: "DELETE" });
  },
  getAccounts() {
    return request<{ accounts: AccountSummary[] }>("/auth/accounts");
  },
  deleteAccount(id: string) {
    return request<void>(`/auth/accounts/${encodeURIComponent(id)}`, { method: "DELETE" });
  },
};

/** The invite code in an `/invite/<code>` link, if this page was opened from one. */
export const inviteCodeFromPath = (pathname: string): string | null => {
  const match = /^\/invite\/([A-Za-z0-9_-]+)\/?$/.exec(pathname);
  return match?.[1] ?? null;
};

/** What an emailed link opened this page for: confirming an address, or resetting a password. */
export type EmailLink = { kind: "verify" | "reset"; token: string };

export const emailLinkFromPath = (pathname: string): EmailLink | null => {
  const match = /^\/(verify-email|reset-password)\/([A-Za-z0-9_-]+)\/?$/.exec(pathname);
  if (!match?.[2]) return null;
  return { kind: match[1] === "verify-email" ? "verify" : "reset", token: match[2] };
};
