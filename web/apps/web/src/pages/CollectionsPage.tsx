import { type ReactNode, useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  ArrowDown,
  ArrowUp,
  Eye,
  FolderPlus,
  LayoutGrid,
  Loader2,
  Plus,
  RotateCcw,
  Save,
  Trash2,
  X,
} from "lucide-react";
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
} from "../api";
import { Poster } from "../components/Poster";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { useToast } from "../hooks/useToast";
import {
  type AddonManifest,
  addonBaseUrl,
  describeSource,
  fetchAddonCatalogPage,
  fetchAddonManifest,
  newCollection,
  newFolder,
  rememberAddonUrl,
  rememberedAddonUrl,
  TMDB_GENRES,
  TMDB_SOURCE_LABELS,
} from "../utils/collections";

const INPUT =
  "w-full min-w-0 rounded-lg border px-2.5 py-1.5 text-sm focus:border-claw-500 focus:outline-none focus:ring-2 focus:ring-claw-500/15";
const INPUT_STYLE = { borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-1)" };
const CARD = "rounded-2xl border p-4";
const CARD_STYLE = { borderColor: "var(--border)", background: "var(--bg-1)" };
const LABEL = "mb-1 block text-xs font-semibold uppercase tracking-[0.04em]";

const VIEW_MODE_LABELS: Record<CollectionViewMode, string> = {
  TABBED_GRID: "Tabs with a grid",
  ROWS: "One row per folder",
  FOLLOW_LAYOUT: "Follow the Home layout",
};

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className={LABEL} style={{ color: "var(--text-mute)" }}>{label}</span>
      {children}
    </label>
  );
}

const move = <T,>(items: T[], index: number, delta: number): T[] => {
  const target = index + delta;
  if (target < 0 || target >= items.length) return items;
  const next = [...items];
  [next[index], next[target]] = [next[target]!, next[index]!];
  return next;
};

// ─── Preview ───

/**
 * The first page a source yields, so a row can be checked before it is saved.
 * TMDB, Trakt and website lists come from the server; addon catalogs straight
 * from the addon, whose address the browser has to know.
 */
function SourcePreview({ source, onClose }: { source: CollectionSource; onClose: () => void }) {
  const [metas, setMetas] = useState<CatalogMeta[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [addonUrl, setAddonUrl] = useState(() => rememberedAddonUrl(source.addonId) ?? "");
  const [attempt, setAttempt] = useState(0);

  const needsAddonUrl = source.provider === "addon" && !rememberedAddonUrl(source.addonId);

  useEffect(() => {
    if (needsAddonUrl) return;
    let cancelled = false;
    const load =
      source.provider === "addon"
        ? fetchAddonCatalogPage(rememberedAddonUrl(source.addonId)!, source)
        : api.previewCollectionSource(source).then((result) => result.metas);
    load.then(
      (result) => { if (!cancelled) setMetas(result); },
      (err: unknown) => { if (!cancelled) setError(err instanceof Error ? err.message : "Could not load a preview."); }
    );
    return () => { cancelled = true; };
  }, [source, needsAddonUrl, attempt]);

  return (
    <section className={CARD} style={CARD_STYLE} aria-label="Preview">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold" style={{ color: "var(--text)" }}>Preview</h3>
          <p className="truncate text-xs" style={{ color: "var(--text-mute)" }}>{describeSource(source)}</p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close preview" className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
          <X size={16} />
        </button>
      </div>
      {needsAddonUrl ? (
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
            className={INPUT}
            style={INPUT_STYLE}
          />
          <button type="submit" className="btn-secondary btn-sm">Load</button>
        </form>
      ) : error ? (
        <p role="alert" className="flex items-center gap-1.5 text-sm text-danger"><AlertCircle size={14} /> {error}</p>
      ) : !metas ? (
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={14} className="animate-spin" /> Loading…
        </p>
      ) : metas.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--text-mute)" }}>Nothing yet — this row would be empty.</p>
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(5.5rem,1fr))] gap-2">
          {metas.map((meta) => (
            <li key={`${meta.type}:${meta.id}`} title={meta.year ? `${meta.name} (${meta.year})` : meta.name}>
              <Poster src={meta.poster} alt={meta.name} sizes="96px" className="aspect-[2/3] w-full rounded-lg" />
              <p className="mt-1 truncate text-2xs" style={{ color: "var(--text-dim)" }}>{meta.name}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ─── Adding a source ───

type Provider = CollectionSource["provider"];

const PROVIDER_LABELS: Record<Provider, string> = {
  fetch: "Website list",
  tmdb: "TMDB",
  trakt: "Trakt list",
  addon: "Addon catalog",
};

function SourceBuilder({
  lists,
  onAdd,
  onPreview,
  onCancel,
}: {
  lists: CatalogList[];
  onAdd: (source: CollectionSource) => void;
  onPreview: (source: CollectionSource) => void;
  onCancel: () => void;
}) {
  const [provider, setProvider] = useState<Provider>(lists.length > 0 ? "fetch" : "tmdb");
  const [mediaType, setMediaType] = useState<"MOVIE" | "TV">("MOVIE");

  // Website list
  const [listId, setListId] = useState(lists[0]?.id ?? "");
  const [listMedia, setListMedia] = useState<"" | "MOVIE" | "TV">("");

  // TMDB
  const [tmdbType, setTmdbType] = useState<TmdbSourceType>("DISCOVER");
  const [tmdbId, setTmdbId] = useState("");
  const [tmdbTitle, setTmdbTitle] = useState("");
  const [genres, setGenres] = useState<number[]>([]);
  const [year, setYear] = useState("");
  const [minRating, setMinRating] = useState("");
  const [sortBy, setSortBy] = useState("popularity.desc");

  // Trakt
  const [traktId, setTraktId] = useState("");
  const [traktTitle, setTraktTitle] = useState("");

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
          return { provider: "tmdb", tmdbSourceType: "DISCOVER", title: "Discover", mediaType, sortBy, filters };
        }
        const id = Number(tmdbId);
        if (!Number.isInteger(id) || id <= 0) return null;
        return {
          provider: "tmdb",
          tmdbSourceType: tmdbType,
          tmdbId: id,
          title: tmdbTitle.trim() || `${TMDB_SOURCE_LABELS[tmdbType]} ${id}`,
          mediaType: tmdbType === "COLLECTION" ? "MOVIE" : tmdbType === "NETWORK" ? "TV" : mediaType,
          sortBy: tmdbType === "LIST" || tmdbType === "COLLECTION" ? "original" : sortBy,
          filters: {},
        };
      }
      case "trakt": {
        const id = Number(traktId);
        if (!Number.isInteger(id) || id <= 0) return null;
        return {
          provider: "trakt",
          traktListId: id,
          title: traktTitle.trim() || `Trakt list ${id}`,
          mediaType,
          sortBy: "rank",
          sortHow: "asc",
        };
      }
      case "addon": {
        const catalog = manifest?.catalogs.find((c) => `${c.type}/${c.id}` === catalogKey);
        if (!manifest || !catalog) return null;
        return { provider: "addon", addonId: manifest.id, type: catalog.type, catalogId: catalog.id, title: catalog.name };
      }
    }
  }, [provider, lists, listId, listMedia, tmdbType, tmdbId, tmdbTitle, genres, year, minRating, sortBy, mediaType, traktId, traktTitle, manifest, catalogKey]);

  const loadManifest = async () => {
    setLoadingManifest(true);
    setManifestError(null);
    try {
      const loaded = await fetchAddonManifest(manifestUrl);
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

  const mediaPicker = (
    <Field label="Type">
      <select value={mediaType} onChange={(e) => setMediaType(e.target.value as "MOVIE" | "TV")} className={INPUT} style={INPUT_STYLE}>
        <option value="MOVIE">Movies</option>
        <option value="TV">Series</option>
      </select>
    </Field>
  );

  return (
    <div className="mt-3 space-y-3 rounded-xl border p-3" style={{ borderColor: "var(--border)" }}>
      <div role="tablist" aria-label="Source type" className="flex flex-wrap gap-1">
        {(Object.keys(PROVIDER_LABELS) as Provider[]).map((p) => (
          <button
            key={p}
            type="button"
            role="tab"
            aria-selected={provider === p}
            onClick={() => setProvider(p)}
            className={`rounded-lg px-2.5 py-1 text-xs font-medium ${provider === p ? "bg-claw-500/15 text-claw-text" : "hover:bg-[var(--surface-strong)]"}`}
            style={provider === p ? undefined : { color: "var(--text-dim)" }}
          >
            {PROVIDER_LABELS[p]}
          </button>
        ))}
      </div>

      {provider === "fetch" && (
        lists.length === 0 ? (
          <p className="text-sm" style={{ color: "var(--text-mute)" }}>
            Make a list on the Lists page first — any custom list can become a row on the TV.
          </p>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="List">
              <select value={listId} onChange={(e) => setListId(e.target.value)} className={INPUT} style={INPUT_STYLE}>
                {lists.map((list) => (
                  <option key={list.id} value={list.id}>{list.name} ({list.itemCount})</option>
                ))}
              </select>
            </Field>
            <Field label="Show">
              <select value={listMedia} onChange={(e) => setListMedia(e.target.value as "" | "MOVIE" | "TV")} className={INPUT} style={INPUT_STYLE}>
                <option value="">Movies and series</option>
                <option value="MOVIE">Movies only</option>
                <option value="TV">Series only</option>
              </select>
            </Field>
          </div>
        )
      )}

      {provider === "tmdb" && (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="From">
              <select value={tmdbType} onChange={(e) => setTmdbType(e.target.value as TmdbSourceType)} className={INPUT} style={INPUT_STYLE}>
                {(Object.keys(TMDB_SOURCE_LABELS) as TmdbSourceType[]).map((type) => (
                  <option key={type} value={type}>{TMDB_SOURCE_LABELS[type]}</option>
                ))}
              </select>
            </Field>
            {tmdbType !== "COLLECTION" && tmdbType !== "NETWORK" && mediaPicker}
          </div>
          {tmdbType === "DISCOVER" ? (
            <>
              <fieldset>
                <legend className={LABEL} style={{ color: "var(--text-mute)" }}>Genres</legend>
                <div className="flex flex-wrap gap-1.5">
                  {TMDB_GENRES[mediaType].map((genre) => {
                    const on = genres.includes(genre.id);
                    return (
                      <button
                        key={genre.id}
                        type="button"
                        aria-pressed={on}
                        onClick={() => setGenres((g) => (on ? g.filter((id) => id !== genre.id) : [...g, genre.id]))}
                        className={`rounded-full border px-2.5 py-0.5 text-xs ${on ? "bg-claw-500/15 text-claw-text" : ""}`}
                        style={{ borderColor: "var(--border)", ...(on ? {} : { color: "var(--text-dim)" }) }}
                      >
                        {genre.name}
                      </button>
                    );
                  })}
                </div>
              </fieldset>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field label="Year">
                  <input value={year} onChange={(e) => setYear(e.target.value.replace(/\D/g, "").slice(0, 4))} inputMode="numeric" placeholder="Any" className={INPUT} style={INPUT_STYLE} />
                </Field>
                <Field label="Rated at least">
                  <select value={minRating} onChange={(e) => setMinRating(e.target.value)} className={INPUT} style={INPUT_STYLE}>
                    <option value="">Any rating</option>
                    {[5, 6, 7, 8].map((r) => <option key={r} value={r}>{r}+</option>)}
                  </select>
                </Field>
                <Field label="Sort">
                  <select value={sortBy} onChange={(e) => setSortBy(e.target.value)} className={INPUT} style={INPUT_STYLE}>
                    <option value="popularity.desc">Most popular</option>
                    <option value="vote_average.desc">Highest rated</option>
                    <option value="vote_count.desc">Most voted</option>
                    <option value={mediaType === "TV" ? "first_air_date.desc" : "primary_release_date.desc"}>Newest</option>
                  </select>
                </Field>
              </div>
            </>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="TMDB id">
                <input value={tmdbId} onChange={(e) => setTmdbId(e.target.value.replace(/\D/g, ""))} inputMode="numeric" placeholder="e.g. 10" className={INPUT} style={INPUT_STYLE} />
              </Field>
              <Field label="Row title">
                <input value={tmdbTitle} onChange={(e) => setTmdbTitle(e.target.value)} placeholder="Shown on the TV" className={INPUT} style={INPUT_STYLE} />
              </Field>
            </div>
          )}
        </div>
      )}

      {provider === "trakt" && (
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Trakt list id">
            <input value={traktId} onChange={(e) => setTraktId(e.target.value.replace(/\D/g, ""))} inputMode="numeric" className={INPUT} style={INPUT_STYLE} />
          </Field>
          <Field label="Row title">
            <input value={traktTitle} onChange={(e) => setTraktTitle(e.target.value)} className={INPUT} style={INPUT_STYLE} />
          </Field>
          {mediaPicker}
        </div>
      )}

      {provider === "addon" && (
        <div className="space-y-3">
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>
            The addon has to be installed on the TV too; the row is matched to it by the addon&apos;s id.
          </p>
          <div className="flex gap-2">
            <input
              value={manifestUrl}
              onChange={(e) => setManifestUrl(e.target.value)}
              placeholder="https://…/manifest.json"
              aria-label="Addon manifest URL"
              className={INPUT}
              style={INPUT_STYLE}
            />
            <button type="button" onClick={loadManifest} disabled={!manifestUrl.trim() || loadingManifest} className="btn-secondary btn-sm">
              {loadingManifest ? <Loader2 size={14} className="animate-spin" /> : null} Load
            </button>
          </div>
          {manifestError && <p role="alert" className="text-xs text-danger">{manifestError}</p>}
          {manifest && (
            <Field label={`Catalog from ${manifest.name}`}>
              <select value={catalogKey} onChange={(e) => setCatalogKey(e.target.value)} className={INPUT} style={INPUT_STYLE}>
                {manifest.catalogs.map((c) => (
                  <option key={`${c.type}/${c.id}`} value={`${c.type}/${c.id}`}>{c.name} ({c.type})</option>
                ))}
              </select>
            </Field>
          )}
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button type="button" disabled={!source} onClick={() => source && onAdd(source)} className="btn-primary btn-sm">
          <Plus size={14} /> Add source
        </button>
        <button type="button" disabled={!source} onClick={() => source && onPreview(source)} className="btn-secondary btn-sm">
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
  onChange,
  onMove,
  onRemove,
  onPreview,
}: {
  folder: CollectionFolder;
  index: number;
  count: number;
  lists: CatalogList[];
  onChange: (folder: CollectionFolder) => void;
  onMove: (delta: number) => void;
  onRemove: () => void;
  onPreview: (source: CollectionSource) => void;
}) {
  const [adding, setAdding] = useState(false);
  const sources = folder.sources ?? [];

  return (
    <li className="rounded-xl border p-3" style={{ borderColor: "var(--border)" }}>
      <div className="flex flex-wrap items-end gap-2">
        <div className="w-14">
          <Field label="Icon">
            <input
              value={folder.coverEmoji ?? ""}
              onChange={(e) => onChange({ ...folder, coverEmoji: e.target.value.slice(0, 4) || null })}
              aria-label={`Emoji for ${folder.title}`}
              className={`${INPUT} text-center`}
              style={INPUT_STYLE}
            />
          </Field>
        </div>
        <div className="min-w-[10rem] flex-1">
          <Field label="Folder">
            <input
              value={folder.title}
              onChange={(e) => onChange({ ...folder, title: e.target.value })}
              aria-label="Folder title"
              className={INPUT}
              style={INPUT_STYLE}
            />
          </Field>
        </div>
        <div className="w-32">
          <Field label="Tiles">
            <select
              value={folder.tileShape ?? "SQUARE"}
              onChange={(e) => onChange({ ...folder, tileShape: e.target.value as CollectionFolder["tileShape"] })}
              aria-label="Tile shape"
              className={INPUT}
              style={INPUT_STYLE}
            >
              <option value="POSTER">Poster</option>
              <option value="LANDSCAPE">Landscape</option>
              <option value="SQUARE">Square</option>
            </select>
          </Field>
        </div>
        <div className="flex gap-1 pb-0.5">
          <button type="button" onClick={() => onMove(-1)} disabled={index === 0} aria-label={`Move ${folder.title} up`} className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)] disabled:opacity-30" style={{ color: "var(--text-mute)" }}>
            <ArrowUp size={14} />
          </button>
          <button type="button" onClick={() => onMove(1)} disabled={index === count - 1} aria-label={`Move ${folder.title} down`} className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)] disabled:opacity-30" style={{ color: "var(--text-mute)" }}>
            <ArrowDown size={14} />
          </button>
          <button type="button" onClick={onRemove} aria-label={`Remove folder ${folder.title}`} className="rounded-lg p-1.5 hover:bg-rose-500/10 hover:text-danger" style={{ color: "var(--text-mute)" }}>
            <Trash2 size={14} />
          </button>
        </div>
      </div>

      <ul className="mt-3 space-y-1.5" aria-label={`Sources in ${folder.title}`}>
        {sources.length === 0 && (
          <li className="text-xs" style={{ color: "var(--text-mute)" }}>No sources yet — this folder will be empty on the TV.</li>
        )}
        {sources.map((source, sourceIndex) => (
          <li key={sourceIndex} className="flex items-center gap-2 rounded-lg px-2 py-1" style={{ background: "var(--surface)" }}>
            <span className="min-w-0 flex-1 truncate text-sm" style={{ color: "var(--text)" }}>{describeSource(source)}</span>
            <button type="button" onClick={() => onPreview(source)} aria-label={`Preview ${describeSource(source)}`} className="rounded-lg p-1 hover:bg-[var(--surface-strong)]" style={{ color: "var(--text-mute)" }}>
              <Eye size={14} />
            </button>
            <button
              type="button"
              onClick={() => onChange({ ...folder, sources: sources.filter((_, i) => i !== sourceIndex) })}
              aria-label={`Remove ${describeSource(source)}`}
              className="rounded-lg p-1 hover:bg-rose-500/10 hover:text-danger"
              style={{ color: "var(--text-mute)" }}
            >
              <X size={14} />
            </button>
          </li>
        ))}
      </ul>

      {adding ? (
        <SourceBuilder
          lists={lists}
          onPreview={onPreview}
          onCancel={() => setAdding(false)}
          onAdd={(source) => {
            onChange({ ...folder, sources: [...sources, source] });
            setAdding(false);
          }}
        />
      ) : (
        <button type="button" onClick={() => setAdding(true)} className="btn-secondary btn-sm mt-2">
          <Plus size={14} /> Add source
        </button>
      )}
    </li>
  );
}

// ─── A collection ───

function CollectionEditor({
  collection,
  lists,
  onChange,
  onRemove,
  onPreview,
}: {
  collection: Collection;
  lists: CatalogList[];
  onChange: (collection: Collection) => void;
  onRemove: () => void;
  onPreview: (source: CollectionSource) => void;
}) {
  const setFolders = (folders: CollectionFolder[]) => onChange({ ...collection, folders });

  return (
    <section className={`${CARD} space-y-4`} style={CARD_STYLE} aria-label={`Editing ${collection.title}`}>
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[12rem] flex-1">
          <Field label="Collection">
            <input value={collection.title} onChange={(e) => onChange({ ...collection, title: e.target.value })} aria-label="Collection title" className={INPUT} style={INPUT_STYLE} />
          </Field>
        </div>
        <div className="w-56">
          <Field label="On the TV">
            <select
              value={collection.viewMode ?? "TABBED_GRID"}
              onChange={(e) => onChange({ ...collection, viewMode: e.target.value as CollectionViewMode })}
              aria-label="Layout on the TV"
              className={INPUT}
              style={INPUT_STYLE}
            >
              {(Object.keys(VIEW_MODE_LABELS) as CollectionViewMode[]).map((mode) => (
                <option key={mode} value={mode}>{VIEW_MODE_LABELS[mode]}</option>
              ))}
            </select>
          </Field>
        </div>
        <button type="button" onClick={onRemove} className="btn-secondary btn-sm">
          <Trash2 size={14} /> Delete collection
        </button>
      </div>
      <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm" style={{ color: "var(--text)" }}>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={collection.pinToTop ?? false} onChange={(e) => onChange({ ...collection, pinToTop: e.target.checked })} />
          Pin to the top of Home
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={collection.showAllTab ?? true} onChange={(e) => onChange({ ...collection, showAllTab: e.target.checked })} />
          Show an &ldquo;All&rdquo; tab
        </label>
      </div>

      <ol className="space-y-3">
        {collection.folders.map((folder, index) => (
          <FolderEditor
            key={folder.id}
            folder={folder}
            index={index}
            count={collection.folders.length}
            lists={lists}
            onPreview={onPreview}
            onChange={(next) => setFolders(collection.folders.map((f) => (f.id === folder.id ? next : f)))}
            onMove={(delta) => setFolders(move(collection.folders, index, delta))}
            onRemove={() => setFolders(collection.folders.filter((f) => f.id !== folder.id))}
          />
        ))}
      </ol>
      <button type="button" onClick={() => setFolders([...collection.folders, newFolder()])} className="btn-secondary btn-sm">
        <FolderPlus size={14} /> Add folder
      </button>
    </section>
  );
}

// ─── The page ───

/**
 * Collections: custom Home rows on the TV. Each collection is a set of
 * folders, and each folder is filled from one or more sources — addon
 * catalogs, TMDB, Trakt, or a list made here. Saved as one document per
 * profile, the way the TV saves them, and picked up by paired TVs on sync.
 */
export function CollectionsPage() {
  const [saved, setSaved] = useState<Collection[] | null>(null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [draft, setDraft] = useState<Collection[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [lists, setLists] = useState<CatalogList[]>([]);
  const [preview, setPreview] = useState<CollectionSource | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saving, setSaving] = useState(false);
  const { showToast } = useToast();

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [doc, { lists: fetched }] = await Promise.all([api.getCollections(), api.getLists()]);
      setSaved(doc.collections);
      setDraft(doc.collections);
      setUpdatedAt(doc.updatedAt);
      setSelectedId((current) => current ?? doc.collections[0]?.id ?? null);
      setLists(fetched.filter((list) => list.kind === "custom"));
      setConflict(false);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Could not load collections.");
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const dirty = saved !== null && JSON.stringify(saved) !== JSON.stringify(draft);
  const selected = draft.find((c) => c.id === selectedId) ?? null;

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const doc = await api.saveCollections(draft, updatedAt);
      setSaved(doc.collections);
      setDraft(doc.collections);
      setUpdatedAt(doc.updatedAt);
      showToast("Collections saved");
    } catch (err) {
      if (err instanceof ApiError && err.code === "collections_conflict") setConflict(true);
      else setSaveError(err instanceof Error ? err.message : "Could not save collections.");
    } finally {
      setSaving(false);
    }
  };

  const addCollection = () => {
    const created = newCollection();
    setDraft((d) => [...d, created]);
    setSelectedId(created.id);
  };

  return (
    <div className="space-y-6 py-2">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Collections</h1>
          <p className="mt-1 max-w-2xl text-sm" style={{ color: "var(--text-dim)" }}>
            Custom rows for the TV&apos;s Home screen. Group sources into folders, preview what each one brings, and
            save — paired TVs pick the change up on their next sync.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {dirty && <span className="text-xs" style={{ color: "var(--text-mute)" }}>Unsaved changes</span>}
          <button type="button" onClick={() => saved && setDraft(saved)} disabled={!dirty || saving} className="btn-secondary btn-sm">
            <RotateCcw size={14} /> Discard
          </button>
          <button type="button" onClick={save} disabled={!dirty || saving} className="btn-primary btn-sm">
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} Save
          </button>
        </div>
      </header>

      {conflict && (
        <div role="alert" className="flex flex-wrap items-center gap-3 rounded-xl border px-3 py-2 text-sm" style={{ borderColor: "var(--border-strong)", color: "var(--text)" }}>
          <AlertCircle size={16} className="text-danger" />
          These collections were changed on a TV since you opened this page. Reload to start from the latest — your
          edits here will be lost.
          <button type="button" onClick={() => void load()} className="btn-secondary btn-sm">Reload</button>
        </div>
      )}
      {saveError && <p role="alert" className="flex items-center gap-1.5 text-sm text-danger"><AlertCircle size={14} /> {saveError}</p>}

      {loadError ? (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {loadError}</p>
      ) : saved === null ? (
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading collections…
        </p>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[16rem_minmax(0,1fr)]">
          <nav aria-label="Collections" className="space-y-1">
            {draft.map((collection) => (
              <button
                key={collection.id}
                type="button"
                onClick={() => setSelectedId(collection.id)}
                aria-current={collection.id === selectedId ? "true" : undefined}
                className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm ${collection.id === selectedId ? "bg-claw-500/10 text-claw-text" : "hover:bg-[var(--surface-strong)]"}`}
                style={collection.id === selectedId ? undefined : { color: "var(--text)" }}
              >
                <LayoutGrid size={14} className="flex-none" />
                <span className="min-w-0 flex-1 truncate">{collection.title || "Untitled"}</span>
                <span className="text-2xs" style={{ color: "var(--text-mute)" }}>{collection.folders.length}</span>
              </button>
            ))}
            <button type="button" onClick={addCollection} className="btn-secondary btn-sm mt-2 w-full">
              <Plus size={14} /> New collection
            </button>
          </nav>

          <div className="space-y-4">
            {selected ? (
              <CollectionEditor
                collection={selected}
                lists={lists}
                onPreview={setPreview}
                onChange={(next) => setDraft((d) => d.map((c) => (c.id === next.id ? next : c)))}
                onRemove={() => {
                  setDraft((d) => d.filter((c) => c.id !== selected.id));
                  setSelectedId(draft.find((c) => c.id !== selected.id)?.id ?? null);
                }}
              />
            ) : (
              <div className={CARD} style={CARD_STYLE}>
                <h2 className={SECTION_TITLE} style={{ color: "var(--text)" }}>No collections yet</h2>
                <p className="mt-1 text-sm" style={{ color: "var(--text-dim)" }}>
                  Start one, add a folder, and fill it from a list you made here, a TMDB filter, a Trakt list or an
                  addon catalog.
                </p>
                <button type="button" onClick={addCollection} className="btn-primary btn-sm mt-3">
                  <Plus size={14} /> New collection
                </button>
              </div>
            )}
            {preview && <SourcePreview key={JSON.stringify(preview)} source={preview} onClose={() => setPreview(null)} />}
          </div>
        </div>
      )}
    </div>
  );
}
