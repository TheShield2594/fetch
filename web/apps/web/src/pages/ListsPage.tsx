import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRightLeft,
  Check,
  CheckSquare,
  Film,
  FolderOpen,
  GripVertical,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Trash2,
  Tv,
  X,
} from "lucide-react";
import { api, ApiError, type CatalogList, type ListItemWithMeta, type MediaType, type SearchResult } from "../api";
import { useTitlePanel } from "../components/media-detail/titlePanel";
import { useToast } from "../hooks/useToast";
import { useCachedState } from "../hooks/useCachedState";
import { useDebouncedSearch } from "../hooks/useDebouncedSearch";
import { useListMembership } from "../hooks/useListMembership";
import { mergeByRelevance } from "../utils/mergeSearchResults";
import { SearchAddDialog, SearchAddResultRow } from "../components/SearchAddDialog";
import { Poster } from "../components/Poster";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { PosterGrid } from "../components/PosterGrid";
import { kindWord, metaRun, PosterCard, PosterCardSkeleton } from "../components/PosterCard";
import { ListLinkPanel } from "../components/ListLinkPanel";
import { ListDetailsDialog } from "../components/ListDetailsDialog";
import { SelectField } from "../components/SelectField";
import { moveItem, ReorderControls, useDragReorder } from "../components/tv/reorder";

/*
 * Lists.
 *
 * Two views on one route. Without `?list=` it is the index: every list as a
 * cover made of its first four posters, with its name, its count and the line
 * the user wrote about it — lists used to be rows of text in a sidebar, and a
 * row of text is not something anyone recognises a list by. With `?list=` it is
 * that list: a compact header, one row for search and sort, and then the
 * posters, which on a phone used to arrive after the tabs, the create form,
 * the title, the search field and the sort pills.
 *
 * Inside a list there are two modes besides browsing. Reorder (from "My order")
 * arranges the list by drag or by the move buttons, and saves the order to the
 * server, where it also orders the list's cover. Select puts a checkbox on every
 * card and an action bar under them: remove, with an Undo, or move to another
 * list. Removing ten titles used to be ten hover-only trash taps; the trash is
 * gone, and a single title still comes out through select or the title panel.
 */

type SortOption = "manual" | "added" | "name" | "year" | "rating";

// Large imported collections (Letterboxd/Trakt) can carry thousands of items;
// rendering them all as DOM nodes at once is what actually costs, not the fetch
// (which is one indexed query), so grow the rendered slice as the user scrolls
// instead of paginating the already-sorted/filtered in-memory list.
const RENDER_PAGE_SIZE = 60;

const SORT_LABELS: Record<SortOption, string> = {
  manual: "My order",
  added: "Date added",
  name: "Name",
  year: "Year",
  rating: "Rating",
};

const DEFAULT_SORT: SortOption = "manual";

// Per list, in this browser. A sort is a way of looking at one list — the
// watchlist by date added, a ranked list in its own order — so one setting for
// every list would be wrong for most of them. Storage can be missing or throw
// (a private window, blocked site data); the default stands in.
const SORT_STORAGE_PREFIX = "fetch:list-sort:";

function readStoredSort(listId: string): SortOption {
  try {
    const stored = window.localStorage.getItem(SORT_STORAGE_PREFIX + listId);
    return stored && stored in SORT_LABELS ? (stored as SortOption) : DEFAULT_SORT;
  } catch {
    return DEFAULT_SORT;
  }
}

function storeSort(listId: string, sort: SortOption) {
  try {
    window.localStorage.setItem(SORT_STORAGE_PREFIX + listId, sort);
  } catch {
    /* a sort that doesn't survive a reload is the whole cost */
  }
}

// Metadata rows are backfilled in the background, so an item can arrive without
// one. Fall back to the title captured when it was added before showing the bare
// IMDb ID, which is never what the user is looking for.
function itemName(item: ListItemWithMeta): string {
  return item.metadata?.name ?? item.title ?? item.imdbId;
}

const itemKey = (item: { type: string; imdbId: string }) => `${item.type}:${item.imdbId}`;

// The server's own manual order (GET /lists covers use it too): arranged rows by
// position, then the never-arranged ones newest first.
function compareManual(a: ListItemWithMeta, b: ListItemWithMeta): number {
  const pa = a.position ?? null;
  const pb = b.position ?? null;
  if (pa !== null && pb !== null) return pa - pb;
  if (pa !== null) return -1;
  if (pb !== null) return 1;
  return new Date(b.addedAt).getTime() - new Date(a.addedAt).getTime();
}

function sortItems(items: readonly ListItemWithMeta[], sortBy: SortOption): ListItemWithMeta[] {
  const sorted = [...items];
  switch (sortBy) {
    case "name":
      return sorted.sort((a, b) => itemName(a).localeCompare(itemName(b)));
    case "year":
      return sorted.sort((a, b) => (b.metadata?.year ?? 0) - (a.metadata?.year ?? 0));
    case "rating":
      return sorted.sort((a, b) => (b.metadata?.rating ?? 0) - (a.metadata?.rating ?? 0));
    case "added":
      return sorted.sort((a, b) => new Date(b.addedAt).getTime() - new Date(a.addedAt).getTime());
    case "manual":
    default:
      return sorted.sort(compareManual);
  }
}

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

const reasonOf = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback;

function toSearchResult(item: ListItemWithMeta, list?: CatalogList): SearchResult {
  return {
    imdbId: item.imdbId,
    type: item.type,
    name: itemName(item),
    year: item.metadata?.year ?? null,
    poster: item.metadata?.poster ?? null,
    description: null,
    genres: item.metadata?.genres ?? [],
    rating: item.metadata?.rating ?? null,
    inWatchlist: list?.kind === "watchlist",
    inCollection: list?.kind === "collection",
    lists: list ? [list.id] : [],
  };
}

// Same three-way choice the search page offers, rather than the movie-or-series
// toggle this modal used to force — one task should not have two models.
type AddFilter = "all" | MediaType;

const ADD_FILTERS: { value: AddFilter; label: string; icon?: typeof Film }[] = [
  { value: "all", label: "All" },
  { value: "movie", label: "Movies", icon: Film },
  { value: "series", label: "Series", icon: Tv },
];

const resultKey = (type: string, imdbId: string) => `${type}:${imdbId}`;

function AddItemModal({
  listId,
  listName,
  existingKeys,
  onClose,
  onAdded,
}: {
  listId: string;
  listName: string;
  /** `type:imdbId` for everything already in the list, so results can say so. */
  existingKeys: Set<string>;
  onClose: () => void;
  onAdded: () => void;
}) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<AddFilter>("all");
  // Added during this modal's lifetime. `existingKeys` is a snapshot taken when
  // the modal opened and the parent's refetch is in flight behind it, so without
  // this a row would sit unchanged for as long as the reload took.
  const [addedKeys, setAddedKeys] = useState<Set<string>>(new Set());
  const { showToast } = useToast();
  // The same add the Search cards and the title panel make, toast and all.
  const membership = useListMembership(showToast);

  // "All" is two requests per search, so both have to be carried by the same
  // guard — a half-resolved pair is exactly the case a bare abort misses. See
  // `useDebouncedSearch`.
  const search = useCallback(
    async (q: string, signal: AbortSignal): Promise<SearchResult[]> => {
      if (filter === "all") {
        const [movies, series] = await Promise.all([
          api.search("movie", q, signal),
          api.search("series", q, signal),
        ]);
        return mergeByRelevance(movies, series, q);
      }
      return api.search(filter, q, signal);
    },
    [filter]
  );

  const { results, searching, error } = useDebouncedSearch<SearchResult>(query, search);

  const handleAdd = (result: SearchResult) => {
    const key = resultKey(result.type, result.imdbId);
    if (addedKeys.has(key) || existingKeys.has(key)) return;
    const markAdded = (member: boolean) =>
      setAddedKeys((prev) => {
        const next = new Set(prev);
        if (member) next.add(key);
        else next.delete(key);
        return next;
      });
    void membership
      .setMembership({ id: listId, name: listName }, { type: result.type, imdbId: result.imdbId, name: result.name }, true, markAdded)
      .then((added) => {
        if (added) onAdded();
      });
  };

  return (
    <SearchAddDialog
      title={`Add to ${listName}`}
      titleId="add-item-modal-title"
      query={query}
      onQueryChange={setQuery}
      placeholder="Search movies & series…"
      inputLabel="Search movies and series"
      onClose={onClose}
      filters={
        <div
          role="group"
          aria-label="Filter by type"
          className="mt-2.5 inline-flex rounded-full bg-[var(--surface)] p-1"
          style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-strong)" }}
        >
          {ADD_FILTERS.map((opt) => {
            const Icon = opt.icon;
            const active = filter === opt.value;
            return (
              <button
                key={opt.value}
                type="button"
                onClick={() => setFilter(opt.value)}
                aria-pressed={active}
                className={`flex items-center gap-1.5 rounded-full px-3.5 py-1 text-xs font-medium transition-[color,background-color,box-shadow] duration-base ${
                  active ? "bg-accent-500 text-accent-on shadow-e1" : "text-[var(--text-mute)] hover:text-[var(--text)]"
                }`}
              >
                {Icon && <Icon className="h-3 w-3" />}
                {opt.label}
              </button>
            );
          })}
        </div>
      }
    >
      {error && <p role="alert" className="mb-2 rounded-lg bg-rose-500/10 border border-rose-500/20 px-3 py-2 text-xs text-danger">{error}</p>}
      {searching && <p className="py-6 text-center text-sm" style={{ color: "var(--text-mute)" }}>Searching…</p>}
      {!searching && query.trim() && results.length === 0 && (
        <p className="py-6 text-center text-sm" style={{ color: "var(--text-mute)" }}>No results found.</p>
      )}
      <div className="space-y-1">
        {results.map((r) => {
          const key = resultKey(r.type, r.imdbId);
          const already = existingKeys.has(key) || addedKeys.has(key);
          const pending = membership.isPending(listId, r);
          return (
            <SearchAddResultRow
              key={key}
              disabled={pending || already}
              muted={already}
              onAdd={() => handleAdd(r)}
              thumbnail={
                r.poster ? (
                  <Poster src={r.poster} alt="" className="h-full w-full" sizes="40px" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center"><Film className="h-4 w-4" style={{ color: "var(--text-mute)" }} /></div>
                )
              }
              title={r.name}
              subtitle={<>{r.year ?? "Unknown"} &middot; {r.type}</>}
              trailing={
                pending ? (
                  <span
                    role="status"
                    aria-label={`Adding ${r.name}`}
                    className="h-4 w-4 flex-none animate-spin rounded-full border-2 border-accent-500 border-t-transparent"
                  />
                ) : already ? (
                  <span className="flex flex-none items-center gap-1 text-2xs font-semibold" style={{ color: "var(--text-mute)" }}>
                    <Check className="h-4 w-4 text-success" />
                    Added
                  </span>
                ) : (
                  <Plus className="h-4 w-4 flex-none text-accent-text" />
                )
              }
            />
          );
        })}
      </div>
    </SearchAddDialog>
  );
}

/** A list's face on the index: its first four posters in a 2×2, or a folder when it has none. */
function ListCover({ covers }: { covers: readonly string[] }) {
  if (covers.length === 0) {
    return (
      <div
        className="flex aspect-poster items-center justify-center rounded-2xl"
        style={{ background: "var(--surface)", borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)" }}
      >
        <FolderOpen className="h-10 w-10" style={{ color: "var(--text-mute)" }} aria-hidden="true" />
      </div>
    );
  }
  return (
    // Decorative: the card's name and count are what it is called.
    <div
      aria-hidden="true"
      className="grid aspect-poster grid-cols-2 grid-rows-2 gap-0.5 overflow-hidden rounded-2xl"
      style={{ background: "var(--surface)" }}
    >
      {[0, 1, 2, 3].map((index) => {
        const src = covers[index];
        return src ? (
          <Poster key={index} src={src} alt="" className="h-full w-full" sizes="(min-width: 640px) 110px, 25vw" />
        ) : (
          <div key={index} style={{ background: "var(--bg-1)" }} />
        );
      })}
    </div>
  );
}

/** Every list, as covers. */
function ListsIndex({
  lists,
  loading,
  onOpen,
  onCreate,
}: {
  lists: readonly CatalogList[];
  loading: boolean;
  onOpen: (listId: string) => void;
  onCreate: () => void;
}) {
  return (
    <section aria-labelledby="lists-index-title">
      <div className="mb-5 flex items-center justify-between gap-3">
        <h1 id="lists-index-title" className={PAGE_TITLE} style={{ color: "var(--text)" }}>Lists</h1>
        <button type="button" onClick={onCreate} className="btn-primary flex-none">
          <Plus className="h-4 w-4" />
          New list
        </button>
      </div>
      {loading ? (
        <PosterGrid>
          {Array.from({ length: 4 }).map((_, i) => <PosterCardSkeleton key={i} />)}
        </PosterGrid>
      ) : (
        <PosterGrid className="content-in">
          {lists.map((list) => (
            <button
              key={list.id}
              type="button"
              onClick={() => onOpen(list.id)}
              className="group rounded-2xl text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
            >
              <div className="transition-transform duration-base group-hover:scale-[1.03]">
                <ListCover covers={list.covers ?? []} />
              </div>
              <p className="mt-2 truncate text-[0.8125rem] font-semibold" style={{ color: "var(--text)" }}>{list.name}</p>
              <p className="meta truncate" style={{ color: "var(--text-mute)" }}>
                {plural(list.itemCount, "title")}
                {list.link && ` · ${list.link.provider === "trakt" ? "Trakt" : "MDBList"}`}
              </p>
              {list.description && (
                <p className="mt-0.5 line-clamp-2 text-2xs leading-snug" style={{ color: "var(--text-dim)" }}>
                  {list.description}
                </p>
              )}
            </button>
          ))}
        </PosterGrid>
      )}
    </section>
  );
}

/** A small disclosure of buttons: the list's overflow, and "Move to…". */
function ActionMenu({
  label,
  trigger,
  triggerClassName,
  placement = "below",
  children,
}: {
  label: string;
  trigger: React.ReactNode;
  triggerClassName: string;
  placement?: "below" | "above";
  children: (close: () => void) => React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  const close = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  return (
    <div
      ref={wrapperRef}
      className="relative"
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.stopPropagation();
          close();
        }
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((value) => !value)}
        className={triggerClassName}
      >
        {trigger}
      </button>
      {open && (
        <div
          id={panelId}
          role="group"
          aria-label={label}
          className={`absolute right-0 z-40 max-h-72 w-56 overflow-y-auto rounded-2xl py-1 shadow-e2 ${
            placement === "above" ? "bottom-full mb-2" : "top-full mt-1"
          }`}
          style={{ background: "var(--bg-0)", borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)" }}
        >
          {children(close)}
        </div>
      )}
    </div>
  );
}

const menuItemClass =
  "flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm transition-colors hover:bg-[var(--surface)] disabled:opacity-50";

/** One list: its header, its controls and its posters. Keyed by list, so every mode resets on a switch. */
function ListView({
  list,
  lists,
  onBack,
  onListsChanged,
  onDeleted,
}: {
  list: CatalogList;
  lists: readonly CatalogList[];
  onBack: () => void;
  onListsChanged: () => Promise<unknown>;
  onDeleted: () => void;
}) {
  const [items, setItems] = useState<ListItemWithMeta[]>([]);
  const [loadingItems, setLoadingItems] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [sortOverride, setSortOverride] = useState<SortOption | null>(null);
  const [filterQuery, setFilterQuery] = useState("");
  const [renderLimit, setRenderLimit] = useState(RENDER_PAGE_SIZE);
  // Reorder mode: the order being arranged, or null when not arranging.
  const [draft, setDraft] = useState<ListItemWithMeta[] | null>(null);
  const [savingOrder, setSavingOrder] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [bulkBusy, setBulkBusy] = useState(false);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const cancelDeleteRef = useRef<HTMLButtonElement>(null);
  // The one in-flight items request, so a newer one can cancel it.
  const itemsRequestRef = useRef<AbortController | null>(null);
  const { showToast } = useToast();
  const { openTitle } = useTitlePanel();
  const sortId = useId();

  const sortBy = sortOverride ?? readStoredSort(list.id);
  const setSortBy = (sort: SortOption) => {
    storeSort(list.id, sort);
    setSortOverride(sort);
    setRenderLimit(RENDER_PAGE_SIZE);
  };

  // Both paths that load items — the first load and the refetch after an add
  // or an undo — go through here, and each cancels the one before it, so a slow
  // response can never land on top of a newer one. Leaving the list (this view
  // is keyed by it) aborts whatever is still out.
  const loadItems = useCallback(async () => {
    itemsRequestRef.current?.abort();
    const controller = new AbortController();
    itemsRequestRef.current = controller;
    const { signal } = controller;

    setLoadingItems(true);
    setError(null);
    try {
      const { items: loaded } = await api.getListItems(list.id, signal);
      if (signal.aborted) return;
      setItems(loaded);
    } catch (err) {
      if (signal.aborted) return;
      setError(reasonOf(err, "Failed to load items"));
    } finally {
      if (!signal.aborted) setLoadingItems(false);
    }
  }, [list.id]);

  useEffect(() => {
    void loadItems();
    return () => itemsRequestRef.current?.abort();
  }, [loadItems]);

  // Focus follows the confirm block in, landing on Cancel rather than Delete:
  // the block exists to make the second step deliberate.
  useEffect(() => {
    if (confirmDelete) cancelDeleteRef.current?.focus();
  }, [confirmDelete]);

  // What the add modal marks as "Added". Built from the full list rather than
  // what the search box narrows it to — a filtered-out item is still in it.
  const existingItemKeys = useMemo(() => new Set(items.map(itemKey)), [items]);

  const displayedItems = useMemo(() => {
    const q = filterQuery.trim().toLowerCase();
    const matching = q ? items.filter((item) => itemName(item).toLowerCase().includes(q)) : items;
    return sortItems(matching, sortBy);
  }, [items, filterQuery, sortBy]);

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || renderLimit >= displayedItems.length) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          setRenderLimit((prev) => Math.min(prev + RENDER_PAGE_SIZE, displayedItems.length));
        }
      },
      { rootMargin: "800px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [renderLimit, displayedItems.length]);

  const visibleItems = displayedItems.slice(0, renderLimit);

  const handleDelete = async () => {
    setDeleting(true);
    setError(null);
    try {
      await api.deleteList(list.id);
      onDeleted();
    } catch (err) {
      setError(reasonOf(err, "Failed to delete list"));
      setDeleting(false);
    }
  };

  const saveDetails = async ({ name, description }: { name: string; description: string }) => {
    await api.updateList(list.id, { name, description });
    await onListsChanged();
  };

  // ─── Reorder ───

  const { rowProps, dragIndex, dragOverIndex } = useDragReorder((from, to) =>
    setDraft((current) => (current ? moveItem(current, from, to) : current))
  );

  const startArranging = () => {
    setSelecting(false);
    setFilterQuery("");
    setDraft(sortItems(items, "manual"));
  };

  const saveOrder = async () => {
    if (!draft) return;
    const before = sortItems(items, "manual");
    const changed = draft.some((item, index) => itemKey(item) !== itemKey(before[index] ?? item));
    if (!changed) {
      setDraft(null);
      return;
    }
    setSavingOrder(true);
    try {
      await api.reorderList(list.id, draft);
      setItems(draft.map((item, position) => ({ ...item, position })));
      setDraft(null);
      showToast("Order saved", "success");
      // The cover on the index follows the order.
      void onListsChanged();
    } catch (err) {
      showToast(`Couldn't save the order (${reasonOf(err, "unknown error")})`, "error");
    } finally {
      setSavingOrder(false);
    }
  };

  // ─── Select ───

  const selectedItems = items.filter((item) => selected.has(itemKey(item)));

  const toggleSelected = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const allShownSelected = displayedItems.length > 0 && displayedItems.every((item) => selected.has(itemKey(item)));

  const endSelecting = () => {
    setSelecting(false);
    setSelected(new Set());
  };

  // A list's arrangement, for an undo to put back: re-added titles come back
  // with no position, so without this an undone removal would also quietly
  // shuffle an arranged list.
  const arrangementOf = (all: readonly ListItemWithMeta[]) =>
    all.some((item) => item.position != null) ? sortItems(all, "manual") : null;

  const undoRemove = async (removed: readonly ListItemWithMeta[], arrangement: ListItemWithMeta[] | null) => {
    const results = await Promise.allSettled(
      removed.map((item) => api.addToList(list.id, { type: item.type, imdbId: item.imdbId, title: itemName(item) }))
    );
    // A 409 is a title already back — another tab, the TV — which is the same
    // outcome as putting it back.
    const failed = results.filter(
      (result) => result.status === "rejected" && !(result.reason instanceof ApiError && result.reason.status === 409)
    ).length;
    if (arrangement) {
      try {
        await api.reorderList(list.id, arrangement);
      } catch {
        /* the titles are back; only their places were lost */
      }
    }
    if (failed > 0) showToast(`Couldn't put back ${plural(failed, "title")}`, "error");
    await Promise.all([loadItems(), onListsChanged()]);
  };

  const removeSelected = async () => {
    const chosen = selectedItems;
    if (chosen.length === 0 || bulkBusy) return;
    const arrangement = arrangementOf(items);
    const chosenKeys = new Set(chosen.map(itemKey));
    setBulkBusy(true);
    // Optimistic: they leave the grid now, and come back if the call fails.
    setItems((prev) => prev.filter((item) => !chosenKeys.has(itemKey(item))));
    try {
      await api.removeFromListBulk(list.id, chosen);
      endSelecting();
      showToast(
        chosen.length === 1 ? `Removed "${itemName(chosen[0]!)}"` : `Removed ${plural(chosen.length, "title")}`,
        "info",
        { action: { label: "Undo", onAction: () => void undoRemove(chosen, arrangement) } }
      );
      void onListsChanged();
    } catch (err) {
      setItems((prev) => [...prev, ...chosen.filter((item) => !prev.some((p) => itemKey(p) === itemKey(item)))]);
      showToast(`Couldn't remove ${plural(chosen.length, "title")} (${reasonOf(err, "unknown error")})`, "error");
    } finally {
      setBulkBusy(false);
    }
  };

  // Into the other list first, out of this one second, and only the ones that
  // made it across come out — a move that half fails leaves a title in both
  // lists, never in neither.
  const moveSelected = async (target: CatalogList) => {
    const chosen = selectedItems;
    if (chosen.length === 0 || bulkBusy) return;
    setBulkBusy(true);
    try {
      const results = await Promise.allSettled(
        chosen.map((item) => api.addToList(target.id, { type: item.type, imdbId: item.imdbId, title: itemName(item) }))
      );
      const landed = chosen.filter((_, index) => {
        const result = results[index];
        return (
          result?.status === "fulfilled" ||
          (result?.status === "rejected" && result.reason instanceof ApiError && result.reason.status === 409)
        );
      });
      const failed = chosen.length - landed.length;
      if (landed.length === 0) {
        showToast(`Couldn't move to ${target.name}`, "error");
        return;
      }
      try {
        await api.removeFromListBulk(list.id, landed);
      } catch (err) {
        showToast(`Added to ${target.name}, but couldn't take them out of ${list.name} (${reasonOf(err, "unknown error")})`, "error");
        void onListsChanged();
        return;
      }
      const landedKeys = new Set(landed.map(itemKey));
      setItems((prev) => prev.filter((item) => !landedKeys.has(itemKey(item))));
      endSelecting();
      showToast(
        failed > 0
          ? `Moved ${plural(landed.length, "title")} to ${target.name}; ${failed} couldn't be moved`
          : `Moved ${plural(landed.length, "title")} to ${target.name}`,
        failed > 0 ? "error" : "success"
      );
      void onListsChanged();
    } finally {
      setBulkBusy(false);
    }
  };

  const moveTargets = lists.filter((other) => other.id !== list.id);
  const canArrange = sortBy === "manual" && items.length > 1;
  const iconButton =
    "flex h-9 w-9 flex-none items-center justify-center rounded-full border transition-colors hover:bg-[var(--surface)] hover:text-[var(--text)]";
  const iconButtonStyle = { borderColor: "var(--border-strong)", color: "var(--text-dim)" };

  return (
    <div className="flex flex-col">
      {/* The visible headline is the list's name; the page keeps a stable h1. */}
      <h1 className="sr-only">Lists</h1>

      {/* Header: back, name, count — and the list's actions in one row. */}
      <div className="mb-4">
        <button
          type="button"
          onClick={onBack}
          className="-ml-1 mb-2 inline-flex items-center gap-1 rounded-lg px-1 py-0.5 text-xs font-medium hover:text-[var(--text)]"
          style={{ color: "var(--text-mute)" }}
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          All lists
        </button>
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <h2 className={`truncate ${PAGE_TITLE}`} style={{ color: "var(--text)" }}>{list.name}</h2>
            <p className="meta-row mt-1" style={{ color: "var(--text-mute)" }}>
              {loadingItems ? plural(list.itemCount, "title") : plural(items.length, "title")}
            </p>
          </div>
          <div className="flex flex-none items-center gap-2">
            <button
              type="button"
              onClick={() => setEditing(true)}
              aria-label="Edit list details"
              className={iconButton}
              style={iconButtonStyle}
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
            {list.kind === "custom" && (
              <ActionMenu
                label="More list actions"
                trigger={<MoreHorizontal className="h-4 w-4" />}
                triggerClassName={iconButton}
              >
                {(close) => (
                  <button
                    type="button"
                    onClick={() => {
                      close();
                      setConfirmDelete(true);
                    }}
                    className={`${menuItemClass} text-danger`}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    Delete list
                  </button>
                )}
              </ActionMenu>
            )}
            <button type="button" onClick={() => setShowAddModal(true)} className="btn-primary flex-none">
              <Plus className="h-4 w-4" />
              Add
            </button>
          </div>
        </div>
        {list.description && (
          <p className="mt-2 max-w-prose text-sm" style={{ color: "var(--text-dim)" }}>{list.description}</p>
        )}
      </div>

      {confirmDelete && (
        <div className="mb-4 rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3">
          <div className="mb-2 flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 flex-none text-danger" />
            <p role="alert" className="text-xs font-semibold text-danger">Delete "{list.name}"?</p>
          </div>
          <p className="mb-3 text-xs" style={{ color: "var(--text-mute)" }}>
            This will remove the list and all its items.
            {list.link && ` Its ${list.link.provider === "trakt" ? "Trakt" : "MDBList"} list is kept.`}
          </p>
          <div className="flex gap-2">
            <button type="button" disabled={deleting} onClick={() => void handleDelete()} className="btn-danger btn-sm">
              {deleting ? "Deleting…" : "Delete"}
            </button>
            <button ref={cancelDeleteRef} type="button" onClick={() => setConfirmDelete(false)} className="btn-secondary btn-sm">
              Cancel
            </button>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="mb-4 rounded-xl border border-rose-500/20 bg-rose-500/5 px-4 py-3 text-sm text-danger">{error}</p>
      )}

      {/* Search and sort on one row, then the mode switches beside them. */}
      {items.length > 0 && !draft && (
        <div className="mb-4 flex items-center gap-2">
          <div className="relative min-w-0 flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2" style={{ color: "var(--text-mute)" }} />
            <input
              value={filterQuery}
              onChange={(e) => {
                setFilterQuery(e.target.value);
                setRenderLimit(RENDER_PAGE_SIZE);
              }}
              placeholder="Search this list…"
              aria-label="Search within list"
              className="w-full rounded-full border py-2 pl-9 pr-3 text-sm field-control"
              style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
            />
          </div>
          <label htmlFor={sortId} className="sr-only">Sort by</label>
          <SelectField
            id={sortId}
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as SortOption)}
            wrapperClassName="flex-none"
            className="rounded-full py-2 pl-3 pr-8 text-sm"
            style={{ color: "var(--text-dim)" }}
          >
            {(Object.entries(SORT_LABELS) as [SortOption, string][]).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </SelectField>
          {canArrange && !selecting && (
            <button type="button" onClick={startArranging} aria-label="Reorder" title="Reorder" className={iconButton} style={iconButtonStyle}>
              <GripVertical className="h-4 w-4" />
            </button>
          )}
          <button
            type="button"
            onClick={() => (selecting ? endSelecting() : setSelecting(true))}
            aria-pressed={selecting}
            aria-label="Select"
            title="Select"
            className={`${iconButton} ${selecting ? "bg-accent-500/15 text-accent-text" : ""}`}
            style={selecting ? { borderColor: "var(--border-strong)" } : iconButtonStyle}
          >
            <CheckSquare className="h-4 w-4" />
          </button>
        </div>
      )}

      {draft && (
        <div
          className="mb-4 flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2"
          style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}
        >
          <p className="min-w-0 flex-1 text-sm" style={{ color: "var(--text-dim)" }}>
            Drag the posters, or use the arrows, to arrange this list.
          </p>
          <button type="button" onClick={() => setDraft(null)} disabled={savingOrder} className="btn-secondary btn-sm">
            Cancel
          </button>
          <button type="button" onClick={() => void saveOrder()} disabled={savingOrder} className="btn-primary btn-sm">
            {savingOrder ? "Saving…" : "Save order"}
          </button>
        </div>
      )}

      {/* Items grid */}
      {loadingItems ? (
        <PosterGrid>
          {Array.from({ length: 8 }).map((_, i) => <PosterCardSkeleton key={i} />)}
        </PosterGrid>
      ) : items.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <div className="flex h-20 w-20 items-center justify-center rounded-full ring-1" style={{ backgroundColor: "var(--surface)", "--tw-ring-color": "var(--border)" } as React.CSSProperties}>
            <FolderOpen className="h-10 w-10" style={{ color: "var(--text-mute)" }} />
          </div>
          <p className={`mt-4 ${SECTION_TITLE}`} style={{ color: "var(--text-dim)" }}>This list is empty</p>
          <p className="mt-1 text-sm" style={{ color: "var(--text-mute)" }}>
            Click <span className="font-semibold text-accent-text">+ Add</span> to search and add titles.
          </p>
        </div>
      ) : draft ? (
        <PosterGrid>
          {draft.map((item, index) => {
            const name = itemName(item);
            return (
              <div
                key={itemKey(item)}
                {...rowProps(index)}
                className={`rounded-2xl transition-opacity ${dragIndex === index ? "opacity-40" : ""} ${
                  dragOverIndex === index && dragIndex !== index ? "ring-2 ring-accent-500 ring-offset-2 ring-offset-[var(--bg-0)]" : ""
                }`}
              >
                <PosterCard
                  poster={item.metadata?.poster}
                  name={name}
                  hover={false}
                  meta={metaRun(kindWord(item.type), item.metadata?.year)}
                />
                <div className="mt-1 flex justify-center">
                  <ReorderControls index={index} count={draft.length} label={name} disabled={savingOrder} onMove={(from, to) => setDraft((current) => (current ? moveItem(current, from, to) : current))} />
                </div>
              </div>
            );
          })}
        </PosterGrid>
      ) : displayedItems.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <div className="flex h-20 w-20 items-center justify-center rounded-full ring-1" style={{ backgroundColor: "var(--surface)", "--tw-ring-color": "var(--border)" } as React.CSSProperties}>
            <Search className="h-10 w-10" style={{ color: "var(--text-mute)" }} />
          </div>
          <p className={`mt-4 ${SECTION_TITLE}`} style={{ color: "var(--text-dim)" }}>No matches</p>
          <p className="mt-1 text-sm" style={{ color: "var(--text-mute)" }}>Try a different search term.</p>
        </div>
      ) : (
        <PosterGrid className="content-in">
          {visibleItems.map((item, index) => {
            const name = itemName(item);
            const rating = item.metadata?.rating;
            const key = itemKey(item);
            const isSelected = selected.has(key);
            return (
              <PosterCard
                key={key}
                poster={item.metadata?.poster}
                name={name}
                onOpen={selecting ? undefined : () => openTitle(toSearchResult(item, list))}
                openLabel={`Open details for ${name}`}
                eager={index < 5}
                meta={metaRun(kindWord(item.type), item.metadata?.year)}
                rating={rating != null && rating > 0 ? { source: "tmdb", value: rating } : null}
                selected={selecting && isSelected}
                selection={
                  selecting ? (
                    // The whole card is the label, so a tap anywhere on it
                    // toggles the box — and the box is a real checkbox.
                    <label className="absolute inset-0 cursor-pointer rounded-2xl">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => toggleSelected(key)}
                        aria-label={`Select ${name}`}
                        className="absolute left-2 top-2 h-5 w-5 cursor-pointer accent-accent-500"
                      />
                    </label>
                  ) : undefined
                }
              />
            );
          })}
        </PosterGrid>
      )}
      {!loadingItems && !draft && renderLimit < displayedItems.length && <div ref={sentinelRef} className="h-4" />}

      {selecting && (
        <div
          role="toolbar"
          aria-label="Selected titles"
          className="glass-surface sticky bottom-[calc(5rem+env(safe-area-inset-bottom))] z-30 mt-4 flex flex-wrap items-center gap-2 rounded-2xl border px-3 py-2 shadow-e2 sm:bottom-4"
          style={{ borderColor: "var(--border)", background: "color-mix(in srgb, var(--bg-1) 92%, transparent)" }}
        >
          <p className="min-w-0 flex-1 text-sm font-medium" style={{ color: "var(--text)" }} aria-live="polite">
            {selected.size === 0 ? "Select titles" : `${selected.size} selected`}
          </p>
          <button
            type="button"
            onClick={() => setSelected(allShownSelected ? new Set() : new Set(displayedItems.map(itemKey)))}
            className="btn-secondary btn-sm"
          >
            {allShownSelected ? "Select none" : "Select all"}
          </button>
          {moveTargets.length > 0 && (
            <ActionMenu
              label="Move to…"
              placement="above"
              trigger={
                <>
                  <ArrowRightLeft className="h-3.5 w-3.5" />
                  <span>Move to…</span>
                </>
              }
              triggerClassName="btn-secondary btn-sm disabled:opacity-50"
            >
              {(close) =>
                moveTargets.map((target) => (
                  <button
                    key={target.id}
                    type="button"
                    disabled={selected.size === 0 || bulkBusy}
                    onClick={() => {
                      close();
                      void moveSelected(target);
                    }}
                    className={menuItemClass}
                    style={{ color: "var(--text-dim)" }}
                  >
                    <span className="truncate">{target.name}</span>
                  </button>
                ))
              }
            </ActionMenu>
          )}
          <button
            type="button"
            onClick={() => void removeSelected()}
            disabled={selected.size === 0 || bulkBusy}
            className="btn-danger btn-sm"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Remove
          </button>
          <button type="button" onClick={endSelecting} aria-label="Done selecting" className="rounded-lg p-1.5 hover:bg-[var(--surface)]" style={{ color: "var(--text-mute)" }}>
            <X className="h-4 w-4" />
          </button>
        </div>
      )}

      {/* The link to Trakt or MDBList is set up once and rarely looked at, so it
          sits under the posters rather than between them and the header. */}
      {list.kind === "custom" && (
        <div className="mt-6">
          <ListLinkPanel
            list={list}
            onMessage={showToast}
            onChanged={() => {
              void onListsChanged();
              void loadItems();
            }}
          />
        </div>
      )}

      {showAddModal && (
        <AddItemModal
          listId={list.id}
          listName={list.name}
          existingKeys={existingItemKeys}
          onClose={() => setShowAddModal(false)}
          onAdded={() => {
            void loadItems();
            void onListsChanged();
          }}
        />
      )}

      {editing && (
        <ListDetailsDialog
          title="Edit list"
          submitLabel="Save"
          initialName={list.name}
          initialDescription={list.description ?? ""}
          onSubmit={saveDetails}
          onClose={() => setEditing(false)}
        />
      )}
    </div>
  );
}

/**
 * The lists page. The open list lives in the URL (`?list=`) so it can be
 * linked, bookmarked and reloaded, and so Back goes from a list to the index.
 */
export function ListsPage() {
  const [lists, setLists] = useCachedState<CatalogList[]>("lists:all", []);
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedListId = searchParams.get("list");
  // Deliberately false even when the cache seeded `lists`: this flag gates
  // "that list doesn't exist", and a list deleted on another device is still
  // present in a stale cache. That message waits for the fresh response.
  const [listsLoaded, setListsLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  // Pushing (rather than replacing) is what makes Back go from a list to the
  // index rather than off the page.
  const selectList = useCallback(
    (listId: string | null, options?: { replace?: boolean }) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (listId) next.set("list", listId);
          else next.delete("list");
          return next;
        },
        { replace: options?.replace ?? false }
      );
    },
    [setSearchParams]
  );

  const loadLists = useCallback(async (signal?: AbortSignal) => {
    setError(null);
    try {
      const { lists: loaded } = await api.getLists(signal);
      // `setLists` writes through to the shared cache, so a response that
      // outlives the page has to be dropped here rather than filed under
      // whichever profile is active when it lands.
      if (signal?.aborted) return [];
      setLists(loaded);
      // Only a *successful* load can judge an unknown ID: marking this on
      // failure too would report a list missing when what failed was the
      // request that would have found it.
      setListsLoaded(true);
      return loaded;
    } catch (err) {
      if (signal?.aborted) return [];
      setError(reasonOf(err, "Failed to load lists"));
      return [];
    }
  }, [setLists]);

  useEffect(() => {
    const controller = new AbortController();
    void loadLists(controller.signal);
    return () => controller.abort();
  }, [loadLists]);

  const selectedList = lists.find((l) => l.id === selectedListId);
  const listNotFound = listsLoaded && selectedListId !== null && !selectedList;

  // The built-in collection is a list nobody made; empty, it is a card of
  // nothing on the index. It turns up once something is in it — and a link
  // straight to it still opens it.
  const shownLists = useMemo(
    () => lists.filter((list) => !(list.kind === "collection" && list.itemCount === 0)),
    [lists]
  );

  const createList = async ({ name, description }: { name: string; description: string }) => {
    const { list } = await api.createList(name, description || undefined);
    await loadLists();
    selectList(list.id);
  };

  return (
    <div>
      {error && (
        <p role="alert" className="mb-4 rounded-xl border border-rose-500/20 bg-rose-500/5 px-4 py-3 text-sm text-danger">{error}</p>
      )}

      {selectedListId === null ? (
        <ListsIndex
          lists={shownLists}
          loading={!listsLoaded && lists.length === 0 && !error}
          onOpen={(listId) => selectList(listId)}
          onCreate={() => setCreating(true)}
        />
      ) : selectedList ? (
        <ListView
          key={selectedList.id}
          list={selectedList}
          lists={lists}
          onBack={() => selectList(null)}
          onListsChanged={loadLists}
          onDeleted={() => {
            // Back to the index, replacing the entry: Back shouldn't lead to
            // a list that no longer exists.
            selectList(null, { replace: true });
            void loadLists();
          }}
        />
      ) : listNotFound ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <h1 className="sr-only">Lists</h1>
          <div className="flex h-20 w-20 items-center justify-center rounded-full ring-1" style={{ backgroundColor: "var(--surface)", "--tw-ring-color": "var(--border)" } as React.CSSProperties}>
            <FolderOpen className="h-10 w-10" style={{ color: "var(--text-mute)" }} />
          </div>
          <p className={`mt-4 ${SECTION_TITLE}`} style={{ color: "var(--text-dim)" }}>That list no longer exists</p>
          <p className="mt-1 text-sm" style={{ color: "var(--text-mute)" }}>
            It may have been deleted since you saved the link.
          </p>
          <button type="button" onClick={() => selectList(null)} className="btn-secondary mt-4">
            <ArrowLeft className="h-4 w-4" />
            All lists
          </button>
        </div>
      ) : (
        <>
          <h1 className="sr-only">Lists</h1>
          <PosterGrid>
            {Array.from({ length: 8 }).map((_, i) => <PosterCardSkeleton key={i} />)}
          </PosterGrid>
        </>
      )}

      {creating && (
        <ListDetailsDialog
          title="New list"
          submitLabel="Create"
          onSubmit={createList}
          onClose={() => setCreating(false)}
        />
      )}
    </div>
  );
}
