import { type FormEvent, useEffect, useState } from "react";
import { AlertCircle, ExternalLink, Loader2, Plus, Puzzle, RefreshCw, Settings2, Trash2 } from "lucide-react";
import { api, runtimeConfig, type TvAddon } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { StatusBadge } from "../settings/StatusBadge";
import {
  ReorderControls,
  errorMessage,
  inputClass,
  inputStyle,
  labelClass,
  labelStyle,
  moveItem,
  rowClass,
  rowStyle,
  useDragReorder,
} from "./reorder";

/** The host an addon lives on. The full URL can hold the addon's credentials, so it isn't shown. */
const hostOf = (manifestUrl: string): string => {
  try {
    return new URL(manifestUrl).host;
  } catch {
    return manifestUrl;
  }
};

/** This server's own Stremio addon, for the profile being edited. */
const ownAddonUrl = (profileId: string) => `${runtimeConfig.getAddonBase()}/p/${profileId}/manifest.json`;

/**
 * The browser's own read of a manifest, sent along as the fallback for an addon
 * the API server can't reach (this server's addon on a public address it can't
 * resolve from inside its container). Null when the browser can't read it
 * either; the server's answer then stands.
 */
async function fetchManifestInBrowser(manifestUrl: string): Promise<unknown> {
  try {
    const response = await fetch(manifestUrl, { headers: { Accept: "application/json" } });
    return response.ok ? ((await response.json()) as unknown) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The addons a profile's TV runs, in the order it lists them. Each change is
 * saved as it is made; the TV picks the list up on its next sync.
 */
export function TvAddonsSettings() {
  const { profile } = useProfile();
  const profileId = profile?.id ?? null;

  const [addons, setAddons] = useState<TvAddon[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [manifestUrl, setManifestUrl] = useState("");
  const [adding, setAdding] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);

  // Inline rather than a shared `load()`, and setting state only once the
  // request settles, so mounting starts the request without an extra render;
  // `loading` starts true for the same reason.
  useEffect(() => {
    if (!profileId) return;
    void (async () => {
      try {
        const { addons: loaded } = await api.getTvAddons(profileId);
        setAddons(loaded);
        setLoadError(null);
      } catch (err) {
        setLoadError(errorMessage(err, "Couldn't load this profile's addons."));
      } finally {
        setLoading(false);
      }
    })();
  }, [profileId]);

  const install = async (url: string, withBrowserFallback: boolean) => {
    if (!profileId) return;
    setAdding(true);
    setAddError(null);
    try {
      const fallback = withBrowserFallback ? await fetchManifestInBrowser(url) : undefined;
      const { addon } = await api.addTvAddon(profileId, url, fallback);
      setAddons((current) => [...current, addon]);
      setManifestUrl("");
    } catch (err) {
      setAddError(errorMessage(err, "Couldn't add that addon."));
    } finally {
      setAdding(false);
    }
  };

  const add = (event: FormEvent) => {
    event.preventDefault();
    if (manifestUrl.trim()) void install(manifestUrl.trim(), false);
  };

  const run = async (addon: TvAddon, action: () => Promise<void>, fallback: string) => {
    setBusyId(addon.id);
    setActionError(null);
    try {
      await action();
    } catch (err) {
      setActionError(errorMessage(err, fallback));
    } finally {
      setBusyId(null);
    }
  };

  const toggle = (addon: TvAddon) =>
    run(
      addon,
      async () => {
        const { addon: updated } = await api.updateTvAddon(profileId!, addon.id, { enabled: !addon.enabled });
        setAddons((current) => current.map((a) => (a.id === updated.id ? updated : a)));
      },
      `Couldn't ${addon.enabled ? "turn off" : "turn on"} ${addon.name}.`
    );

  const refresh = (addon: TvAddon) =>
    run(
      addon,
      async () => {
        const { addon: updated } = await api.refreshTvAddon(profileId!, addon.id);
        setAddons((current) => current.map((a) => (a.id === updated.id ? updated : a)));
      },
      `Couldn't reload ${addon.name}.`
    );

  const remove = (addon: TvAddon) =>
    run(
      addon,
      async () => {
        await api.removeTvAddon(profileId!, addon.id);
        setAddons((current) => current.filter((a) => a.id !== addon.id));
      },
      `Couldn't remove ${addon.name}.`
    );

  const move = (from: number, to: number) => {
    if (!profileId) return;
    const previous = addons;
    const next = moveItem(addons, from, to);
    if (next.every((addon, index) => addon.id === previous[index]?.id)) return;
    // Shown at once and put back if the save fails, so dragging feels direct.
    setAddons(next);
    setActionError(null);
    api.reorderTvAddons(profileId, next.map((addon) => addon.id)).then(
      ({ addons: saved }) => setAddons(saved),
      (err: unknown) => {
        setAddons(previous);
        setActionError(errorMessage(err, "Couldn't save the new order."));
      }
    );
  };

  const { rowProps, dragOverIndex } = useDragReorder(move);

  if (!profileId) {
    return (
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Choose a profile to manage its addons.
      </p>
    );
  }

  const ownUrl = ownAddonUrl(profileId);
  const ownInstalled = addons.some((addon) => addon.manifestUrl === ownUrl);

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        The Stremio addons {profile?.name ? `${profile.name}'s` : "this profile's"} TV uses, top to bottom in the
        order the TV lists them. Changes reach the TV the next time it syncs.
      </p>

      {loading ? (
        <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading addons...
        </div>
      ) : loadError ? (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {loadError}
        </p>
      ) : addons.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          No addons yet. Add one by its manifest address below.
        </p>
      ) : (
        <ul className="space-y-2" aria-label="Installed addons">
          {addons.map((addon, index) => (
            <li
              key={addon.id}
              {...rowProps(index)}
              className={rowClass}
              style={{
                ...rowStyle,
                ...(dragOverIndex === index ? { borderColor: "var(--accent)" } : {}),
                ...(addon.enabled ? {} : { opacity: 0.6 }),
              }}
            >
              <div className="flex flex-wrap items-center gap-2">
                <ReorderControls
                  index={index}
                  count={addons.length}
                  label={addon.name}
                  disabled={busyId !== null}
                  onMove={move}
                />
                {addon.logo ? (
                  <img src={addon.logo} alt="" className="h-8 w-8 flex-none rounded-lg object-contain" loading="lazy" />
                ) : (
                  <Puzzle size={20} className="flex-none" style={{ color: "var(--text-mute)" }} aria-hidden="true" />
                )}
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium" style={{ color: "var(--text)" }}>
                    {addon.name}
                    {addon.version && (
                      <span className="ml-2 font-mono text-2xs" style={{ color: "var(--text-mute)" }}>
                        v{addon.version}
                      </span>
                    )}
                  </span>
                  <span className="block truncate text-xs" style={{ color: "var(--text-mute)" }}>
                    {hostOf(addon.manifestUrl)}
                    {addon.resources.length > 0 && ` · ${addon.resources.join(", ")}`}
                    {addon.catalogs.length > 0 &&
                      ` · ${addon.catalogs.length} catalog${addon.catalogs.length === 1 ? "" : "s"}`}
                  </span>
                </span>
                {!addon.reachable && <StatusBadge ok={false} label="Couldn't read manifest" />}
                <span className="ml-auto flex items-center gap-1">
                  <label className="flex items-center gap-2 px-1 text-xs" style={{ color: "var(--text-dim)" }}>
                    <input
                      type="checkbox"
                      role="switch"
                      className="switch-control"
                      checked={addon.enabled}
                      disabled={busyId === addon.id}
                      onChange={() => void toggle(addon)}
                      aria-label={`${addon.name} enabled`}
                    />
                  </label>
                  {addon.configureUrl && (
                    <a
                      href={addon.configureUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)]"
                      style={{ color: "var(--text-dim)" }}
                      title="Configure (opens the addon's own page)"
                      aria-label={`Configure ${addon.name}`}
                    >
                      <Settings2 size={16} />
                    </a>
                  )}
                  <button
                    type="button"
                    onClick={() => void refresh(addon)}
                    disabled={busyId === addon.id}
                    className="rounded-lg p-1.5 hover:bg-[var(--surface-strong)] disabled:opacity-50"
                    style={{ color: "var(--text-dim)" }}
                    title="Reload the manifest"
                    aria-label={`Reload ${addon.name}'s manifest`}
                  >
                    {busyId === addon.id ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />}
                  </button>
                  <button
                    type="button"
                    onClick={() => void remove(addon)}
                    disabled={busyId === addon.id}
                    className="rounded-lg p-1.5 text-danger hover:bg-[var(--surface-strong)] disabled:opacity-50"
                    title="Remove addon"
                    aria-label={`Remove ${addon.name}`}
                  >
                    <Trash2 size={16} />
                  </button>
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}

      {actionError && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {actionError}
        </p>
      )}

      <form onSubmit={add} className="space-y-3 border-t pt-4" style={{ borderColor: "var(--border)" }}>
        <div>
          <label htmlFor="tv-addon-url" className={labelClass} style={labelStyle}>
            Manifest address
          </label>
          <input
            id="tv-addon-url"
            type="url"
            inputMode="url"
            autoComplete="off"
            spellCheck={false}
            value={manifestUrl}
            onChange={(e) => {
              setManifestUrl(e.target.value);
              setAddError(null);
            }}
            placeholder="https://addon.example/manifest.json"
            className={inputClass}
            style={inputStyle}
          />
          <p className="mt-1 text-xs" style={{ color: "var(--text-mute)" }}>
            The address an addon's Install button gives you, ending in /manifest.json. stremio:// links work too.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="submit" disabled={adding || !manifestUrl.trim()} className="btn-primary disabled:opacity-50">
            {adding ? <Loader2 size={16} className="animate-spin" /> : <Plus size={16} />} Add addon
          </button>
          {!loading && !loadError && !ownInstalled && (
            <button
              type="button"
              disabled={adding}
              onClick={() => void install(ownUrl, true)}
              className="btn-secondary disabled:opacity-50"
            >
              <ExternalLink size={16} /> Add this server's addon
            </button>
          )}
        </div>
        {addError && (
          <p className="flex items-center gap-2 text-sm text-danger" role="alert">
            <AlertCircle size={16} /> {addError}
          </p>
        )}
      </form>
    </div>
  );
}
