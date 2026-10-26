import { prisma } from "./prisma.js";
import { readCachedManifest } from "./addon-manifest.js";
import { getProfileDocument, writeProfileDocument } from "./profile-document.js";

/**
 * The order and visibility of a profile's Home rows on the TV.
 *
 * A row is named by a key that says where it comes from:
 *
 *   - `addon:<addonId>:<type>:<catalogId>` — an addon catalog. `addonId` is the
 *     manifest's own id, the same one the TV's `SyncCatalogItem.addon_id` holds,
 *     and each part is URI-encoded so a `:` inside one can't split it.
 *   - `list:<uuid>` — one of the website's lists (watchlist, collection or a
 *     custom list), so a list made here can sit on Home like any catalog.
 *   - `builtin:<name>` — a row the TV draws itself, such as Continue Watching.
 *
 * Any other prefix is a row this server doesn't know how to resolve (a TV
 * collection, say). Those are kept exactly as sent: the TV owns them.
 *
 * What is stored is only what someone arranged. Reading the layout merges in
 * every row the profile's addons and lists provide that isn't placed yet (at
 * the end, visible) and drops rows whose addon or list is gone, so removing an
 * addon takes its rows with it without disturbing the rest of the order.
 */

export const MAX_HOME_ROWS = 500;
export const MAX_ROW_KEY_LENGTH = 600;

export const BUILTIN_ROWS: readonly { key: string; title: string }[] = [
  { key: "builtin:continue_watching", title: "Continue Watching" },
];

export type StoredRow = { key: string; hidden: boolean; title?: string };

export type HomeRowSource = "addon" | "list" | "builtin" | "other";

export type HomeRow = {
  key: string;
  title: string;
  hidden: boolean;
  source: HomeRowSource;
  /** The addon's name, for addon rows. */
  addonName: string | null;
  /** The catalog's content type (movie, series…), for addon rows. */
  type: string | null;
  /**
   * False for a row this server can't currently resolve: its addon's manifest
   * couldn't be read, or the catalog is no longer in it. Kept, by name.
   */
  available: boolean;
};

const enc = encodeURIComponent;

export const addonRowKey = (addonId: string, type: string, catalogId: string): string =>
  `addon:${enc(addonId)}:${enc(type)}:${enc(catalogId)}`;

export const listRowKey = (listId: string): string => `list:${listId}`;

type ParsedKey =
  | { source: "addon"; addonId: string; type: string; catalogId: string }
  | { source: "list"; listId: string }
  | { source: "builtin"; name: string }
  | { source: "other" };

const decode = (part: string): string | null => {
  try {
    return decodeURIComponent(part);
  } catch {
    return null;
  }
};

export const parseRowKey = (key: string): ParsedKey => {
  const [prefix, ...parts] = key.split(":");
  if (prefix === "addon" && parts.length === 3) {
    const [addonId, type, catalogId] = parts.map(decode);
    if (addonId && type && catalogId) return { source: "addon", addonId, type, catalogId };
  }
  if (prefix === "list" && parts.length === 1 && parts[0]) return { source: "list", listId: parts[0] };
  if (prefix === "builtin" && parts.length === 1 && parts[0]) return { source: "builtin", name: parts[0] };
  return { source: "other" };
};

/** A row key has to name a source, `prefix:rest`, with nothing exotic in it. */
export const isValidRowKey = (key: string): boolean =>
  key.length <= MAX_ROW_KEY_LENGTH && /^[a-z_]+:[^\s]+$/.test(key);

/** Reads a stored document's rows, skipping anything that isn't one. */
export const readStoredRows = (document: unknown): StoredRow[] => {
  const rows = (document as { rows?: unknown } | null)?.rows;
  if (!Array.isArray(rows)) return [];
  return rows.flatMap((entry): StoredRow[] => {
    const row = entry as { key?: unknown; hidden?: unknown; title?: unknown } | null;
    if (typeof row?.key !== "string" || !isValidRowKey(row.key)) return [];
    return [
      {
        key: row.key,
        hidden: row.hidden === true,
        ...(typeof row.title === "string" && row.title.trim() ? { title: row.title.trim().slice(0, 300) } : {}),
      },
    ];
  });
};

type SourceRow = Omit<HomeRow, "hidden">;

/** What a profile's addons and lists can put on Home, plus what decides pruning. */
export type HomeRowContext = {
  sources: SourceRow[];
  installedAddonIds: Set<string>;
  /** An installed addon whose manifest was never read: its rows can't be ruled out. */
  hasUnidentifiedAddon: boolean;
  listIds: Set<string>;
};

export const loadHomeRowContext = async (profileId: string): Promise<HomeRowContext> => {
  const [addons, lists] = await Promise.all([
    prisma.profileAddon.findMany({
      where: { profileId },
      orderBy: [{ position: "asc" }, { createdAt: "asc" }],
      select: { addonId: true, name: true, manifest: true },
    }),
    prisma.list.findMany({
      where: { profileId },
      orderBy: { createdAt: "asc" },
      select: { id: true, name: true, kind: true },
    }),
  ]);

  const sources: SourceRow[] = BUILTIN_ROWS.map((row) => ({
    key: row.key,
    title: row.title,
    source: "builtin",
    addonName: null,
    type: null,
    available: true,
  }));

  const installedAddonIds = new Set<string>();
  let hasUnidentifiedAddon = false;
  for (const addon of addons) {
    if (!addon.addonId) {
      hasUnidentifiedAddon = true;
      continue;
    }
    installedAddonIds.add(addon.addonId);
    for (const catalog of readCachedManifest(addon.manifest)?.catalogs ?? []) {
      sources.push({
        key: addonRowKey(addon.addonId, catalog.type, catalog.id),
        title: catalog.name,
        source: "addon",
        addonName: addon.name,
        type: catalog.type,
        available: true,
      });
    }
  }

  // The watchlist and the collection first, as the rest of the website orders them.
  const kindRank = { watchlist: 0, collection: 1, custom: 2 } as const;
  for (const list of [...lists].sort((a, b) => kindRank[a.kind] - kindRank[b.kind])) {
    sources.push({ key: listRowKey(list.id), title: list.name, source: "list", addonName: null, type: null, available: true });
  }

  return { sources, installedAddonIds, hasUnidentifiedAddon, listIds: new Set(lists.map((list) => list.id)) };
};

/** Whether a stored row's source still exists for this profile. */
const rowStillBelongs = (row: StoredRow, context: HomeRowContext): boolean => {
  const parsed = parseRowKey(row.key);
  switch (parsed.source) {
    case "addon":
      return context.installedAddonIds.has(parsed.addonId) || context.hasUnidentifiedAddon;
    case "list":
      return context.listIds.has(parsed.listId);
    default:
      return true;
  }
};

/** Stored rows minus those whose addon or list was removed, first occurrence of each key only. */
export const pruneRows = (rows: StoredRow[], context: HomeRowContext): StoredRow[] => {
  const seen = new Set<string>();
  return rows.filter((row) => {
    if (seen.has(row.key) || !rowStillBelongs(row, context)) return false;
    seen.add(row.key);
    return true;
  });
};

/** The full ordered layout: what was arranged, then everything not yet placed. */
export const mergeHomeLayout = (stored: StoredRow[], context: HomeRowContext): HomeRow[] => {
  const sourcesByKey = new Map(context.sources.map((row) => [row.key, row]));
  const placed = pruneRows(stored, context).map((row): HomeRow => {
    const source = sourcesByKey.get(row.key);
    if (source) return { ...source, hidden: row.hidden };
    const parsed = parseRowKey(row.key);
    return {
      key: row.key,
      title: row.title ?? (parsed.source === "addon" ? parsed.catalogId : row.key),
      hidden: row.hidden,
      source: parsed.source,
      addonName: null,
      type: parsed.source === "addon" ? parsed.type : null,
      // An `other` row is the TV's to resolve, not missing.
      available: parsed.source === "other",
    };
  });
  const placedKeys = new Set(placed.map((row) => row.key));
  const unplaced = context.sources
    .filter((row) => !placedKeys.has(row.key))
    .map((row): HomeRow => ({ ...row, hidden: false }));
  return [...placed, ...unplaced];
};

/** What gets stored for a layout: key, visibility, and the title so an unreachable row keeps its name. */
export const toStoredRows = (rows: HomeRow[] | StoredRow[]): StoredRow[] =>
  rows.map((row) => ({ key: row.key, hidden: row.hidden, ...(row.title ? { title: row.title } : {}) }));

/**
 * Drops the rows of addons that are no longer installed from the stored
 * layout. Called after an addon is removed, so the TV's next pull doesn't carry
 * them. A concurrent edit wins: pruning is redone on every read anyway.
 */
export const pruneHomeLayoutForAddons = async (profileId: string): Promise<void> => {
  const current = await getProfileDocument(profileId, "home-layout");
  if (!current) return;
  const stored = readStoredRows(current.document);
  const pruned = pruneRows(stored, await loadHomeRowContext(profileId));
  if (pruned.length === stored.length) return;
  await writeProfileDocument(
    profileId,
    "home-layout",
    { rows: pruned },
    current.version
  );
};
