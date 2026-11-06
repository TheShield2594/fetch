/**
 * The TTL caches in front of the API.
 *
 * Caches for genuinely global data (genres, RPDB config, the spoiler-protection
 * preference) stay unkeyed. Anything derived from a profile-scoped endpoint —
 * the manifest, which lists that profile's lists, the enabled-catalog config,
 * and the discovery catalogs, which include personal recommendations — is
 * keyed by profile so one profile never serves another's rows.
 */

export type CacheEntry<T> = { data: T; expiry: number };

// A signed request's answers are cached apart from an unsigned one's for the
// same profile. Only a signed request can read a PIN-protected profile (the API
// refuses the unsigned one), so sharing a key would let `/p/<uuid>` be served,
// from cache, what only `/u/<uuid>/<key>` was allowed to see.
export const cacheKeyFor = (profile: { profileId: string | null; profileKey: string | null }) =>
  `${profile.profileId ?? "-"}${profile.profileKey ? ":signed" : ""}`;

// Profile ids reaching these maps are UUID-shaped but not verified to exist, so
// bound the maps rather than let unknown ids accumulate entries indefinitely.
const MAX_CACHE_ENTRIES = 512;

export const cacheSet = <T>(cache: Map<string, CacheEntry<T>>, key: string, entry: CacheEntry<T>) => {
  cache.set(key, entry);
  if (cache.size > MAX_CACHE_ENTRIES) {
    // Map iterates in insertion order, so this drops the least recently added.
    const oldest = cache.keys().next();
    if (!oldest.done) cache.delete(oldest.value);
  }
};
