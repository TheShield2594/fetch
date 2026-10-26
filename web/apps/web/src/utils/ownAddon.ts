import { runtimeConfig } from "../api";

/**
 * This server's own Stremio addon, installed for one profile. With the
 * profile's key the URL is the signed `/u/<profileId>/<key>/` form, which is
 * the only one an addon run with `ADDON_REQUIRE_SIGNED_URLS` serves; without
 * it (an API that predates the key) it falls back to the `/p/<profileId>/`
 * form.
 */
export const ownAddonManifestUrl = (profileId: string, profileKey: string | null | undefined): string =>
  profileKey
    ? `${runtimeConfig.getAddonBase()}/u/${profileId}/${profileKey}/manifest.json`
    : `${runtimeConfig.getAddonBase()}/p/${profileId}/manifest.json`;

/** Whether `manifestUrl` is this server's addon for `profileId`, in either URL form. */
export const isOwnAddonManifestUrl = (manifestUrl: string, profileId: string): boolean => {
  const base = runtimeConfig.getAddonBase();
  return (
    manifestUrl === `${base}/p/${profileId}/manifest.json` ||
    (manifestUrl.startsWith(`${base}/u/${profileId}/`) && manifestUrl.endsWith("/manifest.json"))
  );
};
