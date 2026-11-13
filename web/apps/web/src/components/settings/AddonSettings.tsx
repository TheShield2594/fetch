import { useCallback, useEffect, useState } from "react";
import { api, ApiError, runtimeConfig } from "../../api";
import { useTransientFlag } from "../../hooks/useTransientFlag";
import type { AddonCatalogOption } from "../../api";
import { Loader2, Check, AlertCircle, Copy, ExternalLink, Sparkles, RefreshCw, KeyRound } from "lucide-react";
import { ConfirmReview } from "../ConfirmReview";
import { KICKER } from "../typography";
import { ownAddonManifestUrl } from "../../utils/ownAddon";

/**
 * "Change this profile's addon URL": a new key for this profile alone, for a URL
 * that got out. The old URL stops working at once, so Stremio stops syncing
 * until the new one is installed, which the confirmation says first.
 */
function RotateAddonUrl({ profileId, profileName, onRotated }: { profileId: string; profileName: string | null; onRotated: (key: string | null) => void }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useTransientFlag(4000);
  // Asked for only when the server wants it: the profile's unlock has lapsed.
  const [needsPin, setNeedsPin] = useState(false);
  const [pin, setPin] = useState("");

  const close = () => {
    setConfirming(false);
    setError(null);
    setNeedsPin(false);
    setPin("");
  };

  const rotate = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await api.rotateAddonUrl(profileId, needsPin ? pin : undefined);
      onRotated(res.stremioProfileKey);
      close();
      setDone(true);
    } catch (err) {
      if (err instanceof ApiError && err.code === "profile_pin_required") {
        setNeedsPin(true);
        setError("Enter this profile's PIN to change its addon URL.");
      } else {
        setError(err instanceof Error ? err.message : "Couldn't change the addon URL");
        setPin("");
      }
    } finally {
      setBusy(false);
    }
  };

  if (confirming) {
    return (
      <ConfirmReview
        label="Change this profile's addon URL?"
        message={
          <>
            Give {profileName ? <strong>{profileName}</strong> : "this profile"} a new addon URL? The current one stops working at
            once: Stremio stops showing this profile&apos;s catalogs and stops marking things watched until you install the new URL
            shown above. Other profiles&apos; URLs aren&apos;t affected.
          </>
        }
        confirmLabel="Change URL"
        icon={<KeyRound size={14} />}
        onConfirm={() => void rotate()}
        onCancel={close}
        busy={busy}
        disabled={needsPin && !pin.trim()}
        error={error}
      >
        {needsPin && (
          <label className="block text-xs font-semibold" style={{ color: "var(--text-dim)" }}>
            Profile PIN
            <input
              type="password"
              inputMode="numeric"
              autoComplete="off"
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              maxLength={8}
              className="mt-1.5 block w-32 rounded-lg border px-2.5 py-1.5 text-sm field-control"
              style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" }}
            />
          </label>
        )}
      </ConfirmReview>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-2 border-t pt-3" style={{ borderColor: "var(--border)" }}>
      <button type="button" onClick={() => setConfirming(true)} className="btn-secondary btn-sm">
        <KeyRound size={14} /> Change this profile&apos;s addon URL
      </button>
      <span role="status" className="text-xs" style={{ color: done ? "var(--text-dim)" : "var(--text-mute)" }}>
        {done ? "New URL ready. Install it in Stremio to keep syncing." : "If the URL got out, replace it here."}
      </span>
    </div>
  );
}

function AddonManifestUrl({
  profileName,
  multiProfile,
  profileKey,
  onRotated,
}: {
  profileName: string | null;
  multiProfile: boolean;
  profileKey: string | null;
  onRotated: (key: string | null) => void;
}) {
  const [copied, setCopied] = useTransientFlag();
  // Longer than a success: a failure the user has to do something about should
  // not disappear at the same speed as a confirmation they can ignore.
  const [copyError, setCopyError] = useTransientFlag(3000);
  // Stremio has no way to say who is watching, so the profile is baked into the
  // installed URL: everything Stremio requests hangs off the manifest URL's
  // base, and the addon reads the profile back out of that path. Without it,
  // every catalog would be the oldest profile's once a second profile exists.
  // The profile's key makes the URL unguessable, so it can't be used to read
  // someone else's profile.
  const activeProfileId = runtimeConfig.getProfileId();
  const manifestUrl = activeProfileId
    ? ownAddonManifestUrl(activeProfileId, profileKey)
    : `${runtimeConfig.getAddonBase()}/manifest.json`;

  const copy = () => {
    navigator.clipboard.writeText(manifestUrl).then(() => {
      setCopied(true);
    }).catch(() => {
      setCopyError(true);
    });
  };

  return (
    <div className="rounded-xl border p-4 space-y-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      <p className="text-sm font-medium" style={{ color: "var(--text-dim)" }}>Manifest URL</p>
      <p className="text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
        Copy this URL and paste it into Stremio under <strong style={{ color: "var(--text-dim)" }}>Add-ons &rarr; Install from URL</strong>.
        {profileName && (
          <> It installs the addon for <strong style={{ color: "var(--text-dim)" }}>{profileName}</strong>—catalogs and anything you mark as watched from Stremio belong to that profile.</>
        )}
      </p>
      <div className="flex items-center gap-2">
        <code className="flex-1 overflow-x-auto rounded-lg border px-3 py-2 text-xs select-all whitespace-nowrap scrollbar-hide" style={{ borderColor: "var(--border)", background: "var(--bg-0)", color: "var(--text-dim)" }}>
          {manifestUrl}
        </code>
        <button
          type="button"
          onClick={copy}
          // A permanent transparent 1px border on the base, so the confirmed
          // (ring) states don't drop the border the idle state carries and
          // shift the box by a pixel. Only border-color changes between states,
          // and it eases with the rest.
          className={`flex-none inline-flex items-center gap-1.5 rounded-lg border border-transparent px-3 py-2 text-xs font-semibold transition-[color,background-color,border-color,box-shadow,opacity] duration-base ${
            copied
              ? "bg-emerald-500/15 text-success ring-1 ring-emerald-500/20"
              : copyError
                ? "bg-rose-500/15 text-danger ring-1 ring-rose-500/20"
                : "hover:bg-[var(--surface-strong)]"
          }`}
          style={!copied && !copyError ? { color: "var(--text-dim)", borderColor: "var(--border)", background: "var(--bg-0)" } : undefined}
          aria-label="Copy manifest URL"
        >
          {copied ? <><Check size={13} /> Copied</> : copyError ? <>Failed</> : <><Copy size={13} /> Copy</>}
        </button>
        <a
          href={manifestUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex-none inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold hover:bg-[var(--surface-strong)] transition-colors"
          style={{ color: "var(--text-dim)", borderColor: "var(--border)", background: "var(--bg-0)" }}
          aria-label="Open manifest URL"
        >
          <ExternalLink size={13} />
        </a>
      </div>
      <p className="text-xs" style={{ color: "var(--text-mute)" }}>
        The URL points to your addon server, and Stremio must be able to reach it. Keep it to yourself: anyone with it can see this profile's catalogs and mark things watched.
        {multiProfile && " To use a different profile in Stremio, switch profiles here and install the URL shown then — each profile has its own."}
      </p>
      {activeProfileId && profileKey && (
        <RotateAddonUrl profileId={activeProfileId} profileName={profileName} onRotated={onRotated} />
      )}
    </div>
  );
}

/**
 * The settings panel for the local Stremio addon: shows the manifest URL with a
 * copy button and per-profile install guidance, and loads and saves the addon
 * configuration.
 */
export function AddonSettings() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useTransientFlag();
  const [error, setError] = useState<string | null>(null);
  // Separate from `error` (which save failures also use): while the config
  // hasn't loaded, `enabled` is just the empty initial state, and saving it
  // would wipe every catalog and list from Stremio.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [enabled, setEnabled] = useState<string[]>([]);
  const [available, setAvailable] = useState<AddonCatalogOption[]>([]);
  const [availableLists, setAvailableLists] = useState<{ id: string; name: string }[]>([]);
  const [aiConfigured, setAiConfigured] = useState(false);
  const [profileName, setProfileName] = useState<string | null>(null);
  const [multiProfile, setMultiProfile] = useState(false);
  // The profile list only names the install URL and adds the "each profile has
  // its own" hint; the URL itself is right without it. So a failed lookup keeps
  // the panel working, but says so instead of quietly dropping both.
  const [profilesFailed, setProfilesFailed] = useState(false);
  const [retryingProfiles, setRetryingProfiles] = useState(false);
  const [profileKey, setProfileKey] = useState<string | null>(null);

  const applyProfiles = (profiles: { id: string; name: string }[] | null) => {
    setProfilesFailed(profiles === null);
    if (profiles === null) return;
    const activeProfileId = runtimeConfig.getProfileId();
    setProfileName(profiles.find((p) => p.id === activeProfileId)?.name ?? null);
    setMultiProfile(profiles.length > 1);
  };

  // State changes only after the await, so the mount effect doesn't set state
  // synchronously; Retry resets loading/error itself before calling this.
  const load = useCallback(async () => {
    try {
      const [res, aiRes, profilesRes] = await Promise.all([
        api.getAddonConfig(),
        api.getAiConfig().catch(() => ({ configured: false, config: null, lastGeneratedAt: null })),
        api.getProfiles().then((r) => r.profiles, () => null),
      ]);
      setEnabled(res.config.enabledCatalogs);
      setAvailable(res.availableCatalogs);
      setAvailableLists(res.availableLists ?? []);
      setAiConfigured(res.aiConfigured ?? aiRes.configured);
      setProfileKey(res.stremioProfileKey ?? null);
      applyProfiles(profilesRes);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Failed to load config");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const retryProfiles = async () => {
    setRetryingProfiles(true);
    try {
      applyProfiles(await api.getProfiles().then((r) => r.profiles, () => null));
    } finally {
      setRetryingProfiles(false);
    }
  };

  const toggle = (catalog: string) => {
    setEnabled((prev) =>
      prev.includes(catalog) ? prev.filter((c) => c !== catalog) : [...prev, catalog]
    );
    setSaved(false);
  };

  const save = async () => {
    if (loadError) return;
    setSaving(true);
    setError(null);
    try {
      await api.updateAddonConfig(enabled);
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save config");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}><Loader2 size={16} className="animate-spin" /> Loading configuration…</div>;
  }

  if (loadError) {
    return (
      <div className="space-y-2">
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {loadError}
        </p>
        {/* No form here: until the config loads, the checkboxes would all read
            unchecked and Save would replace the real config with nothing. */}
        <button
          type="button"
          onClick={() => {
            setLoading(true);
            setLoadError(null);
            void load();
          }}
          className="btn-secondary"
        >
          <RefreshCw size={16} /> Retry
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <AddonManifestUrl profileName={profileName} multiProfile={multiProfile} profileKey={profileKey} onRotated={setProfileKey} />
      {profilesFailed && (
        <div role="status" className="flex flex-wrap items-center gap-2 text-xs" style={{ color: "var(--text-mute)" }}>
          <AlertCircle size={14} className="flex-none text-warning" aria-hidden="true" />
          <span className="flex-1">
            Couldn't load profile details, so this can't say which profile the URL belongs to. Each profile has its own URL.
          </span>
          <button type="button" onClick={() => void retryProfiles()} disabled={retryingProfiles} className="btn-secondary btn-sm">
            {retryingProfiles ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />} Retry
          </button>
        </div>
      )}
      <p className="text-sm leading-relaxed" style={{ color: "var(--text-mute)" }}>
        Choose which catalogs appear in Stremio
        {multiProfile && profileName ? <> for <strong style={{ color: "var(--text-dim)" }}>{profileName}</strong> — each profile picks its own</> : null}.
        Changes take effect after the manifest cache refreshes (~60s).
      </p>

      {/* Discovery catalogs */}
      <div className="space-y-2">
        {available.map((catalog) => {
          const isAiCatalog = catalog.requiresAi;
          return (
            <label
              key={catalog.id}
              className={`flex items-center gap-3 rounded-xl border px-4 py-3 cursor-pointer transition-colors ${
                isAiCatalog
                  ? "border-plum-500/30 bg-plum-500/5 hover:bg-plum-500/10"
                  : "hover:bg-[var(--surface-strong)] border-[var(--border)]"
              } ${isAiCatalog && !aiConfigured ? "opacity-50" : ""}`}
              style={isAiCatalog ? undefined : { background: "var(--surface)" }}
            >
              <input
                type="checkbox"
                checked={enabled.includes(catalog.id)}
                onChange={() => toggle(catalog.id)}
                disabled={isAiCatalog && !aiConfigured}
                className="checkbox-control"
              />
              <span className="flex-1 text-sm font-medium" style={{ color: "var(--text)" }}>{catalog.label}</span>
              {isAiCatalog && (
                <span className="inline-flex items-center gap-1 rounded-md bg-plum-500/80 px-1.5 py-0.5 text-2xs font-semibold text-white">
                  <Sparkles className="h-2.5 w-2.5" /> AI
                </span>
              )}
            </label>
          );
        })}
      </div>
      {available.some((c) => c.requiresAi) && !aiConfigured && (
        <p className="text-xs italic" style={{ color: "var(--text-mute)" }}>Configure AI Recommendations to enable the AI Picks catalogs.</p>
      )}

      {/* User lists */}
      {availableLists.length > 0 && (
        <div className="space-y-2">
          <p className={`pt-1 ${KICKER}`} style={{ color: "var(--text-mute)" }}>My Lists</p>
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>Each list adds separate Movies and Series catalogs to Stremio.</p>
          {availableLists.map((list) => {
            const catalogId = `list:${list.id}`;
            return (
              <label
                key={list.id}
                className="flex items-center gap-3 rounded-xl border px-4 py-3 cursor-pointer transition-colors hover:bg-[var(--surface-strong)] border-[var(--border)]"
                style={{ background: "var(--surface)" }}
              >
                <input
                  type="checkbox"
                  checked={enabled.includes(catalogId)}
                  onChange={() => toggle(catalogId)}
                  className="checkbox-control"
                />
                <span className="text-sm font-medium" style={{ color: "var(--text)" }}>{list.name}</span>
              </label>
            );
          })}
        </div>
      )}

      <button
        type="button"
        onClick={save}
        disabled={saving}
        className={`btn-primary ${saved ? "btn-saved" : ""}`}
      >
        {saved ? <><Check size={16} /> Saved</> : saving ? <><Loader2 size={16} className="animate-spin" /> Saving…</> : "Save configuration"}
      </button>
      {error && <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>}
    </div>
  );
}
