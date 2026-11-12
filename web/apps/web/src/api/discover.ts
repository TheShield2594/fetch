/**
 * Finding something to watch: search, what is trending, what a
 * provider is carrying, and the catalogues built from those.
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
  // Trending & Popular
  getTrending(type: MediaType, window: "day" | "week" = "week") {
    return request<{ metas: TrendingMeta[] }>(`/trending?type=${type}&window=${window}`);
  },
  getPopular(type: MediaType) {
    return request<{ metas: TrendingMeta[] }>(`/popular?type=${type}`);
  },
  // Streaming
  getStreamingCatalog(type: MediaType, provider: string) {
    return request<{ metas: TrendingMeta[]; provider: string }>(`/streaming?type=${type}&provider=${encodeURIComponent(provider)}`);
  },
  getStreamingProviders() {
    return request<{ providers: Array<{ key: string; id: number; name: string }> }>("/streaming/providers");
  },
  // Anime
  getAnimeCatalog(type: MediaType) {
    return request<{ metas: TrendingMeta[] }>(`/anime?type=${type}`);
  },
  // Recommendations
  getPersonalRecommendations(type: MediaType, limit = 20) {
    return request<{ metas: TrendingMeta[] }>(`/recommendations/personal?type=${type}&limit=${limit}`);
  },
};
