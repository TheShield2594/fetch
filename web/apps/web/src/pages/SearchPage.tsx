import { type FormEvent, useEffect, useId, useMemo, useRef, useState } from "react";
import { Link } from "react-router";
import { Check, ChevronDown, ChevronUp, Clock, Plus, Search, SlidersHorizontal, X } from "lucide-react";
import { api, type CatalogList, type SearchResult, type TrendingMeta } from "../api";
import { useTitlePanel } from "../components/media-detail/titlePanel";
import { useToast } from "../hooks/useToast";
import { mergeByRelevance } from "../utils/mergeSearchResults";
import { clearRecentSearches, loadRecentSearches, rememberSearch } from "../utils/recentSearches";
import {
  useSearchFilters,
  type FilterType,
  type SortOption,
  BROWSE_RELEVANCE_LABEL,
  GENRE_OPTIONS,
  SORT_LABELS,
} from "../hooks/useSearchFilters";
import { SECTION_TITLE, MICRO_LABEL, KICKER } from "../components/typography";
import { EmptyState, EMPTY_STATE_ACTION } from "../components/EmptyState";
import { PageFrame, PageHeader } from "../components/PageFrame";
import { SegmentedControl, type SegmentedOption } from "../components/SegmentedControl";
import { SelectField } from "../components/SelectField";
import { PosterGrid } from "../components/PosterGrid";
import { kindWord, metaRun, PosterCard, PosterCardSkeleton } from "../components/PosterCard";

/*
 * Search.
 *
 * Every filter is a question for TMDB, not a sieve over what one page
 * returned: the page asks `/search/browse` once per media type, and the API
 * either scans TMDB search pages for matches (when something is typed) or
 * runs a `/discover` query (when only filters are set). "Horror, 1980–1989"
 * therefore finds eighties horror rather than narrowing twenty results to two.
 *
 * With nothing typed and no filter set, the page is a place to start: recent
 * searches, genres and decades that run a real browse, and what is trending.
 */

type MediaKey = "movie" | "series";

const typesFor = (filter: FilterType): MediaKey[] => (filter === "all" ? ["movie", "series"] : [filter]);

const resultKey = (r: SearchResult) => `${r.type}:${r.imdbId}`;

const dedupe = (results: SearchResult[]): SearchResult[] => {
  const seen = new Set<string>();
  return results.filter((r) => {
    const key = resultKey(r);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
};

/** Films and shows one for one — the order each arrived in is already TMDB's. */
const interleave = (a: SearchResult[], b: SearchResult[]): SearchResult[] => {
  const out: SearchResult[] = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i]) out.push(a[i]!);
    if (b[i]) out.push(b[i]!);
  }
  return out;
};

/**
 * The order the loaded results are shown in. Relevance is the order they
 * arrived in; anything else is applied over everything loaded so far, so a
 * page appended by "Show more" lands in place rather than in a second sorted
 * run under the first.
 */
function sortResults(results: SearchResult[], sort: SortOption): SearchResult[] {
  switch (sort) {
    case "rating":
      return [...results].sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
    case "year_desc":
      return [...results].sort((a, b) => (b.year ?? 0) - (a.year ?? 0));
    case "year_asc":
      return [...results].sort((a, b) => (a.year ?? 9999) - (b.year ?? 9999));
    case "title":
      return [...results].sort((a, b) => a.name.localeCompare(b.name));
    case "relevance":
    default:
      return results;
  }
}

const trendingToResult = (meta: TrendingMeta): SearchResult => ({
  imdbId: meta.id,
  type: meta.type,
  name: meta.name,
  year: meta.year ?? null,
  poster: meta.poster ?? null,
  description: meta.description ?? null,
  genres: meta.genres ?? [],
  rating: meta.rating ?? null,
  inWatchlist: false,
  inCollection: false,
  // Trending rows don't carry membership; an add made here still marks the card.
  lists: [],
});

const DECADES = [2020, 2010, 2000, 1990, 1980, 1970, 1960, 1950];

/* ─── Main Component ─── */

export function SearchPage() {
  const { filters, setFilters, clearFilters, hasActiveFilters, activeFilterCount } = useSearchFilters();
  const query = filters.query.trim();
  const active = query !== "" || hasActiveFilters;

  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [nextPages, setNextPages] = useState<Partial<Record<MediaKey, number | null>>>({});
  const [needsTmdb, setNeedsTmdb] = useState(false);
  // Held as state rather than left to the toast: the toast fades, and the page
  // it fades off said "No results found" — a search that failed reported for
  // the rest of the session as a search that came back empty.
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [lists, setLists] = useState<CatalogList[]>([]);
  const [trending, setTrending] = useState<SearchResult[] | null>(null);
  const [recent, setRecent] = useState<string[]>(() => loadRecentSearches());
  // Keys are deleted when an add settles, not set to `false`: a `false` entry
  // reads the same as an absent one but keeps the `listId:imdbId` string alive
  // for the life of the tab, so the map only ever grew.
  const [pendingAdds, setPendingAdds] = useState<Record<string, true>>({});
  const { showToast } = useToast();
  const [openDropdown, setOpenDropdown] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(hasActiveFilters);
  const advancedFiltersId = useId();
  const sortId = useId();
  const { openTitle } = useTitlePanel();
  const dropdownRef = useRef<HTMLDivElement>(null);
  const requestIdRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  // What a first page is a page *of*. With something typed the sort is applied
  // here over what has loaded; with nothing typed it is part of the TMDB query.
  const runKey = JSON.stringify({
    filter: filters.filter,
    query,
    genre: filters.genre,
    yearMin: filters.yearMin,
    yearMax: filters.yearMax,
    ratingMin: filters.ratingMin,
    sort: query ? null : filters.sort,
  });
  // What the loaded results answer — compared against `runKey` to know when
  // "Show more" would append a page of the wrong question.
  const loadedKeyRef = useRef<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const { lists: loaded } = await api.getLists();
        setLists(loaded);
      } catch (err) {
        console.error(err);
        showToast(err instanceof Error ? err.message : "Failed to load lists", "error");
      }
    })();
  }, [showToast]);

  useEffect(() => () => abortRef.current?.abort(), []);

  // Close the add menu on an outside click.
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) setOpenDropdown(null);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  // Trending, for the browse state. Asked once, the first time the page has
  // nothing else to show, and best-effort: a failure leaves the genres and
  // decades, which are useful on their own.
  const trendingAsked = useRef(false);
  useEffect(() => {
    if (active || trendingAsked.current) return;
    trendingAsked.current = true;
    void Promise.all([
      api.getTrending("movie").catch(() => ({ metas: [] as TrendingMeta[] })),
      api.getTrending("series").catch(() => ({ metas: [] as TrendingMeta[] })),
    ]).then(([movies, series]) => {
      setTrending(interleave(movies.metas.map(trendingToResult), series.metas.map(trendingToResult)).slice(0, 21));
    });
  }, [active]);

  const listMap = useMemo(() => {
    const map = new Map<string, CatalogList>();
    for (const l of lists) map.set(l.id, l);
    return map;
  }, [lists]);

  /**
   * Fetches a page per media type. `more` appends the next page of what is
   * already loaded; otherwise this is a fresh question and replaces it.
   */
  const run = async (more: boolean) => {
      const key = runKey;
      const types = typesFor(filters.filter);
      const pages = types
        .map((type) => [type, more ? nextPages[type] : 1] as const)
        .filter((entry): entry is readonly [MediaKey, number] => typeof entry[1] === "number");
      if (pages.length === 0) return;

      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      const requestId = ++requestIdRef.current;
      if (more) setIsLoadingMore(true);
      else setIsSearching(true);
      setNeedsTmdb(false);
      setSearchError(null);

      try {
        const answered = await Promise.all(
          pages.map(([type, page]) =>
            api
              .browseSearch(
                type,
                {
                  query,
                  genre: filters.genre,
                  yearMin: filters.yearMin,
                  yearMax: filters.yearMax,
                  ratingMin: filters.ratingMin,
                  sort: query ? undefined : filters.sort,
                  page,
                },
                controller.signal
              )
              .then((res) => [type, res] as const)
          )
        );
        if (requestIdRef.current !== requestId) return;

        const byType = Object.fromEntries(answered.map(([type, res]) => [type, res.results])) as Partial<
          Record<MediaKey, SearchResult[]>
        >;
        const movies = byType.movie ?? [];
        const series = byType.series ?? [];
        const merged = query ? mergeByRelevance(movies, series, query) : interleave(movies, series);

        setResults((prev) => dedupe(more && prev ? [...prev, ...merged] : merged));
        setNextPages((prev) => {
          const next = more ? { ...prev } : {};
          for (const [type, res] of answered) next[type] = res.nextPage;
          return next;
        });
        loadedKeyRef.current = key;
        if (!more && query && merged.length > 0) setRecent(rememberSearch(query));
      } catch (err) {
        if (requestIdRef.current !== requestId) return;
        if (err instanceof DOMException && err.name === "AbortError") return;
        const message = err instanceof Error ? err.message : "Search failed";
        if (more) {
          showToast(message, "error");
          return;
        }
        setResults([]);
        setNextPages({});
        setNeedsTmdb(/tmdb/i.test(message));
        setSearchError(message);
        showToast(message, "error");
      } finally {
        if (requestIdRef.current === requestId) {
          setIsSearching(false);
          setIsLoadingMore(false);
        }
      }
  };

  // A fresh question whenever what is being asked changes, debounced so typing
  // asks once. `run` is deliberately not a dependency: it changes with
  // `nextPages`, and a page landing must not ask the same question again.
  const runRef = useRef(run);
  useEffect(() => {
    runRef.current = run;
  });
  useEffect(() => {
    if (!active) {
      ++requestIdRef.current;
      abortRef.current?.abort();
      loadedKeyRef.current = null;
      setResults(null);
      setNextPages({});
      setSearchError(null);
      setNeedsTmdb(false);
      setIsSearching(false);
      setIsLoadingMore(false);
      return;
    }
    if (loadedKeyRef.current === runKey) return;
    const timer = setTimeout(() => void runRef.current(false), 300);
    return () => clearTimeout(timer);
  }, [runKey, active]);

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (active) void run(false);
  };

  const markAdded = (imdbId: string, listId: string) => {
    const add = (r: SearchResult) => (r.imdbId === imdbId && !r.lists.includes(listId) ? { ...r, lists: [...r.lists, listId] } : r);
    setResults((prev) => prev?.map(add) ?? null);
    setTrending((prev) => prev?.map(add) ?? null);
  };

  const handleAdd = async (listId: string, result: SearchResult) => {
    const key = `${listId}:${result.imdbId}`;
    if (pendingAdds[key]) return;

    setPendingAdds((current) => ({ ...current, [key]: true }));

    try {
      await api.addToList(listId, { type: result.type, imdbId: result.imdbId, title: result.name });
      const listName = listMap.get(listId)?.name ?? "list";
      showToast(`Added "${result.name}" to ${listName}`, "success");
      setLists((prev) => prev.map((l) => (l.id === listId ? { ...l, itemCount: l.itemCount + 1 } : l)));
      markAdded(result.imdbId, listId);
      setOpenDropdown(null);
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Unable to add item", "error");
    } finally {
      setPendingAdds((current) => {
        const next = { ...current };
        delete next[key];
        return next;
      });
    }
  };

  const handleCreateAndAdd = async (rawName: string, result: SearchResult) => {
    const name = rawName.trim();
    if (!name) return;
    // createList and addToList are two separate calls: keep a successfully created
    // list in state even if the add then fails, report the two failures distinctly,
    // and only claim full success once both complete. The created list stays in the
    // menu so the user can retry the add without spawning a duplicate list.
    let list: CatalogList;
    try {
      ({ list } = await api.createList(name));
      setLists((prev) => [...prev, list]);
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Unable to create list", "error");
      return;
    }
    try {
      await api.addToList(list.id, { type: result.type, imdbId: result.imdbId, title: result.name });
    } catch (err) {
      const reason = err instanceof Error ? err.message : "unknown error";
      showToast(`Created "${list.name}", but couldn't add "${result.name}" (${reason})`, "error");
      setOpenDropdown(null);
      return;
    }
    showToast(`Created "${list.name}" and added "${result.name}"`, "success");
    setLists((prev) => prev.map((l) => (l.id === list.id ? { ...l, itemCount: l.itemCount + 1 } : l)));
    markAdded(result.imdbId, list.id);
    setOpenDropdown(null);
  };

  const filterOptions: SegmentedOption<FilterType>[] = [
    { value: "all", label: "All" },
    { value: "movie", label: "Films" },
    { value: "series", label: "Shows" },
  ];

  const shown = useMemo(() => (results ? sortResults(results, filters.sort) : null), [results, filters.sort]);
  const hasMore = Object.values(nextPages).some((page) => typeof page === "number");
  const hasSearched = active && results !== null;
  const noResults = hasSearched && (shown?.length ?? 0) === 0;
  const relevanceLabel = query ? SORT_LABELS.relevance : BROWSE_RELEVANCE_LABEL;

  // Empty until something has actually happened, so the region doesn't announce
  // on mount. Written as whole sentences rather than echoing the labels on
  // screen: the visible copy sits next to an icon and a heading that supply the
  // context, and an announcement arrives with none of that around it.
  const searchStatus = (() => {
    if (isSearching) return "Searching…";
    if (isLoadingMore) return "Loading more results…";
    if (!hasSearched) return "";
    if (needsTmdb) return "A TMDB API key is needed before titles can be searched.";
    if (searchError) return `Search could not run. ${searchError}`;
    const count = shown?.length ?? 0;
    if (count === 0) return "Nothing matched that search.";
    return `Showing ${count} ${count === 1 ? "result" : "results"}${hasMore ? ", with more to load" : ""}.`;
  })();

  const renderCard = (result: SearchResult, index: number) => (
    <ResultCard
      key={resultKey(result)}
      result={result}
      eager={index < 7}
      lists={lists}
      listMap={listMap}
      pendingAdds={pendingAdds}
      openDropdown={openDropdown}
      dropdownRef={dropdownRef}
      onToggleDropdown={(id) => setOpenDropdown(openDropdown === id ? null : id)}
      onCloseDropdown={() => setOpenDropdown(null)}
      onAdd={handleAdd}
      onCreateAndAdd={handleCreateAndAdd}
      onSelect={openTitle}
    />
  );

  return (
    <PageFrame className="relative">
      <PageHeader title="Search" />
      <div className="space-y-6">
      {/* Search bar.

          Two radii in this panel, and only two: surfaces are rounded-2xl (this
          form, the advanced-filters grid inside it), controls are pills
          (the query field, the type toggle, the year inputs, the selects). */}
      <form
        onSubmit={submitSearch}
        className="glass-surface sticky top-[76px] z-40 rounded-2xl p-4 backdrop-blur-xl shadow-e1"
        style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)", background: "color-mix(in srgb, var(--bg-1) 90%, transparent)" }}
      >
        <div className="relative">
          <Search className="pointer-events-none absolute left-5 top-1/2 h-5 w-5 -translate-y-1/2" style={{ color: "var(--text-mute)" }} />
          <input
            value={filters.query}
            onChange={(e) => setFilters({ query: e.target.value })}
            placeholder="Search films & shows…"
            aria-label="Search films and shows"
            className="w-full rounded-full py-3.5 pl-14 pr-12 text-base placeholder:text-[var(--text-mute)] field-control transition-[border-color,box-shadow] duration-base"
            style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-0)", color: "var(--text)" }}
            autoFocus={typeof window !== "undefined" && !window.matchMedia("(pointer: coarse)").matches}
          />
          {filters.query && (
            <button
              type="button"
              onClick={() => setFilters({ query: "" })}
              aria-label="Clear search"
              className="absolute right-4 top-1/2 -translate-y-1/2 flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold hover:bg-[var(--border-strong)] transition-colors"
              style={{ backgroundColor: "var(--border)", color: "var(--text)" }}
            >
              <X aria-hidden="true" className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {/* Type toggle + filters */}
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <SegmentedControl
            label="Search films, shows or both"
            options={filterOptions}
            value={filters.filter}
            onChange={(next) => setFilters({ filter: next })}
            className="w-56"
          />

          {/* The word "Filters" is hidden below `sm`, where the button is an
              icon only — so the name comes from `aria-label`, which also carries
              the badge's count. */}
          <button
            type="button"
            onClick={() => setFiltersOpen((v) => !v)}
            aria-expanded={filtersOpen}
            aria-controls={advancedFiltersId}
            aria-label={activeFilterCount > 0 ? `Filters (${activeFilterCount} active)` : "Filters"}
            className={`flex items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-sm font-medium transition-[color,background-color,border-color] duration-base ${
              hasActiveFilters
                ? "border-accent-500/50 bg-accent-500/10 text-accent-text"
                : "border-[var(--border-strong)] bg-[var(--surface)] text-[var(--text-mute)] hover:text-[var(--text)]"
            }`}
          >
            <SlidersHorizontal aria-hidden="true" className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">Filters</span>
            {activeFilterCount > 0 && (
              <span aria-hidden="true" className="flex h-5 w-5 items-center justify-center rounded-full bg-accent-500 text-2xs font-bold text-accent-on">
                {activeFilterCount}
              </span>
            )}
            {filtersOpen ? <ChevronUp aria-hidden="true" className="h-3.5 w-3.5" /> : <ChevronDown aria-hidden="true" className="h-3.5 w-3.5" />}
          </button>

          {hasActiveFilters && (
            <button
              type="button"
              onClick={clearFilters}
              className="flex items-center gap-1 rounded-full px-3 py-1.5 text-sm text-[var(--text-mute)] hover:text-[var(--text)] transition-colors"
            >
              <X className="h-3 w-3" />
              Clear filters
            </button>
          )}

          {isSearching && (
            <span className="ml-auto flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
              <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-accent-500 border-t-transparent" />
              Searching…
            </span>
          )}
        </div>

        {/* Filters. Each one is part of the question asked of TMDB. */}
        {filtersOpen && (
          <div id={advancedFiltersId} className="mt-3 grid grid-cols-2 gap-3 rounded-2xl p-3 sm:grid-cols-3" style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)", background: "var(--surface)" }}>
            <FilterSelect
              label="Genre"
              value={filters.genre}
              onChange={(v) => setFilters({ genre: v })}
              options={[{ value: "", label: "Any genre" }, ...GENRE_OPTIONS.map((g) => ({ value: g, label: g }))]}
            />

            <div className="flex flex-col gap-1">
              <label className={MICRO_LABEL} style={{ color: "var(--text-mute)" }}>Year</label>
              <div className="flex gap-1.5">
                <input
                  type="number"
                  placeholder="From"
                  aria-label="Earliest year"
                  min="1900"
                  max="2100"
                  value={filters.yearMin}
                  onChange={(e) => setFilters({ yearMin: e.target.value })}
                  className="w-full min-w-0 rounded-full px-1.5 py-2 text-center text-sm placeholder:text-[var(--text-mute)] field-control"
                  style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-0)", color: "var(--text)" }}
                />
                <input
                  type="number"
                  placeholder="To"
                  aria-label="Latest year"
                  min="1900"
                  max="2100"
                  value={filters.yearMax}
                  onChange={(e) => setFilters({ yearMax: e.target.value })}
                  className="w-full min-w-0 rounded-full px-1.5 py-2 text-center text-sm placeholder:text-[var(--text-mute)] field-control"
                  style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-0)", color: "var(--text)" }}
                />
              </div>
            </div>

            <FilterSelect
              label="TMDB rating"
              value={filters.ratingMin}
              onChange={(v) => setFilters({ ratingMin: v })}
              options={[
                { value: "", label: "Any rating" },
                { value: "8", label: "8 and up" },
                { value: "7", label: "7 and up" },
                { value: "6", label: "6 and up" },
                { value: "5", label: "5 and up" },
              ]}
            />
          </div>
        )}
      </form>

      {/* One region for every outcome, kept mounted so its text changes rather
          than its subtree — a region that unmounts doesn't announce (SC 4.1.3). */}
      <p className="sr-only" role="status" aria-live="polite" aria-label="Search status">
        {searchStatus}
      </p>

      {/* First question of the session: there is no previous answer to hold on
          to, so the region gets the grid's own skeleton rather than nothing. */}
      {isSearching && results === null && <ResultsSkeleton />}

      {!active && (
        <SearchBrowse
          recent={recent}
          onPickRecent={(q) => setFilters({ query: q })}
          onClearRecent={() => {
            clearRecentSearches();
            setRecent([]);
          }}
          onPickGenre={(genre) => {
            setFilters({ genre });
            setFiltersOpen(true);
          }}
          onPickDecade={(start) => {
            setFilters({ yearMin: String(start), yearMax: String(start + 9) });
            setFiltersOpen(true);
          }}
          trending={trending}
          renderCard={renderCard}
        />
      )}

      {/* Everything a completed search produced — the grid, or the reason there
          isn't one. A re-search leaves it in place but dimmed and marked busy:
          until the new answer lands it describes the previous question. */}
      {active && (
        <div
          data-testid="search-results"
          aria-busy={isSearching && hasSearched}
          className={`space-y-5 transition-opacity duration-slow ${isSearching && hasSearched ? "opacity-40" : ""}`}
        >
          {noResults && (
            <EmptyState
              title={needsTmdb ? "Search needs a TMDB API key" : searchError ? "Search failed" : "No results found"}
              action={
                needsTmdb ? (
                  <Link to="/settings?section=tmdb" className={EMPTY_STATE_ACTION}>
                    Set up TMDB in Settings &rarr;
                  </Link>
                ) : hasActiveFilters && !searchError ? (
                  // Not offered when the search itself failed: there is nothing a
                  // looser filter would find in a request that never ran.
                  <button type="button" onClick={clearFilters} className={EMPTY_STATE_ACTION}>
                    Clear all filters
                  </button>
                ) : undefined
              }
            >
              {needsTmdb
                ? "Film and show search is powered by TMDB — set up a free API key to start searching."
                : searchError
                  ? searchError
                  : hasActiveFilters
                    ? "Nothing matches every filter. Try loosening one."
                    : "Try a different search term."}
            </EmptyState>
          )}

          {shown !== null && shown.length > 0 && (
            <>
              {/* The one sort control. It used to be in the filter panel and
                  again as a row of pills here, two controls for one setting. */}
              <div className="flex items-center justify-between gap-3">
                <p className="meta-row" style={{ color: "var(--text-mute)" }}>
                  {shown.length} {shown.length === 1 ? "result" : "results"}
                  {hasMore ? " so far" : ""}
                </p>
                <div className="flex items-center gap-2">
                  <label htmlFor={sortId} className="text-xs" style={{ color: "var(--text-mute)" }}>
                    Sort
                  </label>
                  <SelectField
                    id={sortId}
                    value={filters.sort}
                    onChange={(e) => setFilters({ sort: e.target.value as SortOption })}
                    className="rounded-full py-1.5 pl-3 pr-8 text-sm"
                    style={{ color: "var(--text-dim)" }}
                  >
                    {(Object.entries(SORT_LABELS) as [SortOption, string][]).map(([value, label]) => (
                      <option key={value} value={value}>
                        {value === "relevance" ? relevanceLabel : label}
                      </option>
                    ))}
                  </SelectField>
                </div>
              </div>
              <PosterGrid density="results">{shown.map(renderCard)}</PosterGrid>
              {hasMore && (
                <div className="flex justify-center pt-2">
                  <button
                    type="button"
                    onClick={() => void run(true)}
                    disabled={isLoadingMore || isSearching || loadedKeyRef.current !== runKey}
                    className="btn-secondary btn-sm"
                  >
                    {isLoadingMore ? "Loading…" : "Show more"}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
      </div>
    </PageFrame>
  );
}

/* ─── Browse state ─── */

/**
 * The page before anything is typed: somewhere to start rather than a large
 * icon and an invitation. Genres and decades set a real filter, which runs a
 * TMDB browse, so they lead to the same results page a typed search does.
 */
function SearchBrowse({
  recent,
  onPickRecent,
  onClearRecent,
  onPickGenre,
  onPickDecade,
  trending,
  renderCard,
}: {
  recent: string[];
  onPickRecent: (query: string) => void;
  onClearRecent: () => void;
  onPickGenre: (genre: string) => void;
  onPickDecade: (start: number) => void;
  trending: SearchResult[] | null;
  renderCard: (result: SearchResult, index: number) => React.ReactNode;
}) {
  const chip =
    "rounded-full px-3 py-1.5 text-sm transition-colors duration-base hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus";
  const chipStyle = { border: "1px solid var(--border)", background: "var(--surface)", color: "var(--text-dim)" } as const;

  return (
    <div className="space-y-7">
      {recent.length > 0 && (
        <section aria-labelledby="search-recent">
          <div className="mb-2.5 flex items-center justify-between">
            <h2 id="search-recent" className={KICKER} style={{ color: "var(--text-mute)" }}>
              Recent searches
            </h2>
            <button type="button" onClick={onClearRecent} className="text-xs hover:underline" style={{ color: "var(--text-mute)" }}>
              Clear
            </button>
          </div>
          <ul className="flex flex-wrap gap-2">
            {recent.map((q) => (
              <li key={q}>
                <button type="button" onClick={() => onPickRecent(q)} className={`${chip} inline-flex items-center gap-1.5`} style={chipStyle}>
                  <Clock aria-hidden="true" className="h-3.5 w-3.5" />
                  {q}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="search-genres">
        <h2 id="search-genres" className={`mb-2.5 ${KICKER}`} style={{ color: "var(--text-mute)" }}>
          Browse by genre
        </h2>
        <ul className="flex flex-wrap gap-2">
          {GENRE_OPTIONS.map((genre) => (
            <li key={genre}>
              <button type="button" onClick={() => onPickGenre(genre)} className={chip} style={chipStyle}>
                {genre}
              </button>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="search-decades">
        <h2 id="search-decades" className={`mb-2.5 ${KICKER}`} style={{ color: "var(--text-mute)" }}>
          Browse by decade
        </h2>
        <ul className="flex flex-wrap gap-2">
          {DECADES.map((start) => (
            <li key={start}>
              <button type="button" onClick={() => onPickDecade(start)} className={chip} style={chipStyle}>
                {start}s
              </button>
            </li>
          ))}
        </ul>
      </section>

      {trending !== null && trending.length > 0 && (
        <section aria-labelledby="search-trending">
          <h2 id="search-trending" className={`mb-3 ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>
            Trending this week
          </h2>
          <PosterGrid density="results">{trending.map(renderCard)}</PosterGrid>
        </section>
      )}
    </div>
  );
}

/* ─── Results Skeleton ─── */

// The same grid the results land in, so they replace it in place. Hidden from
// assistive tech: the live region already says "Searching…".
function ResultsSkeleton() {
  return (
    <div aria-hidden="true" data-testid="search-skeleton">
      <div className="skeleton h-4 w-24 rounded" />
      <PosterGrid density="results" className="mt-5">
        {Array.from({ length: 14 }).map((_, i) => (
          <PosterCardSkeleton key={i} />
        ))}
      </PosterGrid>
    </div>
  );
}

/* ─── Filter Select ─── */

function FilterSelect({
  label,
  value,
  onChange,
  options,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className={MICRO_LABEL} style={{ color: "var(--text-mute)" }}>{label}</label>
      <SelectField
        id={id}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-full px-3 py-2 text-sm"
        style={{ color: "var(--text-dim)" }}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </SelectField>
    </div>
  );
}

/* ─── Result Card ─────────────────────────────────────────── */

function ResultCard({
  result,
  eager = false,
  lists,
  listMap,
  pendingAdds,
  openDropdown,
  dropdownRef,
  onToggleDropdown,
  onCloseDropdown,
  onAdd,
  onCreateAndAdd,
  onSelect,
}: {
  result: SearchResult;
  eager?: boolean;
  lists: CatalogList[];
  listMap: Map<string, CatalogList>;
  pendingAdds: Record<string, true>;
  openDropdown: string | null;
  dropdownRef: React.RefObject<HTMLDivElement | null>;
  onToggleDropdown: (id: string) => void;
  onCloseDropdown: () => void;
  onAdd: (listId: string, result: SearchResult) => Promise<void>;
  onCreateAndAdd: (name: string, result: SearchResult) => Promise<void>;
  onSelect: (result: SearchResult) => void;
}) {
  const isOpen = openDropdown === result.imdbId;
  const [creating, setCreating] = useState(false);
  const [newListName, setNewListName] = useState("");
  const [savingNewList, setSavingNewList] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const createButtonRef = useRef<HTMLButtonElement>(null);
  const restoreCreateFocus = useRef(false);
  const panelId = useId();

  // Reset the inline create form whenever this card's menu closes.
  useEffect(() => {
    if (!isOpen) { setCreating(false); setNewListName(""); }
  }, [isOpen]);

  // Move focus into the popup on open. Without this a keyboard user is left on
  // the trigger with the panel hanging open behind them, and has to tab through
  // the rest of the card to reach it.
  useEffect(() => {
    if (!isOpen) return;
    panelRef.current?.querySelector<HTMLElement>("button:not([disabled]), input")?.focus();
  }, [isOpen]);

  const closeAndFocusTrigger = () => {
    onCloseDropdown();
    triggerRef.current?.focus();
  };

  // Backing out of the create field unmounts the focused input, so hand focus
  // to the button that replaces it — otherwise the panel is left with no
  // focused child and handlePanelBlur closes the whole dropdown.
  const exitCreate = () => {
    setCreating(false);
    setNewListName("");
    restoreCreateFocus.current = true;
  };

  useEffect(() => {
    if (creating || !restoreCreateFocus.current) return;
    restoreCreateFocus.current = false;
    createButtonRef.current?.focus();
  }, [creating]);

  // Tab (or anything else) taking focus out of the panel closes it. Checked on
  // the next frame rather than straight off relatedTarget, because toggling the
  // inline create form unmounts the focused button and fires a focusout with a
  // null relatedTarget before the new field has mounted to receive focus.
  //
  // The trigger is exempt from both checks: focus lands on it before its own
  // click runs, and closing here first would leave that click to reopen the
  // panel it was meant to dismiss.
  const handlePanelBlur = (e: React.FocusEvent) => {
    const next = e.relatedTarget as Node | null;
    if (e.currentTarget.contains(next) || (next && next === triggerRef.current)) return;
    requestAnimationFrame(() => {
      const active = document.activeElement;
      if (active === triggerRef.current) return;
      if (panelRef.current && !panelRef.current.contains(active)) onCloseDropdown();
    });
  };

  const submitNewList = async () => {
    if (!newListName.trim() || savingNewList) return;
    setSavingNewList(true);
    try {
      await onCreateAndAdd(newListName, result);
    } finally {
      setSavingNewList(false);
    }
  };
  const listNames = result.lists
    .map((id) => listMap.get(id)?.name)
    .filter(Boolean) as string[];

  return (
    <PosterCard
      poster={result.poster}
      name={result.name}
      onOpen={() => onSelect(result)}
      eager={eager}
      meta={metaRun(kindWord(result.type), result.year)}
      rating={result.rating != null && result.rating > 0 ? { source: "tmdb", value: result.rating } : null}
      inLists={listNames}
      quickAction={
        <button
          ref={triggerRef}
          type="button"
          // The outside-click handler is a document mousedown listener and the
          // trigger sits outside the panel, so without this it closed the
          // dropdown on mousedown and the click that followed reopened it —
          // making the button unable to dismiss its own panel.
          onMouseDown={(e) => e.stopPropagation()}
          onClick={(e) => {
            e.stopPropagation();
            onToggleDropdown(result.imdbId);
          }}
          // Visible at rest on every pointer, not only on hover: the one thing
          // Search does that the title panel doesn't is add without opening.
          className="pointer-events-auto flex h-8 w-8 items-center justify-center rounded-full bg-accent-500 text-accent-on shadow-e2 transition-[transform,background-color] duration-base hover:bg-accent-600 active:scale-95 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
          aria-label={`Add ${result.name} to a list`}
          aria-expanded={isOpen}
          aria-controls={isOpen ? panelId : undefined}
        >
          <Plus className="h-4 w-4" strokeWidth={3} />
        </button>
      }
    >
      {/* Quick-add dropdown */}
      {isOpen && (
        <div ref={dropdownRef} className="relative z-30 mt-1">
          {/*
            Deliberately not role="menu". The panel holds a text field and a
            submit button for the inline create flow, which the ARIA menu
            pattern doesn't allow — announcing a menu promised arrow-key
            navigation that was never implemented, which is worse for
            assistive-tech users than the plain disclosure this actually is.
            Trigger keeps aria-expanded/aria-controls; the buttons stay in the
            natural tab order, and Escape closes and restores focus.
          */}
          <div
            ref={panelRef}
            id={panelId}
            role="group"
            aria-label={`Add ${result.name} to a list`}
            onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); closeAndFocusTrigger(); } }}
            onBlur={handlePanelBlur}
            className="absolute left-0 right-0 overflow-hidden rounded-2xl shadow-e2"
            style={{ background: "var(--bg-0)", borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)" }}
          >
            <p className={`px-3 py-2.5 ${MICRO_LABEL}`} style={{ borderBottomWidth: 1, borderBottomStyle: "solid", borderBottomColor: "var(--border)", color: "var(--text-mute)" }}>
              Add to list
            </p>
            {lists.length === 0 && !creating && (
              <p className="px-3 py-2.5 text-2xs" style={{ color: "var(--text-mute)" }}>
                No lists yet — create one below.
              </p>
            )}
            {lists.map((list) => {
              const already = result.lists.includes(list.id);
              const key = `${list.id}:${result.imdbId}`;
              const pending = pendingAdds[key];
              return (
                <button
                  key={list.id}
                  type="button"
                  disabled={already || pending}
                  onClick={(e) => {
                    e.stopPropagation();
                    void onAdd(list.id, result);
                  }}
                  className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm transition-colors hover:bg-[var(--surface)] disabled:opacity-50"
                >
                  {already ? (
                    <Check className="h-3.5 w-3.5 text-success" />
                  ) : (
                    <Plus className="h-3.5 w-3.5" style={{ color: "var(--text-mute)" }} />
                  )}
                  <span style={{ color: already ? "var(--text-mute)" : "var(--text-dim)" }}>{list.name}</span>
                  {already && <span className="ml-auto text-2xs" style={{ color: "var(--text-mute)" }}>Added</span>}
                  {pending && (
                    <span className="ml-auto inline-block h-3 w-3 animate-spin rounded-full border border-accent-500 border-t-transparent" />
                  )}
                </button>
              );
            })}
            {creating ? (
              <div
                className="flex items-center gap-2 px-3 py-2.5"
                style={{ borderTopWidth: lists.length > 0 ? 1 : 0, borderTopStyle: "solid", borderTopColor: "var(--border)" }}
                onClick={(e) => e.stopPropagation()}
              >
                <input
                  autoFocus
                  value={newListName}
                  onChange={(e) => setNewListName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") { e.preventDefault(); void submitNewList(); }
                    // stopPropagation so Escape backs out of the create field
                    // only — the panel's handler would otherwise close the
                    // whole dropdown in the same keystroke.
                    if (e.key === "Escape") { e.stopPropagation(); exitCreate(); }
                  }}
                  placeholder="List name…"
                  aria-label="New list name"
                  className="min-w-0 flex-1 rounded-md px-2 py-1 text-sm field-control"
                  style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-1)", color: "var(--text)" }}
                />
                <button
                  type="button"
                  disabled={!newListName.trim() || savingNewList}
                  onClick={(e) => { e.stopPropagation(); void submitNewList(); }}
                  className="btn-primary btn-xs flex-none"
                >
                  {savingNewList ? "…" : "Add"}
                </button>
              </div>
            ) : (
              <button
                ref={createButtonRef}
                type="button"
                onClick={(e) => { e.stopPropagation(); setCreating(true); }}
                className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm text-accent-text transition-colors hover:bg-[var(--surface)]"
                style={{ borderTopWidth: lists.length > 0 ? 1 : 0, borderTopStyle: "solid", borderTopColor: "var(--border)" }}
              >
                <Plus className="h-3.5 w-3.5" />
                Create new list
              </button>
            )}
          </div>
        </div>
      )}

    </PosterCard>
  );
}
