/**
 * A profile's collections: custom Home rows on the TV, each a set of folders
 * filled from addon catalogs, TMDB, Trakt or this website's own lists.
 */

import { request } from "./client";
import type { CatalogMeta, Collection, CollectionSource, CollectionsDocument } from "./types";

export const collectionsApi = {
  getCollections() {
    return request<CollectionsDocument>("/collections");
  },
  // `baseUpdatedAt` is what was last read; the server answers 409 rather than
  // overwrite a save made on the TV in the meantime.
  saveCollections(collections: Collection[], baseUpdatedAt: string | null) {
    return request<CollectionsDocument>("/collections", {
      method: "PUT",
      body: JSON.stringify({ collections, baseUpdatedAt }),
    });
  },
  previewCollectionSource(source: CollectionSource) {
    return request<{ metas: CatalogMeta[] }>("/collections/preview", {
      method: "POST",
      body: JSON.stringify({ source }),
    });
  },
};
