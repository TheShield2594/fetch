import { readSecretKv } from "./secret-store.js";
import type { StremioMetaPreview } from "./types.js";
import { isImdbId } from "./content-id.js";

const RPDB_API_KEY_KV = "rpdb:apiKey";
export const RPDB_BASE_URL = "https://api.ratingposterdb.com";

export const getRpdbApiKey = async (): Promise<string | null> => {
  const stored = await readSecretKv(RPDB_API_KEY_KV);
  return stored?.trim() || null;
};

export const buildRpdbPosterUrl = (rpdbKey: string, imdbId: string): string =>
  `${RPDB_BASE_URL}/${encodeURIComponent(rpdbKey)}/imdb/poster-default/${encodeURIComponent(imdbId)}.jpg`;

export const withRpdbPoster = (
  imdbId: string,
  fallback: string | null | undefined,
  rpdbKey: string | null
): string | null =>
  // RPDB posters are looked up by IMDb id; a title known by another id keeps its own.
  rpdbKey && isImdbId(imdbId) ? buildRpdbPosterUrl(rpdbKey, imdbId) : (fallback ?? null);

export const applyRpdbToMetaList = (
  metas: StremioMetaPreview[],
  rpdbKey: string | null
): StremioMetaPreview[] => {
  if (!rpdbKey) return metas;
  return metas.map((m) => (isImdbId(m.id) ? { ...m, poster: buildRpdbPosterUrl(rpdbKey, m.id) } : m));
};

export { RPDB_API_KEY_KV };
