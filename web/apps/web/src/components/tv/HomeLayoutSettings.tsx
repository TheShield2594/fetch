import { useEffect, useState } from "react";
import { AlertCircle, Check, Loader2, RotateCcw } from "lucide-react";
import { api, type HomeRow } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { StatusBadge } from "../settings/StatusBadge";
import { ReorderControls, errorMessage, isVersionConflict, moveItem, rowClass, rowStyle, useDragReorder } from "./reorder";

const TYPE_LABELS: Record<string, string> = { movie: "Movies", series: "Series", anime: "Anime", tv: "TV", channel: "Channels" };

/** The line under a row's title: where it comes from. */
function describeRow(row: HomeRow): string {
  switch (row.source) {
    case "addon":
      return [row.addonName ?? "Addon", row.type ? (TYPE_LABELS[row.type] ?? row.type) : null].filter(Boolean).join(" · ");
    case "list":
      return "Your list on this website";
    case "collection":
      return "Collection";
    case "builtin":
      return "Built into the TV";
    default:
      return "Made on the TV";
  }
}

/**
 * The order of a profile's Home rows on the TV, and which are hidden. Every row
 * its addons and lists provide is listed; ones not arranged yet sit at the end.
 *
 * Unlike the addon list this is saved as a whole, with a Save button: an order
 * is one decision made over several moves, and the TV should get the finished
 * one rather than every step towards it.
 */
export function HomeLayoutSettings() {
  const { profile } = useProfile();
  const profileId = profile?.id ?? null;

  const [rows, setRows] = useState<HomeRow[]>([]);
  const [version, setVersion] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState(false);

  // Inline rather than a shared `load()`, and setting state only once the
  // request settles, so mounting starts the request without an extra render;
  // `loading` starts true for the same reason.
  const [reloadToken, setReloadToken] = useState(0);
  useEffect(() => {
    if (!profileId) return;
    // A newer load (another profile, or a reload) supersedes this one.
    let cancelled = false;
    void (async () => {
      try {
        const layout = await api.getHomeLayout(profileId);
        if (cancelled) return;
        setRows(layout.rows);
        setVersion(layout.version);
        setDirty(false);
        setLoadError(null);
      } catch (err) {
        if (cancelled) return;
        setLoadError(errorMessage(err, "Couldn't load the Home layout."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [profileId, reloadToken]);

  /** Back to what is saved: after a conflict, or to throw away unsaved changes. */
  const reload = () => {
    setLoading(true);
    setConflict(false);
    setSaveError(null);
    setReloadToken((n) => n + 1);
  };

  const change = (next: HomeRow[]) => {
    setRows(next);
    setDirty(true);
    setSaved(false);
  };

  const move = (from: number, to: number) => change(moveItem(rows, from, to));
  const toggleHidden = (key: string) => change(rows.map((row) => (row.key === key ? { ...row, hidden: !row.hidden } : row)));
  const { rowProps, dragOverIndex } = useDragReorder(move);

  const save = async () => {
    if (!profileId) return;
    setSaving(true);
    setSaveError(null);
    setConflict(false);
    try {
      const layout = await api.saveHomeLayout(
        profileId,
        rows.map((row) => ({ key: row.key, hidden: row.hidden, title: row.title })),
        version
      );
      setRows(layout.rows);
      setVersion(layout.version);
      setDirty(false);
      setSaved(true);
    } catch (err) {
      if (isVersionConflict(err)) setConflict(true);
      else setSaveError(errorMessage(err, "Couldn't save the Home layout."));
    } finally {
      setSaving(false);
    }
  };

  if (!profileId) {
    return (
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Choose a profile to arrange its Home rows.
      </p>
    );
  }

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading Home rows...
      </div>
    );
  }

  if (loadError) {
    return (
      <p role="alert" className="flex items-center gap-2 text-sm text-danger">
        <AlertCircle size={16} /> {loadError}
      </p>
    );
  }

  const visibleCount = rows.filter((row) => !row.hidden).length;

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        Drag rows into the order the TV's Home screen should show them, and switch off the ones you don't want. Rows
        from new addons and lists appear at the bottom until you place them. {visibleCount} of {rows.length} shown.
      </p>

      {rows.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          Nothing to arrange yet. Add an addon with catalogs, or make a list.
        </p>
      ) : (
        <ul className="space-y-2" aria-label="Home rows">
          {rows.map((row, index) => (
            <li
              key={row.key}
              {...rowProps(index)}
              className={rowClass}
              style={{
                ...rowStyle,
                ...(dragOverIndex === index ? { borderColor: "var(--accent)" } : {}),
                ...(row.hidden ? { opacity: 0.6 } : {}),
              }}
            >
              <div className="flex flex-wrap items-center gap-2">
                <ReorderControls index={index} count={rows.length} label={row.title} disabled={saving} onMove={move} />
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium" style={{ color: "var(--text)" }}>
                    {row.title}
                  </span>
                  <span className="block truncate text-xs" style={{ color: "var(--text-mute)" }}>
                    {describeRow(row)}
                  </span>
                </span>
                {!row.available && <StatusBadge ok={false} label="Not reachable from here" />}
                <label className="ml-auto flex items-center gap-2 text-xs" style={{ color: "var(--text-dim)" }}>
                  Show
                  <input
                    type="checkbox"
                    role="switch"
                    className="switch-control"
                    checked={!row.hidden}
                    disabled={saving}
                    onChange={() => toggleHidden(row.key)}
                    aria-label={`Show ${row.title} on Home`}
                  />
                </label>
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => void save()} disabled={saving || !dirty} className="btn-primary disabled:opacity-50">
          {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />} Save layout
        </button>
        {dirty && (
          <button type="button" onClick={reload} disabled={saving} className="btn-secondary disabled:opacity-50">
            <RotateCcw size={16} /> Discard changes
          </button>
        )}
        <span aria-live="polite" className="text-xs text-success">
          {saved && !dirty ? "Saved. The TV picks this up on its next sync." : ""}
        </span>
      </div>

      {conflict && (
        <p role="alert" className="flex flex-wrap items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> This layout was changed on another device since you opened it.
          <button type="button" onClick={reload} className="btn-secondary btn-sm">
            Load the latest
          </button>
        </p>
      )}
      {saveError && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {saveError}
        </p>
      )}
    </div>
  );
}
