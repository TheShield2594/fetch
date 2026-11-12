/**
 * Signing in: the first-run admin, username and password, invites, and the
 * admin's list of accounts.
 *
 * Every call that signs someone in gets the session back as an HttpOnly cookie
 * and records the session's id, so a caller only has to move on to the next
 * screen.
 */

import { request, WEB_CLIENT_HEADERS } from "./client";
import { LEGACY_PROFILE_TOKEN_KEY, LEGACY_TOKEN_KEY, runtimeConfig, SESSION_TOKEN_PREFIX } from "./runtime";

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

/**
 * What a sign-in answers the website with. The session itself arrived as an
 * HttpOnly cookie on the same response; `sessionId` is its non-secret row id.
 */
type SignedIn = { account: Account; sessionId: string };

/**
 * Records the session the API just set a cookie for. The profile picked on this
 * device belonged to whoever was signed in before, so it goes too.
 */
const signIn = (result: SignedIn) => {
  runtimeConfig.clearProfileId();
  runtimeConfig.setSessionId(result.sessionId);
  return result.account;
};

/** How long the one-off move into a cookie may hold up the first render. */
const MIGRATION_TIMEOUT_MS = 8000;

/**
 * Moves a sign-in from before session cookies into one, so upgrading doesn't
 * sign anybody out. Runs once, before the app renders.
 *
 * The session token (and a PIN profile's access token) used to sit in
 * localStorage. If one is there, it is sent once to `POST /auth/session`,
 * which answers with the same session as an HttpOnly cookie; both copies are
 * then deleted. A token the API turns down is deleted too, and the app opens
 * on the sign-in screen as it would have anyway. If the API can't be reached,
 * the token stays where it was for the next load to try again, and this load
 * uses it as a bearer header so the app still works.
 *
 * A token without the session prefix is the server's `API_TOKEN`, stored by a
 * browser from before accounts existed. It is taken out of storage and kept in
 * memory, for the first-run form to offer.
 */
export async function migrateLegacySession(): Promise<void> {
  let legacy: string;
  let legacyProfileToken: string;
  try {
    legacy = window.localStorage.getItem(LEGACY_TOKEN_KEY)?.trim() ?? "";
    legacyProfileToken = window.localStorage.getItem(LEGACY_PROFILE_TOKEN_KEY)?.trim() ?? "";
  } catch {
    // Storage unavailable (private mode, blocked site data): nothing to move.
    return;
  }

  const forgetLegacy = () => {
    window.localStorage.removeItem(LEGACY_TOKEN_KEY);
    window.localStorage.removeItem(LEGACY_PROFILE_TOKEN_KEY);
  };

  if (!legacy) {
    if (legacyProfileToken) forgetLegacy();
    return;
  }

  if (!legacy.startsWith(SESSION_TOKEN_PREFIX)) {
    runtimeConfig.setLegacyApiToken(legacy);
    forgetLegacy();
    return;
  }

  const headers: Record<string, string> = { ...WEB_CLIENT_HEADERS, Authorization: `Bearer ${legacy}` };
  const profileId = runtimeConfig.getProfileId();
  // The PIN capability moves too, so a locked profile doesn't ask for its PIN
  // again just because Fetch was upgraded.
  if (profileId && legacyProfileToken) {
    headers["x-profile-id"] = profileId;
    headers["x-profile-token"] = legacyProfileToken;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), MIGRATION_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${runtimeConfig.getApiBase()}/auth/session`, {
      method: "POST",
      credentials: "include",
      headers,
      signal: controller.signal,
    });
  } catch {
    runtimeConfig.setLegacyBearer(legacy);
    return;
  } finally {
    clearTimeout(timeout);
  }

  if (response.ok) {
    const body = (await response.json().catch(() => null)) as { sessionId?: unknown } | null;
    forgetLegacy();
    if (typeof body?.sessionId === "string" && body.sessionId) {
      runtimeConfig.setSessionId(body.sessionId);
    } else {
      // An API too old to know the route can't have set a cookie either.
      runtimeConfig.setLegacyBearer(legacy);
    }
    return;
  }

  if (response.status === 401 || response.status === 403 || response.status === 400) {
    // The token was already dead: signed out elsewhere, expired, or revoked.
    forgetLegacy();
    runtimeConfig.clearSession();
    return;
  }

  // Anything else (a 5xx, a proxy error page) says nothing about the token.
  runtimeConfig.setLegacyBearer(legacy);
}

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
  /**
   * Ends this device's session: the API deletes it and expires both cookies.
   * Signs out locally even if the server can't be reached — the cookie is then
   * left behind, but its session is still one a password change or another
   * sign-out can end, and the next request that reaches the API with it after
   * it expires takes it away.
   */
  async logout() {
    try {
      await request<void>("/auth/logout", { method: "POST" });
    } finally {
      runtimeConfig.clearProfileId();
      runtimeConfig.clearSession();
    }
  },
  getMe() {
    return request<{ account: Account | null; isAdmin: boolean; sessionId?: string | null | undefined }>("/auth/me");
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
    runtimeConfig.clearSession();
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
