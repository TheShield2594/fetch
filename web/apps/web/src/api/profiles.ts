/**
 * The household: who is using this install, and the PIN gate in front of
 * the ones that have one.
 */

import { request } from "./client";
import type {
  Profile,
  ProfileSettingsUpdate,
  ProfileSummary,
} from "./types";

export const profilesApi = {
  // Profiles
  getProfiles() {
    return request<{ profiles: Profile[] }>("/profiles");
  },
  createProfile(payload: { name: string; pin?: string | undefined } & ProfileSettingsUpdate) {
    return request<{ profile: Profile }>("/profiles", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  // The signed capability this mints, which later requests for a PIN-protected
  // profile need, comes back as an HttpOnly cookie rather than in the body: the
  // page never holds it, and verifying another profile replaces it. The API
  // sets it as soon as it answers, so a caller that was overtaken meanwhile
  // can't take it back; the worst that leaves is one extra PIN prompt.
  verifyProfile(profileId: string, pin?: string) {
    return request<{ id: string; name: string }>(`/profiles/${encodeURIComponent(profileId)}/verify`, {
      method: "POST",
      body: JSON.stringify(pin ? { pin } : {}),
    });
  },
  /**
   * Drops this browser's profile token (an HttpOnly cookie the page can't
   * touch), so the profile in use needs its PIN again. Signs nothing else out.
   */
  lockProfile() {
    return request<void>("/profiles/lock", { method: "POST" });
  },
  updateProfile(
    profileId: string,
    payload: { name?: string | undefined; pin?: string | null | undefined; currentPin?: string | undefined } & ProfileSettingsUpdate
  ) {
    return request<{ profile: Profile }>(`/profiles/${encodeURIComponent(profileId)}`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    });
  },
  getProfileSummary(profileId: string) {
    return request<ProfileSummary>(`/profiles/${encodeURIComponent(profileId)}/summary`);
  },
  // `currentPin` is required by the server for a PIN-protected profile unless
  // the stored profile token was minted for that same profile — deleting one
  // takes its whole history with it, so it is gated like a PIN change.
  /** The admin clears a forgotten PIN on a profile in their own account. */
  clearProfilePin(profileId: string, password: string) {
    return request<{ profile: Profile }>(`/profiles/${encodeURIComponent(profileId)}/clear-pin`, {
      method: "POST",
      body: JSON.stringify({ password }),
    });
  },
  /**
   * Replaces the profile's Stremio addon URL. The old one stops working at
   * once; Stremio needs the new one installed to keep syncing.
   */
  rotateAddonUrl(profileId: string, currentPin?: string) {
    return request<{ stremioManifestPath: string | null; stremioManifestUrl: string | null; stremioProfileKey: string | null }>(
      `/profiles/${encodeURIComponent(profileId)}/addon-url/rotate`,
      {
        method: "POST",
        body: JSON.stringify(currentPin ? { currentPin } : {}),
      }
    );
  },
  deleteProfile(profileId: string, currentPin?: string) {
    return request<void>(`/profiles/${encodeURIComponent(profileId)}`, {
      method: "DELETE",
      ...(currentPin ? { body: JSON.stringify({ currentPin }) } : {}),
    });
  },
};
