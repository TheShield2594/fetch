import { TITLE_PARAM } from "../components/media-detail/titlePanel";
import { useCallback, useMemo } from "react";
import { useLocation, useSearchParams } from "react-router";

export type FilterType = "all" | "movie" | "series";
export type SortOption = "relevance" | "rating" | "year_desc" | "year_asc" | "title";

export type SearchFilters = {
  query: string;
  filter: FilterType;
  genre: string;
  yearMin: string;
  yearMax: string;
  ratingMin: string;
  sort: SortOption;
};

const DEFAULTS: SearchFilters = {
  query: "",
  filter: "all",
  genre: "",
  yearMin: "",
  yearMax: "",
  ratingMin: "",
  sort: "relevance",
};

const VALID_FILTERS: FilterType[] = ["all", "movie", "series"];
const VALID_SORTS: SortOption[] = ["relevance", "rating", "year_desc", "year_asc", "title"];

export const SORT_LABELS: Record<SortOption, string> = {
  relevance: "Relevance",
  rating: "Highest rated",
  year_desc: "Newest first",
  year_asc: "Oldest first",
  title: "Title A–Z",
};

/** What "relevance" means with nothing typed: there is no query to be relevant to. */
export const BROWSE_RELEVANCE_LABEL = "Most popular";

export const GENRE_OPTIONS = [
  "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
  "Drama", "Family", "Fantasy", "History", "Horror", "Music",
  "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western",
];

const PARAM_MAP: Record<keyof SearchFilters, string> = {
  query: "q",
  filter: "filter",
  genre: "genre",
  yearMin: "yearMin",
  yearMax: "yearMax",
  ratingMin: "ratingMin",
  sort: "sort",
};

function parseParams(params: URLSearchParams): SearchFilters {
  const f = params.get("filter") as FilterType;
  const s = params.get("sort") as SortOption;
  return {
    query: params.get("q") ?? DEFAULTS.query,
    filter: VALID_FILTERS.includes(f) ? f : DEFAULTS.filter,
    genre: params.get("genre") ?? DEFAULTS.genre,
    yearMin: numericParam(params.get("yearMin"), DEFAULTS.yearMin),
    yearMax: numericParam(params.get("yearMax"), DEFAULTS.yearMax),
    ratingMin: numericParam(params.get("ratingMin"), DEFAULTS.ratingMin),
    sort: VALID_SORTS.includes(s) ? s : DEFAULTS.sort,
  };
}

/** Return the string if it parses as a finite number, otherwise fallback. */
function numericParam(raw: string | null, fallback: string): string {
  if (raw == null) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) ? raw : fallback;
}

export function useSearchFilters() {
  const [searchParams, setSearchParams] = useSearchParams();
  // Handed back on every replace below: an open title keeps its history depth
  // in the entry's state, and a replace without it would drop that.
  const locationState: unknown = useLocation().state;

  const filters: SearchFilters = useMemo(() => parseParams(searchParams), [searchParams]);

  const setFilters = useCallback(
    (updates: Partial<SearchFilters>) => {
      setSearchParams((prev) => {
        // Derive base state from prev (not the captured filters closure)
        // so back-to-back calls within the same render cycle don't lose updates.
        const base = parseParams(prev);
        const merged = { ...base, ...updates };

        // Rebuilt from empty so a parameter that was never a filter is dropped
        // — with one exception. An open title lives in `?title=`, and a
        // debounced query landing after a result was clicked used to rebuild
        // the string without it, closing the panel the click had just opened.
        const next = new URLSearchParams();
        const openTitle = prev.get(TITLE_PARAM);
        if (openTitle) next.set(TITLE_PARAM, openTitle);

        for (const [key, paramName] of Object.entries(PARAM_MAP)) {
          const val = merged[key as keyof SearchFilters];
          const def = DEFAULTS[key as keyof SearchFilters];
          if (val && val !== def) {
            next.set(paramName, val);
          } else {
            next.delete(paramName);
          }
        }

        return next;
      }, { replace: true, state: locationState });
    },
    [setSearchParams, locationState]
  );

  const clearFilters = useCallback(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      // Keep the query, the media type, the sort and an open title: clearing
      // filters narrows nothing further, it does not start over.
      for (const key of [...next.keys()]) {
        if (key !== "q" && key !== "filter" && key !== "sort" && key !== TITLE_PARAM) next.delete(key);
      }
      return next;
    }, { replace: true, state: locationState });
  }, [setSearchParams, locationState]);

  // The sort is not a filter: it orders what is there and narrows nothing, so
  // it neither lights the Filters button nor counts towards its badge.
  const hasActiveFilters = useMemo(() => {
    return (
      filters.genre !== DEFAULTS.genre ||
      filters.yearMin !== DEFAULTS.yearMin ||
      filters.yearMax !== DEFAULTS.yearMax ||
      filters.ratingMin !== DEFAULTS.ratingMin
    );
  }, [filters]);

  const activeFilterCount = useMemo(() => {
    let count = 0;
    if (filters.genre) count++;
    if (filters.yearMin || filters.yearMax) count++;
    if (filters.ratingMin) count++;
    return count;
  }, [filters]);

  return { filters, setFilters, clearFilters, hasActiveFilters, activeFilterCount };
}
