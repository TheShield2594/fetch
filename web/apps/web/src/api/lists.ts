/**
 * The watchlist, the collection, and every list the user made.
 */

import { request } from "./client";
import type {
  CatalogList,
  CatalogMeta,
  ItemListMembership,
  ListItemWithMeta,
  ListLink,
  ListLinkProvider,
  ListLinkSyncResult,
  ListLinkTarget,
  MediaType,
} from "./types";

export const listsApi = {
  getLists(signal?: AbortSignal) {
    return request<{ lists: CatalogList[] }>("/lists", { signal });
  },
  createList(name: string) {
    return request<{ list: CatalogList }>("/lists", {
      method: "POST",
      body: JSON.stringify({ name, kind: "custom" })
    });
  },
  deleteList(listId: string) {
    return request<void>(`/lists/${encodeURIComponent(listId)}`, { method: "DELETE" });
  },
  renameList(listId: string, name: string) {
    return request<{ list: CatalogList }>(`/lists/${encodeURIComponent(listId)}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    });
  },
  addToList(listId: string, payload: { type: MediaType; imdbId: string; title: string }) {
    const encodedListId = encodeURIComponent(listId);

    return request(`/lists/${encodedListId}/items`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getListItems(listId: string, signal?: AbortSignal) {
    return request<{ items: ListItemWithMeta[] }>(
      `/lists/${encodeURIComponent(listId)}/items`,
      { signal }
    );
  },
  removeFromList(listId: string, item: { type: MediaType; imdbId: string }) {
    const encodedListId = encodeURIComponent(listId);
    const encodedImdbId = encodeURIComponent(item.imdbId);

    return request(`/lists/${encodedListId}/items/${item.type}/${encodedImdbId}`, {
      method: "DELETE"
    });
  },
  getListLinkTargets(provider: ListLinkProvider, signal?: AbortSignal) {
    return request<{ lists: ListLinkTarget[] }>(`/lists/link-targets/${provider}`, { signal });
  },
  /** `remoteId` "new" makes a list on the service named after this one. */
  linkList(listId: string, provider: ListLinkProvider, remoteId: string) {
    return request<{ link: ListLink | null; sync: ListLinkSyncResult }>(
      `/lists/${encodeURIComponent(listId)}/link`,
      // The first merge reads both lists in full and may push many batches.
      { method: "PUT", body: JSON.stringify({ provider, remoteId }), timeoutMs: 120000 }
    );
  },
  unlinkList(listId: string) {
    return request<void>(`/lists/${encodeURIComponent(listId)}/link`, { method: "DELETE" });
  },
  syncListLink(listId: string) {
    return request<{ link: ListLink | null; sync: ListLinkSyncResult }>(
      `/lists/${encodeURIComponent(listId)}/link/sync`,
      { method: "POST", timeoutMs: 120000 }
    );
  },
  getItemLists(imdbId: string) {
    return request<{ lists: ItemListMembership[] }>(`/items/${encodeURIComponent(imdbId)}/lists`);
  },
  dashboard() {
    return Promise.all([
      request<{ metas: CatalogMeta[] }>("/watchlist?type=movie&limit=10"),
      request<{ metas: CatalogMeta[] }>("/continue?limit=10"),
      request<{ metas: CatalogMeta[] }>("/recent?type=movie&limit=10")
    ]);
  },
};
