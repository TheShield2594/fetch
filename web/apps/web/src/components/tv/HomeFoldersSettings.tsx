import { type ReactNode, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertCircle, Eye, FolderPlus, Loader2, Plus, RotateCcw, Save, Search, Trash2, X } from "lucide-react";
import {
  api,
  ApiError,
  type CatalogList,
  type CatalogMeta,
  type Collection,
  type CollectionFolder,
  type CollectionSource,
  type CollectionViewMode,
  type TmdbSourceType,
} from "../../api";
import type { TmdbEntity, TmdbEntityKind } from "../../api/collections";
import { Poster } from "../Poster";
import { SegmentedControl } from "../SegmentedControl";
import { SelectField } from "../SelectField";
import { useToast } from "../../hooks/useToast";
import { useProfile } from "../../hooks/useProfile";
import {
  type AddonManifest,
  addonBaseUrl,
  describeSource,
  idFromPaste,
  newCollection,
  newFolder,
  rememberAddonUrl,
  rememberedAddonUrl,
  TMDB_GENRES,
  TMDB_NETWORKS,
  TMDB_SOURCE_LABELS,
} from "../../utils/collections";
import { ReorderControls, inputClass, inputStyle, labelClass, labelStyle, moveItem, rowClass, rowStyle, useDragReorder } from "./reorder";

/*
 * Home folders: custom rows on the TV's Home screen.
 *
 * This was the Collections page — a page of its own in the rail, with its own
 * word for a thing the TV and the desktop app have since renamed, and its own
 * form controls. It lives in Settings now, beside Home rows, so everything
 * about the TV's Home screen is in one place and "Collection" on the web means
 * one thing again (the built-in list).
 *
 * The vocabulary is the TV's: Home folders is the feature, a *folder row* is
 * one row on Home, and each row holds *folders* filled from sources. The API
 * and the stored document still say "collections", as the TV's storage does.
 *
 * Rebuilt on the shared controls (`reorder.tsx`'s inputs and drag handles,
 * `SegmentedControl`, `SelectField`, the switch), with search in place of
 * typed TMDB ids, drag to reorder, Undo on every delete, and a live preview
 * of each folder's first source.
 */

const VIEW_MODES: { value: CollectionViewMode; label: string }[] = [
  { value: "TABBED_GRID", label: "Tabs" },
  { value: "ROWS", label: "Rows" },
  { value: "FOLLOW_LAYOUT", label: "Like Home" },
];

const TILE_SHAPES: { value: NonNullable<CollectionFolder["tileShape"]>; label: string }[] = [
  { value: "POSTER", label: "Poster" },
  { value: "LANDSCAPE", label: "Wide" },
  { value: "SQUARE", label: "Square" },
];

type Provider = Exclude<CollectionSource["provider"], "local">;

const PROVIDERS: { value: Provider; label: string }[] = [
  { value: "fetch", label: "Your list" },
  { value: "tmdb", label: "TMDB" },
  { value: "trakt", label: "Trakt" },
  { value: "addon", label: "Addon" },
];

const MEDIA: { value: "MOVIE" | "TV"; label: string }[] = [
  { value: "MOVIE", label: "Films" },
  { value: "TV", label: "Shows" },
];

const ENTITY_KIND: Partial<Record<TmdbSourceType, TmdbEntityKind>> = {
  PERSON: "person",
  DIRECTOR: "person",
  COMPANY: "company",
  COLLECTION: "collection",
};

const iconButton = "rounded-lg p-1.5 hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus";

function Field({ label, children, className = "" }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block min-w-0 ${className}`}>
      <span className={labelClass} style={labelStyle}>{label}</span>
      {children}
    </label>
  );
}

/** A row of posters standing in for a loading one, the same height. */
function PreviewSkeleton({ count = 8 }: { count?: number }) {
  return (
    <div aria-hidden="true" className="flex gap-2 overflow-hidden">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton aspect-[2/3] w-[4.5rem] flex-none rounded-lg" />
      ))}
    </div>
  );
}

// ─── Preview ───

/**
 * What one source yields, drawn as the row it will be on the TV. Used inline
 * under each folder (its first source, so the folder's shape is visible while
 * it is edited) and on demand for a source that hasn't been added yet.
 */
function SourcePreview({ source, compact = false }: { source: CollectionSource; compact?: boolean }) {
  const [metas, setMetas] = useState<CatalogMeta[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [addonUrl, setAddonUrl] = useState(() => rememberedAddonUrl(source.addonId) ?? "");
  const [attempt, setAttempt] = useState(0);

  // A source made by a current TV or this page carries its addon's address; an
  // older one only has the id, and needs the address typed once.
  const knownAddonUrl = source.addonUrl ?? rememberedAddonUrl(source.addonId);
  const needsAddonUrl = source.provider === "addon" && !knownAddonUrl;
  const sourceKey = JSON.stringify(source);

  useEffect(() => {
    if (needsAddonUrl || source.provider === "local") return;
    let cancelled = false;
    const request = source.provider === "addon" ? { ...source, addonUrl: knownAddonUrl! } : source;
    api.previewCollectionSource(request).then(
      (result) => { if (!cancelled) setMetas(result.metas); },
      (err: unknown) => { if (!cancelled) setError(err instanceof Error ? err.message : "Could not load a preview."); }
    );
    return () => { cancelled = true; };
    // `sourceKey` stands for `source`, which a parent rebuilds every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceKey, needsAddonUrl, knownAddonUrl, attempt]);

  if (source.provider === "local") {
    return <p className="text-xs" style={{ color: "var(--text-mute)" }}>Kept on the TV — it can only be previewed there.</p>;
  }
  if (needsAddonUrl) {
    return (
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (!addonUrl.trim() || !source.addonId) return;
          rememberAddonUrl(source.addonId, addonBaseUrl(addonUrl));
          setMetas(null);
          setError(null);
          setAttempt((n) => n + 1);
        }}
      >
        <input
          value={addonUrl}
          onChange={(e) => setAddonUrl(e.target.value)}
          placeholder={`Manifest URL for ${source.addonId}`}
          aria-label="Addon manifest URL"
          className={inputClass}
          style={inputStyle}
        />
        <button type="submit" className="btn-secondary btn-sm">Load</button>
      </form>
    );
  }
  if (error) {
    return <p role="alert" className="flex items-center gap-1.5 text-sm text-danger"><AlertCircle size={14} /> {error}</p>;
  }
  if (!metas) return <PreviewSkeleton count={compact ? 6 : 8} />;
  if (metas.length === 0) {
    return <p className="text-xs" style={{ color: "var(--text-mute)" }}>Nothing yet — this would be an empty row.</p>;
  }
  return (
    <ul aria-label={`Preview of ${describeSource(source)}`} className="scrollbar-hide content-in flex gap-2 overflow-x-auto pb-1">
      {metas.map((meta) => (
        <li
          key={`${meta.type}:${meta.id}`}
          title={meta.year ? `${meta.name} (${meta.year})` : meta.name}
          className={`flex-none ${compact ? "w-[4.5rem]" : "w-[5.5rem]"}`}
        >
          <Poster src={meta.poster} alt={meta.name} sizes="96px" className="aspect-[2/3] w-full rounded-lg" />
          {!compact && <p className="mt-1 truncate text-2xs" style={{ color: "var(--text-dim)" }}>{meta.name}</p>}
        </li>
      ))}
    </ul>
  );
}

// ─── Picking a TMDB person, studio or collection ───

/**
 * Search in place of a typed TMDB id. A listbox of results under a search
 * field; picking one sets both the id and the row's title, which is what the
 * two typed fields used to ask for separately.
 */
function EntityPicker({
  kind,
  value,
  onPick,
}: {
  kind: TmdbEntityKind;
  value: { id: number; name: string } | null;
  onPick: (entity: { id: number; name: string } | null) => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<TmdbEntity[]>([]);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const listId = useId();
  const noun = kind === "person" ? "person" : kind === "company" ? "studio" : "film collection";

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setResults([]);
      setSearching(false);
      return;
    }
    const controller = new AbortController();
    setSearching(true);
    const timer = window.setTimeout(() => {
      api.searchTmdbEntities(kind, q, controller.signal).then(
        ({ results: found }) => { setResults(found); setError(null); setSearching(false); },
        (err: unknown) => {
          if (controller.signal.aborted) return;
          setError(err instanceof Error ? err.message : "Search failed.");
          setSearching(false);
        }
      );
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [kind, query]);

  if (value) {
    return (
      <div className="flex items-center gap-2 rounded-xl border px-3 py-2" style={rowStyle}>
        <span className="min-w-0 flex-1 truncate text-sm" style={{ color: "var(--text)" }}>{value.name}</span>
        <button type="button" onClick={() => onPick(null)} className="btn-secondary btn-sm">Change</button>
      </div>
    );
  }

  return (
    <div>
      <div className="relative">
        <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--text-mute)" }} />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={`Search for a ${noun}…`}
          aria-label={`Search TMDB for a ${noun}`}
          aria-controls={listId}
          className={`${inputClass} pl-9`}
          style={inputStyle}
        />
      </div>
      {error && <p role="alert" className="mt-1.5 text-xs text-danger">{error}</p>}
      {searching && results.length === 0 && <p className="mt-1.5 text-xs" style={{ color: "var(--text-mute)" }}>Searching…</p>}
      {!searching && query.trim().length >= 2 && results.length === 0 && !error && (
        <p className="mt-1.5 text-xs" style={{ color: "var(--text-mute)" }}>No {noun} by that name.</p>
      )}
      {results.length > 0 && (
        <ul id={listId} aria-label={`${noun} results`} className="mt-1.5 max-h-56 space-y-0.5 overflow-y-auto">
          {results.map((entity) => (
            <li key={entity.id}>
              <button
                type="button"
                onClick={() => onPick({ id: entity.id, name: entity.name })}
                className="flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors duration-fast hover:bg-[var(--surface-strong)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
              >
                <span className="h-8 w-8 flex-none overflow-hidden rounded-md" style={{ background: "var(--surface-strong)" }}>
                  {entity.image && <img src={entity.image} alt="" loading="lazy" className="h-full w-full object-cover" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm" style={{ color: "var(--text)" }}>{entity.name}</span>
                  {entity.detail && <span className="block truncate text-2xs" style={{ color: "var(--text-mute)" }}>{entity.detail}</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ─── Adding a source ───

function SourceBuilder({
  lists,
  onAdd,
  onCancel,
}: {
  lists: CatalogList[];
  onAdd: (source: CollectionSource) => void;
  onCancel: () => void;
}) {
  const [provider, setProvider] = useState<Provider>(lists.length > 0 ? "fetch" : "tmdb");
  const [mediaType, setMediaType] = useState<"MOVIE" | "TV">("MOVIE");
  const [previewing, setPreviewing] = useState(false);

  // Your list
  const [listId, setListId] = useState(lists[0]?.id ?? "");
  const [listMedia, setListMedia] = useState<"" | "MOVIE" | "TV">("");

  // TMDB
  const [tmdbType, setTmdbType] = useState<TmdbSourceType>("DISCOVER");
  const [entity, setEntity] = useState<{ id: number; name: string } | null>(null);
  const [networkId, setNetworkId] = useState(String(TMDB_NETWORKS[0]!.id));
  const [pastedId, setPastedId] = useState("");
  const [rowTitle, setRowTitle] = useState("");
  const [genres, setGenres] = useState<number[]>([]);
  const [year, setYear] = useState("");
  const [minRating, setMinRating] = useState("");
  const [sortBy, setSortBy] = useState("popularity.desc");

  // Trakt
  const [traktId, setTraktId] = useState("");

  // Addon
  const [manifestUrl, setManifestUrl] = useState("");
  const [manifest, setManifest] = useState<AddonManifest | null>(null);
  const [catalogKey, setCatalogKey] = useState("");
  const [manifestError, setManifestError] = useState<string | null>(null);
  const [loadingManifest, setLoadingManifest] = useState(false);

  const source = useMemo((): CollectionSource | null => {
    switch (provider) {
      case "fetch": {
        const list = lists.find((l) => l.id === listId);
        if (!list) return null;
        return { provider: "fetch", listId: list.id, title: list.name, ...(listMedia ? { mediaType: listMedia } : {}) };
      }
      case "tmdb": {
        if (tmdbType === "DISCOVER") {
          const filters = {
            ...(genres.length ? { withGenres: genres.join(",") } : {}),
            ...(/^\d{4}$/.test(year) ? { year: Number(year) } : {}),
            ...(minRating ? { voteAverageGte: Number(minRating), voteCountGte: 50 } : {}),
          };
          return { provider: "tmdb", tmdbSourceType: "DISCOVER", title: rowTitle.trim() || "Discover", mediaType, sortBy, filters };
        }
        let picked: { id: number; name: string } | null = null;
        if (ENTITY_KIND[tmdbType]) picked = entity;
        else if (tmdbType === "NETWORK") {
          const network = TMDB_NETWORKS.find((n) => String(n.id) === networkId);
          const typed = networkId === "other" ? idFromPaste(pastedId) : null;
          picked = network ? { id: network.id, name: network.name } : typed ? { id: typed, name: `Network ${typed}` } : null;
        } else {
          const typed = idFromPaste(pastedId);
          picked = typed ? { id: typed, name: `${TMDB_SOURCE_LABELS[tmdbType]} ${typed}` } : null;
        }
        if (!picked) return null;
        return {
          provider: "tmdb",
          tmdbSourceType: tmdbType,
          tmdbId: picked.id,
          title: rowTitle.trim() || picked.name,
          mediaType: tmdbType === "COLLECTION" ? "MOVIE" : tmdbType === "NETWORK" ? "TV" : mediaType,
          sortBy: tmdbType === "LIST" || tmdbType === "COLLECTION" ? "original" : sortBy,
          filters: {},
        };
      }
      case "trakt": {
        const id = idFromPaste(traktId);
        if (!id) return null;
        return {
          provider: "trakt",
          traktListId: id,
          title: rowTitle.trim() || `Trakt list ${id}`,
          mediaType,
          sortBy: "rank",
          sortHow: "asc",
        };
      }
      case "addon": {
        const catalog = manifest?.catalogs.find((c) => `${c.type}/${c.id}` === catalogKey);
        if (!manifest || !catalog) return null;
        return {
          provider: "addon",
          addonId: manifest.id,
          addonUrl: manifest.transportUrl,
          type: catalog.type,
          catalogId: catalog.id,
          title: catalog.name,
        };
      }
    }
  }, [provider, lists, listId, listMedia, tmdbType, entity, networkId, pastedId, rowTitle, genres, year, minRating, sortBy, mediaType, traktId, manifest, catalogKey]);

  const loadManifest = async () => {
    setLoadingManifest(true);
    setManifestError(null);
    try {
      const { manifest: loaded } = await api.readAddonManifest(manifestUrl);
      if (loaded.catalogs.length === 0) throw new Error("This addon has no catalogs.");
      rememberAddonUrl(loaded.id, loaded.transportUrl);
      setManifest(loaded);
      setCatalogKey(`${loaded.catalogs[0]!.type}/${loaded.catalogs[0]!.id}`);
    } catch (err) {
      setManifest(null);
      setManifestError(err instanceof Error ? err.message : "Could not read that addon.");
    } finally {
      setLoadingManifest(false);
    }
  };

  const mediaPicker = <SegmentedControl label="Type" options={MEDIA} value={mediaType} onChange={setMediaType} className="max-w-[12rem]" />;
  const titleField = (
    <Field label="Row title">
      <input value={rowTitle} onChange={(e) => setRowTitle(e.target.value)} placeholder="Shown on the TV" className={inputClass} style={inputStyle} />
    </Field>
  );

  return (
    <div className="mt-3 space-y-3 rounded-xl border p-3" style={{ borderColor: "var(--border-strong)" }}>
      <SegmentedControl label="Source" options={PROVIDERS} value={provider} onChange={(next) => { setProvider(next); setPreviewing(false); }} />

      {provider === "fetch" && (
        lists.length === 0 ? (
          <p className="text-sm" style={{ color: "var(--text-mute)" }}>
            Make a list on the Lists page first — any list you made can become a folder on the TV.
          </p>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="List">
              <SelectField value={listId} onChange={(e) => setListId(e.target.value)} className={inputClass} style={inputStyle} chevronSize={16}>
                {lists.map((list) => (
                  <option key={list.id} value={list.id}>{list.name} ({list.itemCount})</option>
                ))}
              </SelectField>
            </Field>
            <Field label="Show">
              <SelectField value={listMedia} onChange={(e) => setListMedia(e.target.value as "" | "MOVIE" | "TV")} className={inputClass} style={inputStyle} chevronSize={16}>
                <option value="">Films and shows</option>
                <option value="MOVIE">Films only</option>
                <option value="TV">Shows only</option>
              </SelectField>
            </Field>
          </div>
        )
      )}

      {provider === "tmdb" && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="From" className="min-w-[12rem] flex-1">
              <SelectField
                value={tmdbType}
                onChange={(e) => { setTmdbType(e.target.value as TmdbSourceType); setEntity(null); setPastedId(""); }}
                className={inputClass}
                style={inputStyle}
                chevronSize={16}
              >
                {(Object.keys(TMDB_SOURCE_LABELS) as TmdbSourceType[]).map((type) => (
                  <option key={type} value={type}>{TMDB_SOURCE_LABELS[type]}</option>
                ))}
              </SelectField>
            </Field>
            {tmdbType !== "COLLECTION" && tmdbType !== "NETWORK" && tmdbType !== "LIST" && mediaPicker}
          </div>
          {tmdbType === "DISCOVER" ? (
            <>
              <fieldset>
                <legend className={labelClass} style={labelStyle}>Genres</legend>
                <div className="flex flex-wrap gap-1.5">
                  {TMDB_GENRES[mediaType].map((genre) => {
                    const on = genres.includes(genre.id);
                    return (
                      <button
                        key={genre.id}
                        type="button"
                        aria-pressed={on}
                        onClick={() => setGenres((g) => (on ? g.filter((id) => id !== genre.id) : [...g, genre.id]))}
                        className={`rounded-full border px-2.5 py-1 text-xs transition-colors duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-focus ${on ? "bg-accent-500/15 text-accent-text" : "hover:bg-[var(--surface-strong)]"}`}
                        style={{ borderColor: on ? "rgb(var(--accent-rgb) / 0.4)" : "var(--border)", ...(on ? {} : { color: "var(--text-dim)" }) }}
                      >
                        {genre.name}
                      </button>
                    );
                  })}
                </div>
              </fieldset>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field label="Year">
                  <input value={year} onChange={(e) => setYear(e.target.value.replace(/\D/g, "").slice(0, 4))} inputMode="numeric" placeholder="Any" className={inputClass} style={inputStyle} />
                </Field>
                <Field label="Rated at least">
                  <SelectField value={minRating} onChange={(e) => setMinRating(e.target.value)} className={inputClass} style={inputStyle} chevronSize={16}>
                    <option value="">Any rating</option>
                    {[5, 6, 7, 8].map((r) => <option key={r} value={r}>{r}+</option>)}
                  </SelectField>
                </Field>
                <Field label="Sort">
                  <SelectField value={sortBy} onChange={(e) => setSortBy(e.target.value)} className={inputClass} style={inputStyle} chevronSize={16}>
                    <option value="popularity.desc">Most popular</option>
                    <option value="vote_average.desc">Highest rated</option>
                    <option value="vote_count.desc">Most voted</option>
                    <option value={mediaType === "TV" ? "first_air_date.desc" : "primary_release_date.desc"}>Newest</option>
                  </SelectField>
                </Field>
              </div>
              {titleField}
            </>
          ) : ENTITY_KIND[tmdbType] ? (
            <>
              <EntityPicker kind={ENTITY_KIND[tmdbType]!} value={entity} onPick={setEntity} />
              {titleField}
            </>
          ) : tmdbType === "NETWORK" ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Network">
                <SelectField value={networkId} onChange={(e) => setNetworkId(e.target.value)} className={inputClass} style={inputStyle} chevronSize={16}>
                  {TMDB_NETWORKS.map((network) => <option key={network.id} value={network.id}>{network.name}</option>)}
                  <option value="other">Another network…</option>
                </SelectField>
              </Field>
              {networkId === "other" && (
                <Field label="TMDB network id or link">
                  <input value={pastedId} onChange={(e) => setPastedId(e.target.value)} placeholder="e.g. 1024" className={inputClass} style={inputStyle} />
                </Field>
              )}
              {titleField}
            </div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="TMDB list link or id">
                <input value={pastedId} onChange={(e) => setPastedId(e.target.value)} placeholder="themoviedb.org/list/…" className={inputClass} style={inputStyle} />
              </Field>
              {titleField}
            </div>
          )}
        </div>
      )}

      {provider === "trakt" && (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Trakt list id">
              <input value={traktId} onChange={(e) => setTraktId(e.target.value)} inputMode="numeric" placeholder="The number in the list's API link" className={inputClass} style={inputStyle} />
            </Field>
            {titleField}
          </div>
          {mediaPicker}
        </div>
      )}

      {provider === "addon" && (
        <div className="space-y-3">
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>
            The folder uses the addon installed on the TV when there is one, and loads straight from this address
            when there isn&apos;t.
          </p>
          <div className="flex gap-2">
            <input
              value={manifestUrl}
              onChange={(e) => setManifestUrl(e.target.value)}
              placeholder="https://…/manifest.json"
              aria-label="Addon manifest URL"
              className={inputClass}
              style={inputStyle}
            />
            <button type="button" onClick={() => void loadManifest()} disabled={!manifestUrl.trim() || loadingManifest} className="btn-secondary btn-sm flex-none">
              {loadingManifest ? <Loader2 size={14} className="animate-spin" /> : null} Load
            </button>
          </div>
          {manifestError && <p role="alert" className="text-xs text-danger">{manifestError}</p>}
          {manifest && (
            <Field label={`Catalog from ${manifest.name}`}>
              <SelectField value={catalogKey} onChange={(e) => setCatalogKey(e.target.value)} className={inputClass} style={inputStyle} chevronSize={16}>
                {manifest.catalogs.map((c) => (
                  <option key={`${c.type}/${c.id}`} value={`${c.type}/${c.id}`}>{c.name} ({c.type})</option>
                ))}
              </SelectField>
            </Field>
          )}
        </div>
      )}

      {previewing && source && (
        <div className="rounded-lg p-2" style={{ background: "var(--surface)" }}>
          <SourcePreview source={source} />
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button type="button" disabled={!source} onClick={() => source && onAdd(source)} className="btn-primary btn-sm">
          <Plus size={14} /> Add source
        </button>
        <button type="button" disabled={!source} onClick={() => setPreviewing((on) => !on)} aria-pressed={previewing} className="btn-secondary btn-sm">
          <Eye size={14} /> Preview
        </button>
        <button type="button" onClick={onCancel} className="btn-secondary btn-sm">Cancel</button>
      </div>
    </div>
  );
}

// ─── Folders ───

function FolderEditor({
  folder,
  index,
  count,
  lists,
  dragProps,
  dropTarget,
  onChange,
  onMove,
  onRemove,
  onRemoveSource,
}: {
  folder: CollectionFolder;
  index: number;
  count: number;
  lists: CatalogList[];
  dragProps: ReturnType<ReturnType<typeof useDragReorder>["rowProps"]>;
  dropTarget: boolean;
  onChange: (folder: CollectionFolder) => void;
  onMove: (from: number, to: number) => void;
  onRemove: () => void;
  onRemoveSource: (sourceIndex: number) => void;
}) {
  const [adding, setAdding] = useState(false);
  const sources = folder.sources ?? [];
  const label = folder.title || "Untitled folder";
  const firstPreviewable = sources.find((s) => s.provider !== "local");

  return (
    <li
      {...dragProps}
      className={`${rowClass} transition-[border-color] duration-fast`}
      style={{ ...rowStyle, ...(dropTarget ? { borderColor: "var(--accent)" } : {}) }}
    >
      <div className="flex flex-wrap items-end gap-2">
        <ReorderControls index={index} count={count} label={label} onMove={onMove} />
        <Field label="Icon" className="w-14">
          <input
            value={folder.coverEmoji ?? ""}
            onChange={(e) => onChange({ ...folder, coverEmoji: e.target.value.slice(0, 4) || null })}
            aria-label={`Emoji for ${label}`}
            className={`${inputClass} px-2 text-center`}
            style={inputStyle}
          />
        </Field>
        <Field label="Folder" className="min-w-[9rem] flex-1">
          <input value={folder.title} onChange={(e) => onChange({ ...folder, title: e.target.value })} aria-label="Folder title" className={inputClass} style={inputStyle} />
        </Field>
        <SegmentedControl
          label={`Tiles in ${label}`}
          options={TILE_SHAPES}
          value={folder.tileShape ?? "POSTER"}
          onChange={(tileShape) => onChange({ ...folder, tileShape })}
          className="max-w-[15rem]"
        />
        <button type="button" onClick={onRemove} aria-label={`Remove folder ${label}`} className={`${iconButton} hover:text-danger`} style={{ color: "var(--text-mute)" }}>
          <Trash2 size={15} />
        </button>
      </div>

      <ul className="mt-3 space-y-1.5" aria-label={`Sources in ${label}`}>
        {sources.length === 0 && (
          <li className="text-xs" style={{ color: "var(--text-mute)" }}>No sources yet — this folder will be empty on the TV.</li>
        )}
        {sources.map((source, sourceIndex) => (
          <li key={sourceIndex} className="flex items-center gap-2 rounded-lg px-2.5 py-1.5" style={{ background: "var(--surface)" }}>
            <span className="min-w-0 flex-1 truncate text-sm" style={{ color: "var(--text)" }}>{describeSource(source)}</span>
            <button
              type="button"
              onClick={() => onRemoveSource(sourceIndex)}
              aria-label={`Remove ${describeSource(source)}`}
              className={`${iconButton} p-1 hover:text-danger`}
              style={{ color: "var(--text-mute)" }}
            >
              <X size={14} />
            </button>
          </li>
        ))}
      </ul>

      {/* The live preview: what the folder's first source fills it with, at
          the folder's tile shape's scale, so a change is checked as it is made
          rather than after a save and a trip to the TV. */}
      {firstPreviewable && (
        <div className="mt-3">
          <SourcePreview key={JSON.stringify(firstPreviewable)} source={firstPreviewable} compact />
        </div>
      )}

      {adding ? (
        <SourceBuilder
          lists={lists}
          onCancel={() => setAdding(false)}
          onAdd={(source) => {
            onChange({ ...folder, sources: [...sources, source] });
            setAdding(false);
          }}
        />
      ) : (
        <button type="button" onClick={() => setAdding(true)} className="btn-secondary btn-sm mt-3">
          <Plus size={14} /> Add source
        </button>
      )}
    </li>
  );
}

// ─── A folder row ───

function FolderRowEditor({
  collection,
  lists,
  onChange,
  onRemove,
}: {
  collection: Collection;
  lists: CatalogList[];
  onChange: (collection: Collection) => void;
  onRemove: () => void;
}) {
  const { showToast } = useToast();
  // Read by the Undo handlers, which run after this render's `collection` is stale.
  const latest = useRef(collection);
  latest.current = collection;
  const setFolders = (folders: CollectionFolder[]) => onChange({ ...latest.current, folders });
  const moveFolder = (from: number, to: number) => setFolders(moveItem(latest.current.folders, from, to));
  const { rowProps, dragOverIndex } = useDragReorder(moveFolder);

  const removeFolder = (folder: CollectionFolder, index: number) => {
    setFolders(collection.folders.filter((f) => f.id !== folder.id));
    showToast(`Removed ${folder.title || "folder"}`, "info", {
      action: {
        label: "Undo",
        onAction: () => {
          const folders = [...latest.current.folders];
          folders.splice(Math.min(index, folders.length), 0, folder);
          setFolders(folders);
        },
      },
    });
  };

  const removeSource = (folder: CollectionFolder, sourceIndex: number) => {
    const sources = folder.sources ?? [];
    const source = sources[sourceIndex];
    if (!source) return;
    const without = (f: CollectionFolder): CollectionFolder => ({ ...f, sources: (f.sources ?? []).filter((_, i) => i !== sourceIndex) });
    setFolders(collection.folders.map((f) => (f.id === folder.id ? without(f) : f)));
    showToast(`Removed ${source.title ?? "source"}`, "info", {
      action: {
        label: "Undo",
        onAction: () => {
          setFolders(latest.current.folders.map((f) => {
            if (f.id !== folder.id) return f;
            const next = [...(f.sources ?? [])];
            next.splice(Math.min(sourceIndex, next.length), 0, source);
            return { ...f, sources: next };
          }));
        },
      },
    });
  };

  return (
    <section className="space-y-4" aria-label={`Editing ${collection.title || "folder row"}`}>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Folder row" className="min-w-[12rem] flex-1">
          <input value={collection.title} onChange={(e) => onChange({ ...collection, title: e.target.value })} aria-label="Folder row title" className={inputClass} style={inputStyle} />
        </Field>
        <button type="button" onClick={onRemove} className="btn-secondary btn-sm">
          <Trash2 size={14} /> Delete row
        </button>
      </div>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <div>
          <span className={labelClass} style={labelStyle}>On the TV</span>
          <SegmentedControl
            label="Layout on the TV"
            options={VIEW_MODES}
            value={collection.viewMode ?? "TABBED_GRID"}
            onChange={(viewMode) => onChange({ ...collection, viewMode })}
            className="max-w-[18rem]"
          />
        </div>
        <label className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
          <input
            type="checkbox"
            role="switch"
            className="switch-control"
            checked={collection.pinToTop ?? false}
            onChange={(e) => onChange({ ...collection, pinToTop: e.target.checked })}
          />
          Pin to the top of Home
        </label>
        <label className="flex items-center gap-2 text-sm" style={{ color: "var(--text)" }}>
          <input
            type="checkbox"
            role="switch"
            className="switch-control"
            checked={collection.showAllTab ?? true}
            onChange={(e) => onChange({ ...collection, showAllTab: e.target.checked })}
          />
          Show an &ldquo;All&rdquo; tab
        </label>
      </div>

      <ol className="space-y-3" aria-label="Folders">
        {collection.folders.map((folder, index) => (
          <FolderEditor
            key={folder.id}
            folder={folder}
            index={index}
            count={collection.folders.length}
            lists={lists}
            dragProps={rowProps(index)}
            dropTarget={dragOverIndex === index}
            onChange={(next) => setFolders(latest.current.folders.map((f) => (f.id === folder.id ? next : f)))}
            onMove={moveFolder}
            onRemove={() => removeFolder(folder, index)}
            onRemoveSource={(sourceIndex) => removeSource(folder, sourceIndex)}
          />
        ))}
      </ol>
      <button type="button" onClick={() => setFolders([...collection.folders, newFolder()])} className="btn-secondary btn-sm">
        <FolderPlus size={14} /> Add folder
      </button>
    </section>
  );
}

// ─── The section ───

/**
 * Home folders for the active profile: its folder rows, each a set of folders
 * filled from sources. Saved as one document per profile, the way the TV saves
 * them, and picked up by paired TVs on their next sync.
 */
export function HomeFoldersSettings() {
  const { profile } = useProfile();
  const profileId = profile?.id ?? null;
  const [saved, setSaved] = useState<Collection[] | null>(null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [draft, setDraft] = useState<Collection[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [lists, setLists] = useState<CatalogList[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saving, setSaving] = useState(false);
  const { showToast } = useToast();
  const draftRef = useRef(draft);
  draftRef.current = draft;

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [doc, { lists: fetched }] = await Promise.all([api.getCollections(), api.getLists()]);
      setSaved(doc.collections);
      setDraft(doc.collections);
      setUpdatedAt(doc.updatedAt);
      setSelectedId((current) => (current && doc.collections.some((c) => c.id === current) ? current : doc.collections[0]?.id ?? null));
      setLists(fetched.filter((list) => list.kind === "custom"));
      setConflict(false);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Could not load Home folders.");
    }
  }, []);

  // Per profile: switching profile is switching whose Home this is.
  useEffect(() => { void load(); }, [load, profileId]);

  const dirty = saved !== null && JSON.stringify(saved) !== JSON.stringify(draft);
  const selected = draft.find((c) => c.id === selectedId) ?? null;
  const moveRow = (from: number, to: number) => setDraft((d) => moveItem(d, from, to));
  const { rowProps, dragOverIndex } = useDragReorder(moveRow);

  // A row added since the last save goes with the discard; if it was the one
  // open, the editor would otherwise show the empty state beside a full list.
  const discard = () => {
    if (!saved) return;
    setDraft(saved);
    if (!saved.some((c) => c.id === selectedId)) setSelectedId(saved[0]?.id ?? null);
  };

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const doc = await api.saveCollections(draft, updatedAt);
      setSaved(doc.collections);
      setDraft(doc.collections);
      setUpdatedAt(doc.updatedAt);
      showToast("Home folders saved");
    } catch (err) {
      if (err instanceof ApiError && err.code === "collections_conflict") setConflict(true);
      else setSaveError(err instanceof Error ? err.message : "Could not save Home folders.");
    } finally {
      setSaving(false);
    }
  };

  const addRow = () => {
    const created = newCollection();
    setDraft((d) => [...d, created]);
    setSelectedId(created.id);
  };

  const removeRow = (row: Collection) => {
    const index = draft.findIndex((c) => c.id === row.id);
    setDraft((d) => d.filter((c) => c.id !== row.id));
    setSelectedId(draft.find((c) => c.id !== row.id)?.id ?? null);
    showToast(`Deleted ${row.title || "folder row"}`, "info", {
      action: {
        label: "Undo",
        onAction: () => {
          const next = [...draftRef.current];
          next.splice(Math.min(index, next.length), 0, row);
          setDraft(next);
          setSelectedId(row.id);
        },
      },
    });
  };

  if (loadError) {
    return (
      <div role="alert" className="flex flex-wrap items-center gap-2 text-sm text-danger">
        <AlertCircle size={16} /> {loadError}
        <button type="button" onClick={() => void load()} className="btn-secondary btn-sm">Try again</button>
      </div>
    );
  }

  if (saved === null) {
    return (
      <div aria-busy="true" aria-label="Loading Home folders" className="space-y-3">
        <div className="skeleton h-9 w-1/2 rounded-xl" />
        <div className="skeleton h-28 rounded-xl" />
        <div className="skeleton h-28 rounded-xl" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Rows of folders on the TV&apos;s Home screen. Each folder opens a page filled from your lists, TMDB, Trakt or
        an addon. Paired TVs pick changes up on their next sync.
      </p>

      {conflict && (
        <div role="alert" className="flex flex-wrap items-center gap-3 rounded-xl border px-3 py-2 text-sm" style={{ borderColor: "var(--border-strong)", color: "var(--text)" }}>
          <AlertCircle size={16} className="text-danger" />
          These Home folders were changed on a TV since you opened them. Reload to start from the latest — your
          edits here will be lost.
          <button type="button" onClick={() => void load()} className="btn-secondary btn-sm">Reload</button>
        </div>
      )}
      {saveError && <p role="alert" className="flex items-center gap-1.5 text-sm text-danger"><AlertCircle size={14} /> {saveError}</p>}

      <div className="space-y-4">
        <ol aria-label="Folder rows" className="space-y-1.5">
          {draft.map((row, index) => {
            const current = row.id === selectedId;
            const label = row.title || "Untitled folder row";
            return (
              <li
                key={row.id}
                {...rowProps(index)}
                className="flex items-center gap-1 rounded-xl border pr-2 transition-[border-color,background-color] duration-fast"
                style={{
                  borderColor: dragOverIndex === index ? "var(--accent)" : current ? "rgb(var(--accent-rgb) / 0.4)" : "var(--border)",
                  background: current ? "rgb(var(--accent-rgb) / 0.08)" : "var(--bg-1)",
                }}
              >
                <button
                  type="button"
                  onClick={() => setSelectedId(row.id)}
                  aria-current={current ? "true" : undefined}
                  className="min-w-0 flex-1 rounded-xl px-3 py-2.5 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                >
                  <span className={`block truncate text-sm font-semibold ${current ? "text-accent-text" : ""}`} style={current ? undefined : { color: "var(--text)" }}>
                    {label}
                  </span>
                  <span className="meta-row" style={{ color: "var(--text-mute)" }}>
                    {row.folders.length} {row.folders.length === 1 ? "folder" : "folders"}
                    {row.pinToTop ? " · pinned" : ""}
                  </span>
                </button>
                <ReorderControls index={index} count={draft.length} label={label} onMove={moveRow} />
              </li>
            );
          })}
        </ol>
        <button type="button" onClick={addRow} className="btn-secondary btn-sm">
          <Plus size={14} /> New folder row
        </button>

        {selected ? (
          <div className="border-t pt-4" style={{ borderColor: "var(--border)" }}>
            <FolderRowEditor
              key={selected.id}
              collection={selected}
              lists={lists}
              onChange={(next) => setDraft((d) => d.map((c) => (c.id === next.id ? next : c)))}
              onRemove={() => removeRow(selected)}
            />
          </div>
        ) : (
          <div className={rowClass} style={rowStyle}>
            <p className="text-sm font-semibold" style={{ color: "var(--text)" }}>No folder rows yet</p>
            <p className="mt-1 text-sm" style={{ color: "var(--text-dim)" }}>
              Start one, add a folder, and fill it from a list you made here, a TMDB search, a Trakt list or an addon
              catalog.
            </p>
          </div>
        )}
      </div>

      {/* Sticky so the decision is always in reach of the edit that made it. */}
      <div
        className="sticky bottom-[calc(5rem+env(safe-area-inset-bottom))] z-10 flex flex-wrap items-center justify-end gap-2 rounded-xl border px-3 py-2 sm:bottom-3"
        style={{ borderColor: "var(--border)", background: "var(--bg-1)", boxShadow: dirty ? "var(--elevation-2)" : undefined }}
      >
        <span className="mr-auto text-xs" role="status" style={{ color: "var(--text-mute)" }}>
          {dirty ? "Unsaved changes" : "Saved"}
        </span>
        <button type="button" onClick={discard} disabled={!dirty || saving} className="btn-secondary btn-sm">
          <RotateCcw size={14} /> Discard
        </button>
        <button type="button" onClick={() => void save()} disabled={!dirty || saving} className="btn-primary btn-sm">
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} Save
        </button>
      </div>
    </div>
  );
}
