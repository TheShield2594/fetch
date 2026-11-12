/**
 * Signing in: the first-run admin, username and password, invites, and the
 * admin's list of accounts.
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
  /** Set when someone else chose the password (the admin, or the server's `fetch` command). */
  mustChangePassword?: boolean;
};

/** One place the account is signed in: a browser session or a paired TV. */
export type SignIn =
  | {
      kind: "browser";
      id: string;
      /** This browser. */
      current: boolean;
      userAgent: string | null;
      ipAddress: string | null;
      createdAt: string;
      lastUsedAt: string;
      expiresAt: string;
    }
  | {
      kind: "tv";
      id: string;
      current: false;
      name: string;
      appVersion: string | null;
      createdAt: string;
      lastUsedAt: string | null;
    };

export type AccountEvent = {
  id: string;
  kind: string;
  detail: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
};

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
  /** Signs every other browser out; with `signOutTvs`, every TV too. */
  changePassword(currentPassword: string, newPassword: string, options: { signOutTvs?: boolean } = {}) {
    return request<void>("/auth/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword, ...(options.signOutTvs ? { signOutTvs: true } : {}) }),
    });
  },
  changeUsername(username: string, currentPassword: string) {
    return request<{ account: Account }>("/auth/me", {
      method: "PATCH",
      body: JSON.stringify({ username, currentPassword }),
    });
  },
  /** Deletes the signed-in account and everything in it, then signs out here. */
  async deleteMyAccount(currentPassword: string) {
    await request<void>("/auth/me", { method: "DELETE", body: JSON.stringify({ currentPassword }) });
    runtimeConfig.clearProfileId();
    runtimeConfig.setToken("");
  },
  getSignIns() {
    return request<{ sessions: SignIn[] }>("/auth/sessions");
  },
  /** Ends one browser's sign-in. A TV is ended with `revokeDevice`. */
  revokeSession(id: string) {
    return request<void>(`/auth/sessions/${encodeURIComponent(id)}`, { method: "DELETE" });
  },
  /** Ends every other browser's sign-in, and with `includeTvs` every TV's. */
  revokeOtherSessions(includeTvs: boolean) {
    return request<{ browsers: number; tvs: number }>("/auth/sessions/revoke-others", {
      method: "POST",
      body: JSON.stringify({ includeTvs }),
    });
  },
  getActivity() {
    return request<{ events: AccountEvent[] }>("/auth/activity");
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
    /** `lastUsedAt`: when a media server last reached a webhook with this key, to the minute; null if none has. */
    return request<{ configured: boolean; createdAt: string | null; lastUsedAt?: string | null }>("/auth/webhook-key");
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
  /** A temporary password for a member; they choose their own at their next sign-in. */
  setMemberPassword(id: string, newPassword: string, adminPassword: string) {
    return request<void>(`/auth/accounts/${encodeURIComponent(id)}/password`, {
      method: "POST",
      body: JSON.stringify({ newPassword, adminPassword }),
    });
  },
  /** Hands the admin role to another account. The caller stops being the admin. */
  makeAdmin(id: string, adminPassword: string) {
    return request<void>(`/auth/accounts/${encodeURIComponent(id)}/make-admin`, {
      method: "POST",
      body: JSON.stringify({ adminPassword }),
    });
  },
};

/** The invite code in an `/invite/<code>` link, if this page was opened from one. */
export const inviteCodeFromPath = (pathname: string): string | null => {
  const match = /^\/invite\/([A-Za-z0-9_-]+)\/?$/.exec(pathname);
  return match?.[1] ?? null;
};
