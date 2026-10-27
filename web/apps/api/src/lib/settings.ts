import { readKv, writeKv } from "./kv.js";
import { type IntegrationOwner, LEGACY_OWNER, ownerKvKey } from "./owner-keys.js";

export const LANGUAGE_KV_KEY = "settings:language";
export const REGION_KV_KEY = "settings:region";
export const SPOILER_PROTECTION_KV_KEY = "settings:spoilerProtection";

// The metadata language is the install's: titles and descriptions are fetched
// once and shared by every account, so there is one language they are in.
export const getLanguageSetting = async (): Promise<string> => (await readKv(LANGUAGE_KV_KEY)) ?? "en-US";

// Region and spoiler protection are each account's own. An account that has
// never set one gets the install's value, the one set before accounts
// existed, and then the built-in default.
const readAccountPreference = async (key: string, owner: IntegrationOwner | undefined): Promise<string | null> =>
  (owner && owner !== LEGACY_OWNER ? await readKv(ownerKvKey(key, owner)) : null) ?? (await readKv(key));

export const getRegionSetting = async (owner?: IntegrationOwner): Promise<string> =>
  (await readAccountPreference(REGION_KV_KEY, owner)) ?? "US";

export const getSpoilerProtection = async (owner?: IntegrationOwner): Promise<boolean> =>
  (await readAccountPreference(SPOILER_PROTECTION_KV_KEY, owner)) === "true";

// Paired with the getters above rather than left as upserts at the call site,
// so that the write and the cache invalidation it needs cannot drift apart —
// the same argument `lib/secret-store.ts` makes for the credential keys.

export const setLanguageSetting = (language: string): Promise<void> => writeKv(LANGUAGE_KV_KEY, language);

export const setRegionSetting = (region: string, owner: IntegrationOwner): Promise<void> =>
  writeKv(ownerKvKey(REGION_KV_KEY, owner), region);

export const setSpoilerProtection = (enabled: boolean, owner: IntegrationOwner): Promise<void> =>
  writeKv(ownerKvKey(SPOILER_PROTECTION_KV_KEY, owner), String(enabled));
