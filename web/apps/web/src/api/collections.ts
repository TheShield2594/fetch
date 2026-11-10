/**
 * A profile's collections: custom Home rows on the TV, each a set of folders
 * filled from addon catalogs, TMDB, Trakt or this website's own lists.
 */

import { request } from "./client";
import type { CatalogMeta, Collection, CollectionSource, CollectionsDocument } from "./types";

/** An addon's manifest as the server read it: what the builder's catalog picker needs. */
export type AddonManifestSummary = {
  id: string;
  name: string;
  /** The addon's base address, with no `/manifest.json`. */
  transportUrl: string;
  catalogs: { type: string; id: string; name: string }[];
};

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
  // Both go through the server: the site's Content-Security-Policy only lets
  // the browser reach this deployment's own origins, and an addon can be anywhere.
  readAddonManifest(manifestUrl: string) {
    return request<{ manifest: AddonManifestSummary }>("/collections/addon-manifest", {
      method: "POST",
      body: JSON.stringify({ manifestUrl }),
    });
  },
  previewCollectionSource(source: CollectionSource) {
    return request<{ metas: CatalogMeta[] }>("/collections/preview", {
      method: "POST",
      body: JSON.stringify({ source }),
    });
  },
};
