import { type FormEvent, useEffect, useState } from "react";
import { AlertCircle, Check, Eye, EyeOff, Loader2, RotateCcw, Unplug } from "lucide-react";
import {
  MDBLIST_RATING_LABELS,
  readMdbListRatingSettings,
  withMdbListRatingSettings,
  type MdbListRatingSettings,
  type TvSettingsDocument,
} from "@fetch/shared/tv-settings";
import { api, type MdbListKeyStatus } from "../../api";
import { useProfile } from "../../hooks/useProfile";
import { useTransientFlag } from "../../hooks/useTransientFlag";
import {
  ReorderControls,
  errorMessage,
  isVersionConflict,
  moveItem,
  rowClass,
  rowStyle,
  useDragReorder,
} from "../tv/reorder";
import { StatusBadge } from "./StatusBadge";

const switchRow =
  "flex items-center justify-between gap-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 py-3 cursor-pointer";

/** The profile's MDBList API key, checked with MDBList before it is kept. */
function ApiKeyForm() {
  const [status, setStatus] = useState<MdbListKeyStatus | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useTransientFlag();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void (async () => {
      try {
        const next = await api.getMdbListKeyStatus();
        if (current) setStatus(next);
      } catch (err) {
        if (current) setError(errorMessage(err, "Couldn't load the MDBList key"));
      }
    })();
    return () => {
      current = false;
    };
  }, []);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      setStatus(await api.setMdbListKey(apiKey.trim()));
      setApiKey("");
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err, "Couldn't save the MDBList key"));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    setError(null);
    try {
      setStatus(await api.removeMdbListKey());
    } catch (err) {
      setError(errorMessage(err, "Couldn't remove the MDBList key"));
    }
  };

  if (!status && !error) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Checking the MDBList key...
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {status && (
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge
            ok={status.hasKey || status.signedIn}
            label={status.hasKey ? "API key saved" : status.signedIn ? "Using your MDBList sign-in" : "No API key"}
          />
        </div>
      )}

      {status?.hasKey ? (
        <button type="button" onClick={() => void remove()} className="btn-secondary hover:bg-rose-600 hover:text-white">
          <Unplug size={16} /> Remove key
        </button>
      ) : (
        <form onSubmit={(e) => void save(e)} className="space-y-3">
          <div className="relative">
            <input
              type={showKey ? "text" : "password"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="Paste your MDBList API key"
              aria-label="MDBList API key"
              autoComplete="off"
              className="w-full rounded-xl border px-4 py-3 pr-20 text-sm focus:border-claw-500 focus:outline-none focus:ring-2 focus:ring-claw-500/15"
              style={{ borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-1)" }}
            />
            <button
              type="button"
              onClick={() => setShowKey((shown) => !shown)}
              className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1.5 transition-colors hover:bg-[var(--surface)] hover:text-[var(--text-dim)]"
              style={{ color: "var(--text-mute)" }}
              aria-label={showKey ? "Hide key" : "Show key"}
            >
              {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
          <button type="submit" disabled={saving || !apiKey.trim()} className={`btn-primary ${saved ? "btn-saved" : ""}`}>
            {saved ? (
              <>
                <Check size={16} /> Saved
              </>
            ) : saving ? (
              <>
                <Loader2 size={16} className="animate-spin" /> Checking...
              </>
            ) : (
              "Save API key"
            )}
          </button>
        </form>
      )}

      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}
    </div>
  );
}

/**
 * Which ratings show and in what order. These are the TV's own MDBList
 * settings, stored in the profile's TV settings document, so a change here
 * reaches the TV on its next sync and the TV's choices show up here.
 */
function DisplayForm({ profileId }: { profileId: string }) {
  const [document, setDocument] = useState<TvSettingsDocument>({});
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const result = await api.getTvSettings(profileId);
        if (cancelled) return;
        setDocument(result.settings);
        setVersion(result.version);
        setDirty(false);
        setLoadError(null);
      } catch (err) {
        if (!cancelled) setLoadError(errorMessage(err, "Couldn't load the rating settings."));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [profileId, reloadToken]);

  const settings = readMdbListRatingSettings(document);

  const change = (next: MdbListRatingSettings) => {
    setDocument(withMdbListRatingSettings(document, next));
    setDirty(true);
    setSaved(false);
  };

  const move = (from: number, to: number) => change({ ...settings, order: moveItem(settings.order, from, to) });
  const { rowProps, dragOverIndex } = useDragReorder(move);

  const reload = () => {
    setLoading(true);
    setConflict(false);
    setSaveError(null);
    setReloadToken((n) => n + 1);
  };

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    setConflict(false);
    try {
      const result = await api.saveTvSettings(profileId, document, version);
      setDocument(result.settings);
      setVersion(result.version);
      setDirty(false);
      setSaved(true);
    } catch (err) {
      if (isVersionConflict(err)) setConflict(true);
      else setSaveError(errorMessage(err, "Couldn't save the rating settings."));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading rating settings...
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

  return (
    <div className="space-y-4">
      <label className={switchRow}>
        <span className="text-sm" style={{ color: "var(--text)" }}>
          Show MDBList ratings on detail pages
        </span>
        <input
          type="checkbox"
          role="switch"
          className="switch-control"
          checked={settings.enabled}
          onChange={(e) => change({ ...settings, enabled: e.target.checked })}
        />
      </label>

      <fieldset className="space-y-2" disabled={!settings.enabled}>
        <legend className="mb-2 text-xs font-medium" style={{ color: "var(--text-dim)" }}>
          Ratings to show, in order
        </legend>
        <ol className="space-y-2">
          {settings.order.map((source, index) => {
            const label = MDBLIST_RATING_LABELS[source];
            return (
              <li
                key={source}
                {...rowProps(index)}
                className={`${rowClass} flex items-center gap-3 ${dragOverIndex === index ? "ring-2 ring-claw-500/40" : ""}`}
                style={{ ...rowStyle, opacity: settings.enabled ? 1 : 0.6 }}
              >
                <ReorderControls
                  index={index}
                  count={settings.order.length}
                  label={label}
                  disabled={!settings.enabled}
                  onMove={move}
                />
                <label className="flex flex-1 cursor-pointer items-center justify-between gap-3">
                  <span className="text-sm" style={{ color: "var(--text)" }}>
                    {label}
                  </span>
                  <input
                    type="checkbox"
                    role="switch"
                    className="switch-control"
                    checked={settings.shown[source]}
                    onChange={(e) => change({ ...settings, shown: { ...settings.shown, [source]: e.target.checked } })}
                  />
                </label>
              </li>
            );
          })}
        </ol>
      </fieldset>

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => void save()} disabled={saving || !dirty} className="btn-primary disabled:opacity-50">
          {saving ? <Loader2 size={16} className="animate-spin" /> : <Check size={16} />} Save rating settings
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
          <AlertCircle size={16} /> These settings were changed on another device since you opened them.
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

export function MdbListRatingsSettings() {
  const { profile } = useProfile();
  const profileId = profile?.id ?? null;

  if (!profileId) {
    return (
      <p className="text-sm" style={{ color: "var(--text-dim)" }}>
        Choose a profile to set up its MDBList ratings.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
        Ratings from IMDb, Trakt, TMDB, Rotten Tomatoes, Letterboxd, Metacritic and MyAnimeList on this profile&apos;s
        detail pages, read through MDBList. Paste an API key from{" "}
        <a
          href="https://mdblist.com/preferences/"
          target="_blank"
          rel="noopener noreferrer"
          className="text-claw-text underline underline-offset-2 hover:decoration-2"
        >
          mdblist.com
        </a>
        , or sign in to MDBList under Where history comes from and that sign-in is used instead.
      </p>
      {/* Keyed by profile: each holds state that belongs to the profile it loaded for. */}
      <ApiKeyForm key={`key-${profileId}`} />
      <div className="border-t pt-6" style={{ borderColor: "var(--border)" }}>
        <DisplayForm key={`display-${profileId}`} profileId={profileId} />
      </div>
    </div>
  );
}
