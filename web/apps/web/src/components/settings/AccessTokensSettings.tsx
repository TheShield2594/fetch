import { type FormEvent, useEffect, useId, useState } from "react";
import { AlertCircle, Check, Copy, KeyRound, Loader2, Trash2 } from "lucide-react";
import { api, type AccessToken } from "../../api";
import { timeAgo } from "../../utils/timeAgo";
import { useTransientFlag } from "../../hooks/useTransientFlag";

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

/** How long a new token lasts. `null` is no expiry. */
const LIFETIMES: { label: string; days: number | null }[] = [
  { label: "30 days", days: 30 },
  { label: "90 days", days: 90 },
  { label: "1 year", days: 365 },
  { label: "No expiry", days: null },
];
const DEFAULT_LIFETIME = "1";

function NewToken({ name, token }: { name: string; token: string }) {
  const [copied, setCopied] = useTransientFlag();
  const [copyError, setCopyError] = useTransientFlag(3000);

  const copy = () => {
    navigator.clipboard.writeText(token).then(() => setCopied(true)).catch(() => setCopyError(true));
  };

  return (
    <div className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
      <p>
        Copy the token for <strong style={{ color: "var(--text)" }}>{name}</strong> now. It won't be shown again; make a
        new one if you lose it.
      </p>
      <div className="flex items-center gap-2">
        <input
          readOnly
          value={token}
          aria-label="New access token"
          onFocus={(e) => e.currentTarget.select()}
          className="w-full min-w-0 rounded-lg border px-2.5 py-1.5 font-mono text-xs field-control"
          style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-0)" }}
        />
        <button type="button" onClick={copy} className="btn-secondary flex-none" aria-label="Copy access token">
          {copied ? <Check size={16} /> : <Copy size={16} />}
        </button>
      </div>
      {copyError && <p role="alert" className="text-xs text-danger">Couldn't copy. Select the token and copy it by hand.</p>}
      <p className="text-xs" style={{ color: "var(--text-mute)" }}>
        Send it as <code>Authorization: Bearer …</code>.
      </p>
    </div>
  );
}

/**
 * Settings → Access tokens: credentials for scripts and integrations (Home
 * Assistant, a cron job) that act as this account without its password. Each
 * is named, can be limited to reading, and is revoked on its own.
 */
export function AccessTokensSettings() {
  const [tokens, setTokens] = useState<AccessToken[] | null>(null);
  const [created, setCreated] = useState<{ name: string; token: string } | null>(null);
  const [name, setName] = useState("");
  const [readOnly, setReadOnly] = useState(true);
  const [lifetime, setLifetime] = useState(DEFAULT_LIFETIME);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameId = useId();
  const lifetimeId = useId();
  const readOnlyId = useId();

  useEffect(() => {
    api.getAccessTokens().then((res) => setTokens(res.tokens)).catch((err: unknown) => {
      setError(err instanceof Error ? err.message : "Couldn't load your access tokens.");
      setTokens([]);
    });
  }, []);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const made = await api.createAccessToken({
        name: trimmed,
        readOnly,
        expiresInDays: LIFETIMES[Number(lifetime)]?.days ?? null,
      });
      setCreated({ name: made.accessToken.name, token: made.token });
      setTokens((current) => [...(current ?? []), made.accessToken]);
      setName("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't make an access token.");
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (token: AccessToken) => {
    setBusy(true);
    setError(null);
    try {
      await api.revokeAccessToken(token.id);
      setTokens((current) => (current ?? []).filter((entry) => entry.id !== token.id));
      setCreated((current) => (current?.name === token.name ? null : current));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't revoke the access token.");
    } finally {
      setBusy(false);
    }
  };

  if (!tokens) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading…
      </div>
    );
  }

  return (
    <div className="space-y-4 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="leading-relaxed">
        An access token lets a script or an integration such as Home Assistant use the API as you, without your
        password. It reaches your profiles only, can't change your account or sign-ins, and stops working the moment
        you revoke it.
      </p>

      {created && <NewToken name={created.name} token={created.token} />}

      {tokens.length > 0 && (
        <ul className="divide-y rounded-xl" style={{ border: "1px solid var(--border)", borderColor: "var(--border)" }}>
          {tokens.map((token) => (
            <li key={token.id} className="flex items-center gap-3 px-3 py-2.5" style={{ borderColor: "var(--border)" }}>
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium" style={{ color: "var(--text)" }}>{token.name}</p>
                <p className="text-xs" style={{ color: "var(--text-mute)" }}>
                  {token.readOnly ? "Read-only" : "Read and write"}
                  {" · "}
                  {token.lastUsedAt ? `Last used ${timeAgo(token.lastUsedAt)}` : "Never used"}
                  {" · "}
                  {token.expiresAt ? `Expires ${formatDate(token.expiresAt)}` : "No expiry"}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void revoke(token)}
                disabled={busy}
                className="btn-secondary flex-none"
                aria-label={`Revoke ${token.name}`}
              >
                <Trash2 size={16} /> Revoke
              </button>
            </li>
          ))}
        </ul>
      )}

      {error && (
        <p role="alert" className="flex items-center gap-2 text-danger"><AlertCircle size={16} /> {error}</p>
      )}

      <form onSubmit={(event) => void create(event)} className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[12rem] flex-1 space-y-1">
            <label htmlFor={nameId} className="text-xs font-medium">Name</label>
            <input
              id={nameId}
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={60}
              placeholder="Home Assistant"
              autoComplete="off"
              className="w-full rounded-lg border px-2.5 py-1.5 field-control"
              style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-0)" }}
            />
          </div>
          <div className="space-y-1">
            <label htmlFor={lifetimeId} className="text-xs font-medium">Expires after</label>
            <select
              id={lifetimeId}
              value={lifetime}
              onChange={(e) => setLifetime(e.target.value)}
              className="block rounded-lg border px-2.5 py-1.5 field-control"
              style={{ borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-0)" }}
            >
              {LIFETIMES.map((option, index) => (
                <option key={option.label} value={index}>{option.label}</option>
              ))}
            </select>
          </div>
        </div>
        <label htmlFor={readOnlyId} className="flex items-center gap-2">
          <input id={readOnlyId} type="checkbox" checked={readOnly} onChange={(e) => setReadOnly(e.target.checked)} />
          Read-only: it can look but not change anything
        </label>
        <button type="submit" disabled={busy || !name.trim()} className="btn-secondary">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <KeyRound size={16} />} Make a token
        </button>
      </form>
    </div>
  );
}
