/**
 * Finding something to watch: search, what is trending, and recommendations
 * built from what the profile has watched.
 */

import { request } from "./client";
import type {
  MediaType,
  SearchResult,
  TrendingMeta,
} from "./types";

export const discoverApi = {
  search(type: MediaType, query: string, signal?: AbortSignal) {
    return request<SearchResult[]>(`/search?type=${type}&query=${encodeURIComponent(query)}`, { signal, timeoutMs: 15000 });
  },
  /**
   * One page of Search with its filters applied by TMDB. One media type per
   * call; `page` is the `nextPage` the previous call returned.
   */
  browseSearch(
    type: "movie" | "series",
    params: {
      query?: string | undefined;
      genre?: string | undefined;
      yearMin?: string | undefined;
      yearMax?: string | undefined;
      ratingMin?: string | undefined;
      sort?: string | undefined;
      page?: number | undefined;
    },
    signal?: AbortSignal
  ) {
    const qs = new URLSearchParams({ type });
    if (params.query) qs.set("q", params.query);
    if (params.genre) qs.set("genre", params.genre);
    if (params.yearMin) qs.set("yearMin", params.yearMin);
    if (params.yearMax) qs.set("yearMax", params.yearMax);
    if (params.ratingMin) qs.set("ratingMin", params.ratingMin);
    if (params.sort && params.sort !== "relevance") qs.set("sort", params.sort);
    if (params.page && params.page > 1) qs.set("page", String(params.page));
    return request<{ results: SearchResult[]; page: number; nextPage: number | null }>(
      `/search/browse?${qs}`,
      { signal, timeoutMs: 20000 }
    );
  },
  getTrending(type: MediaType, window: "day" | "week" = "week") {
    return request<{ metas: TrendingMeta[] }>(`/trending?type=${type}&window=${window}`);
  },
  /** TMDB's "if you liked this" for one title — Discover's "Because you watched". */
  getRecommendationsFor(type: MediaType, imdbId: string) {
    return request<{ metas: TrendingMeta[] }>(
      `/recommendations?type=${type}&imdbId=${encodeURIComponent(imdbId)}`
    );
  },
};
