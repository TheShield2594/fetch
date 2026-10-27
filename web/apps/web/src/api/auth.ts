/**
 * Signing in: the first-run admin, username and password, invites, and the
 * admin's list of accounts.
 *
 * Every call that signs someone in stores the session token it gets back, so a
 * caller only has to move on to the next screen.
 */

import { request } from "./client";
import { runtimeConfig } from "./runtime";

export type Account = { id: string; username: string; isAdmin: boolean };

export type Invite = {
  id: string;
  createdAt: string;
  expiresAt: string;
  usedAt: string | null;
  usedBy: string | null;
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
    return request<{ setupRequired: boolean }>("/auth/status");
  },
  /** The first account, which becomes the admin. Needs the server's API token. */
  async bootstrapAdmin(apiToken: string, username: string, password: string) {
    return signIn(
      await request<SignedIn>("/auth/bootstrap", {
        method: "POST",
        headers: { Authorization: `Bearer ${apiToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      })
    );
  },
  async login(username: string, password: string) {
    return signIn(
      await request<SignedIn>("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) })
    );
  },
  async register(inviteCode: string, username: string, password: string) {
    return signIn(
      await request<SignedIn>("/auth/register", {
        method: "POST",
        body: JSON.stringify({ inviteCode, username, password }),
      })
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
  createInvite(expiresInDays?: number) {
    return request<{ invite: { id: string; code: string; expiresAt: string }; url: string | null }>(
      "/auth/invites",
      { method: "POST", body: JSON.stringify(expiresInDays ? { expiresInDays } : {}) }
    );
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
