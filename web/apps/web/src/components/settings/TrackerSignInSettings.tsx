import { useEffect, useState } from "react";
import { AlertCircle, ExternalLink, Loader2, RefreshCw, Unplug } from "lucide-react";
import { api, type TrackerSyncSummary } from "../../api";
import { StatusBadge } from "./StatusBadge";

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? "" : "s"}`;

export const trackerSummaryText = (name: string, summary: TrackerSyncSummary): string => {
  const parts = [
    summary.movies > 0 ? plural(summary.movies, "film") : null,
    summary.episodes > 0 ? plural(summary.episodes, "episode") : null,
    summary.watchlist > 0 ? `${summary.watchlist} on the watchlist` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `Brought in ${parts.join(", ")}.` : `Nothing new on ${name}.`;
};

export type TrackerPending = { userCode: string; verificationUrl: string; verificationUrlComplete?: string; interval: number };

export type TrackerStatus = {
  configured: boolean;
  connected: boolean;
  username: string | null;
  lastSyncedAt: string | null;
  pending: TrackerPending | null;
};

export type TrackerSignInApi = {
  status: () => Promise<TrackerStatus>;
  start: () => Promise<TrackerPending>;
  poll: () => Promise<{ result: string; status: TrackerStatus }>;
  sync: (full: boolean) => Promise<TrackerSyncSummary>;
  disconnect: () => Promise<unknown>;
};

/**
 * A profile's own account on a tracking service that signs in the way the TV
 * does it, with a code entered on the service's site (Simkl, MDBList). Once
 * connected, plays, watchlist adds and scrobbles go to it, and its history and
 * watchlist come in on a schedule.
 */
export function TrackerSignInSettings({
  name,
  envVar,
  intro,
  client,
}: {
  name: string;
  envVar: string;
  intro: string;
  client: TrackerSignInApi;
}) {
  const [status, setStatus] = useState<TrackerStatus | null>(null);
  const [busy, setBusy] = useState<"connect" | "sync" | "disconnect" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    client
      .status()
      .then((loaded) => {
        if (!cancelled) setStatus(loaded);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : `Couldn't load ${name}.`);
      });
    return () => {
      cancelled = true;
    };
  }, [client, name]);

  // While a code is waiting to be entered, check every `interval` seconds.
  // Each answer replaces `status`, which schedules the next check, or stops
  // once it is connected or the code has expired.
  const pending = status && !status.connected ? status.pending : null;
  useEffect(() => {
    if (!pending) return;
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { result: outcome, status: next } = await client.poll();
          if (outcome === "expired") setError("The code expired before it was entered. Start again.");
          if (outcome === "denied") setError(`The sign-in was declined on ${name}.`);
          setStatus(outcome === "pending" ? { ...next, pending: next.pending ?? pending } : next);
        } catch (err) {
          setError(err instanceof Error ? err.message : `Couldn't check the ${name} sign-in.`);
        }
      })();
    }, pending.interval * 1000);
    return () => window.clearTimeout(timer);
  }, [client, name, pending, status]);

  const connect = async () => {
    setBusy("connect");
    setError(null);
    try {
      const pin = await client.start();
      setStatus((current) => (current ? { ...current, pending: pin } : current));
    } catch (err) {
      setError(err instanceof Error ? err.message : `Couldn't start the ${name} sign-in.`);
    } finally {
      setBusy(null);
    }
  };

  const sync = async (full: boolean) => {
    setBusy("sync");
    setError(null);
    setResult(null);
    try {
      setResult(trackerSummaryText(name, await client.sync(full)));
      setStatus(await client.status());
    } catch (err) {
      setError(err instanceof Error ? err.message : `${name} sync failed.`);
    } finally {
      setBusy(null);
    }
  };

  const disconnect = async () => {
    setBusy("disconnect");
    setError(null);
    try {
      await client.disconnect();
      setResult(null);
      setStatus(await client.status());
    } catch (err) {
      setError(err instanceof Error ? err.message : `Couldn't disconnect ${name}.`);
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
      <p className="leading-relaxed">{intro}</p>

      {status && (
        <StatusBadge
          ok={status.connected}
          label={status.connected ? `Connected${status.username ? ` as ${status.username}` : ""}` : "Not connected"}
        />
      )}

      {status && !status.configured && (
        <p role="alert" className="flex items-center gap-2 text-warning">
          <AlertCircle size={16} /> {name} isn't set up on this server yet: the admin needs to set {envVar}.
        </p>
      )}

      {status?.pending && !status.connected && (
        <div className="space-y-2 rounded-xl p-3" style={{ border: "1px solid var(--border)", background: "var(--bg-1)" }}>
          <p>
            Go to{" "}
            <a href={status.pending.verificationUrlComplete ?? status.pending.verificationUrl} target="_blank" rel="noopener noreferrer" className="underline">
              {status.pending.verificationUrl} <ExternalLink size={12} className="inline" />
            </a>{" "}
            and enter this code:
          </p>
          <p className="font-mono text-2xl tracking-widest" style={{ color: "var(--text)" }} aria-label={`${name} code`}>
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
            {status?.pending ? "Get a new code" : `Connect ${name}`}
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

const simklClient: TrackerSignInApi = {
  status: () => api.getSimklStatus(),
  start: () => api.startSimklPin(),
  poll: () => api.pollSimklPin(),
  sync: (full) => api.syncSimkl(full),
  disconnect: () => api.disconnectSimkl(),
};

const mdbListClient: TrackerSignInApi = {
  status: () => api.getMdbListStatus(),
  start: () => api.startMdbListDevice(),
  poll: () => api.pollMdbListDevice(),
  sync: (full) => api.syncMdbList(full),
  disconnect: () => api.disconnectMdbList(),
};

export function SimklSettings() {
  return (
    <TrackerSignInSettings
      name="Simkl"
      envVar="SIMKL_CLIENT_ID"
      client={simklClient}
      intro="Connect this profile's Simkl account. What you watch here goes to Simkl, and your Simkl history and plan-to-watch list come into Fetch. This is separate from the TV's Simkl sign-in."
    />
  );
}

export function MdbListSettings() {
  return (
    <TrackerSignInSettings
      name="MDBList"
      envVar="MDBLIST_CLIENT_ID"
      client={mdbListClient}
      intro="Connect this profile's MDBList account. What you watch here goes to MDBList, and your MDBList watched history and watchlist come into Fetch. This is separate from the TV's MDBList sign-in."
    />
  );
}
