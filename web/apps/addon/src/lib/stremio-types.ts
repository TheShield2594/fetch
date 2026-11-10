/**
 * The Stremio wire shapes.
 *
 * Everything the API sends is typed by `@fetch/shared` (see api-contracts
 * there). What stays local is what this service itself emits, which never
 * crosses the API boundary.
 */

export type StremioMetaPreview = {
  id: string;
  type: string;
  name: string;
  poster?: string;
  posterShape: "poster";
  genres?: string[];
};

export type StremioSubtitle = {
  id: string;
  url: string;
  lang: string;
};

/** One episode of a series meta. Without these a client shows a series with nothing to play. */
export type StremioVideo = {
  id: string;
  title: string;
  season: number;
  episode: number;
  released?: string;
  thumbnail?: string;
};
