import { useEffect, useState } from "react";
import { AlertCircle, Check, Copy, KeyRound, Loader2, Trash2 } from "lucide-react";
import { api, runtimeConfig } from "../../api";
import { useTransientFlag } from "../../hooks/useTransientFlag";

const SERVERS = [
  { id: "plex", label: "Plex" },
  { id: "jellyfin", label: "Jellyfin" },
  { id: "emby", label: "Emby" },
] as const;

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

/**
 * The API's address as a media server has to be given it: absolute, even when
 * the website reaches the API by a path on its own origin (`/api`).
 */
export const webhookUrl = (server: string, key: string): string => {
  const base = new URL(runtimeConfig.getApiBase().replace(/\/+$/, "") + "/", window.location.origin);
  const url = new URL(`webhooks/${server}`, base);
  url.searchParams.set("token", key);
  return url.toString();
};

function WebhookUrl({ label, url }: { label: string; url: string }) {
  const [copied, setCopied] = useTransientFlag();
  const [copyError, setCopyError] = useTransientFlag(3000);

  const copy = () => {
    navigator.clipboard.writeText(url).then(() => setCopied(true)).catch(() => setCopyError(true));
  };

  return (
    <div className="space-y-1">
      <p className="text-xs font-medium" style={{ color: "var(--text-dim)" }}>{label}</p>
      <div className="flex items-center gap-2">
        <input
          readOnly
          value={url}
          aria-label={`${label} webhook URL`}
          onFocus={(e) => e.currentTarget.select()}
          className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 font-mono text-xs field-control"
          style={{ borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-0)" }}
        />
        <button type="button" onClick={copy} className="btn-secondary flex-none" aria-label={`Copy ${label} webhook URL`}>
          {copied ? <Check size={16} /> : <Copy size={16} />}
        </button>
      </div>
      {copyError && <p role="alert" className="text-xs text-danger">Couldn't copy. Select the URL and copy it by hand.</p>}
    </div>
  );
}

/**
 * This account's link for Plex, Jellyfin and Emby to report plays to. The key
 * in it puts plays in this account's profiles only, and is shown once, when it
 * is made: making another replaces it.
 */
export function WebhookSettings() {
  const [status, setStatus] = useState<{ configured: boolean; createdAt: string | null } | null>(null);
  const [newKey, setNewKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getWebhookKey().then(setStatus).catch((err: unknown) => {
      setError(err instanceof Error ? err.message : "Couldn't load your webhook link.");
    });
  }, []);

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const made = await api.createWebhookKey();
      setNewKey(made.key);
      setStatus({ configured: true, createdAt: made.createdAt });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't make a webhook link.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.removeWebhookKey();
      setNewKey(null);
      setStatus({ configured: false, createdAt: null });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't remove the webhook link.");
    } finally {
      setBusy(false);
    }
  };

  if (!status && !error) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading...
      </div>
    );
  }

  return (
    <div className="space-y-4 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="leading-relaxed">
        Point your media server's webhook at this link and Fetch marks what you finish as watched. Plays go to the
        profile named like the media-server user, or else your first profile, and only ever to your own profiles.
      </p>

      {newKey ? (
        <div className="space-y-3 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
          <p>Copy the one for your server now. The link won't be shown again; make a new one if you lose it.</p>
          {SERVERS.map((server) => (
            <WebhookUrl key={server.id} label={server.label} url={webhookUrl(server.id, newKey)} />
          ))}
        </div>
      ) : status?.configured ? (
        <p role="status" className="flex items-center gap-2">
          <Check size={16} className="text-success" /> You have a webhook link
          {status.createdAt ? `, made ${formatDate(status.createdAt)}` : ""}.
        </p>
      ) : null}

      {error && (
        <p role="alert" className="flex items-center gap-2 text-danger"><AlertCircle size={16} /> {error}</p>
      )}

      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => void create()} disabled={busy} className="btn-secondary">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <KeyRound size={16} />}
          {status?.configured ? " Make a new link" : " Make a webhook link"}
        </button>
        {status?.configured && (
          <button type="button" onClick={() => void remove()} disabled={busy} className="btn-secondary">
            <Trash2 size={16} /> Remove link
          </button>
        )}
      </div>
      {status?.configured && !newKey && (
        <p className="text-xs" style={{ color: "var(--text-mute)" }}>
          Making a new link stops the old one working, so update your media server with it.
        </p>
      )}
    </div>
  );
}
