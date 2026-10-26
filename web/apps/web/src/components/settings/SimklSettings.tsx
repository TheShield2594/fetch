import { useEffect, useState } from "react";
import { AlertCircle, ExternalLink, Loader2, RefreshCw, Unplug } from "lucide-react";
import { api, type SimklStatus, type TrackerSyncSummary } from "../../api";
import { StatusBadge } from "./StatusBadge";

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? "" : "s"}`;

export const simklSummaryText = (summary: TrackerSyncSummary): string => {
  const parts = [
    summary.movies > 0 ? plural(summary.movies, "film") : null,
    summary.episodes > 0 ? plural(summary.episodes, "episode") : null,
    summary.watchlist > 0 ? `${summary.watchlist} on the watchlist` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `Brought in ${parts.join(", ")}.` : "Nothing new on Simkl.";
};

/**
 * This profile's Simkl account, connected the way the TV does it: a code to
 * enter at simkl.com/pin. Once connected, plays, watchlist adds and scrobbles
 * go to Simkl, and its library comes in on a schedule.
 */
export function SimklSettings() {
  const [status, setStatus] = useState<SimklStatus | null>(null);
  const [busy, setBusy] = useState<"connect" | "sync" | "disconnect" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getSimklStatus()
      .then((loaded) => {
        if (!cancelled) setStatus(loaded);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Couldn't load Simkl.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // While a code is waiting to be entered, check every `interval` seconds.
  // Each answer replaces `status`, which schedules the next check, or stops
  // once it is connected or the code has expired.
  const pending = status && !status.connected ? status.pending : null;
  useEffect(() => {
    if (!pending) return;
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { result: outcome, status: next } = await api.pollSimklPin();
          if (outcome === "expired") setError("The code expired before it was entered. Start again.");
          setStatus(outcome === "pending" ? { ...next, pending: next.pending ?? pending } : next);
        } catch (err) {
          setError(err instanceof Error ? err.message : "Couldn't check the Simkl sign-in.");
        }
      })();
    }, pending.interval * 1000);
    return () => window.clearTimeout(timer);
  }, [pending, status]);

  const connect = async () => {
    setBusy("connect");
    setError(null);
    try {
      const pin = await api.startSimklPin();
      setStatus((current) => (current ? { ...current, pending: pin } : current));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't start the Simkl sign-in.");
    } finally {
      setBusy(null);
    }
  };

  const sync = async (full: boolean) => {
    setBusy("sync");
    setError(null);
    setResult(null);
    try {
      setResult(simklSummaryText(await api.syncSimkl(full)));
      setStatus(await api.getSimklStatus());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Simkl sync failed.");
    } finally {
      setBusy(null);
    }
  };

  const disconnect = async () => {
    setBusy("disconnect");
    setError(null);
    try {
      await api.disconnectSimkl();
      setResult(null);
      setStatus(await api.getSimklStatus());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't disconnect Simkl.");
    } finally {
      setBusy(null);
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
        Connect this profile's Simkl account. What you watch here goes to Simkl, and your Simkl history and
        plan-to-watch list come into Fetch. This is separate from the TV's Simkl sign-in.
      </p>

      {status && (
        <StatusBadge
          ok={status.connected}
          label={status.connected ? `Connected${status.username ? ` as ${status.username}` : ""}` : "Not connected"}
        />
      )}

      {status && !status.configured && (
        <p role="alert" className="flex items-center gap-2 text-warning">
          <AlertCircle size={16} /> Simkl isn't set up on this server yet: the admin needs to set SIMKL_CLIENT_ID.
        </p>
      )}

      {status?.pending && !status.connected && (
        <div className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
          <p>
            Go to{" "}
            <a href={status.pending.verificationUrl} target="_blank" rel="noopener noreferrer" className="underline">
              {status.pending.verificationUrl} <ExternalLink size={12} className="inline" />
            </a>{" "}
            and enter this code:
          </p>
          <p className="font-mono text-2xl tracking-widest" style={{ color: "var(--text)" }} aria-label="Simkl code">
            {status.pending.userCode}
          </p>
          <p className="flex items-center gap-2 text-xs" style={{ color: "var(--text-mute)" }}>
            <Loader2 size={12} className="animate-spin" /> Waiting for you to enter it...
          </p>
        </div>
      )}

      {result && <p role="status">{result}</p>}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {status?.connected ? (
          <>
            <button type="button" className="btn-secondary" disabled={busy !== null} onClick={() => void sync(false)}>
              {busy === "sync" ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />} Sync now
            </button>
            <button type="button" className="btn-secondary" disabled={busy !== null} onClick={() => void sync(true)}>
              Import everything again
            </button>
            <button type="button" className="btn-secondary" disabled={busy !== null} onClick={() => void disconnect()}>
              <Unplug size={16} /> Disconnect
            </button>
          </>
        ) : (
          <button
            type="button"
            className="btn-primary"
            disabled={busy !== null || !status?.configured}
            onClick={() => void connect()}
          >
            {busy === "connect" ? <Loader2 size={16} className="animate-spin" /> : null}
            {status?.pending ? "Get a new code" : "Connect Simkl"}
          </button>
        )}
      </div>
      {status?.lastSyncedAt && (
        <p className="text-xs" style={{ color: "var(--text-mute)" }}>
          Last synced {new Date(status.lastSyncedAt).toLocaleString()}.
        </p>
      )}
    </div>
  );
}
