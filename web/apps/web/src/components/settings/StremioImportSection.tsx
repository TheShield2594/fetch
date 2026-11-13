import { useEffect, useState } from "react";
import { AlertCircle, Check, ExternalLink, Loader2 } from "lucide-react";
import { api, type StremioImportState } from "../../api";

const POLL_MS = 3000;

const plural = (count: number, one: string, many: string) => `${count.toLocaleString()} ${count === 1 ? one : many}`;

/** What a finished import brought in, in one sentence. */
export function stremioSummaryText(summary: Extract<StremioImportState, { state: "done" }>["summary"]): string {
  const parts = [
    plural(summary.movies, "film", "films"),
    plural(summary.episodes, "episode", "episodes"),
    plural(summary.resumePoints, "resume point", "resume points"),
  ];
  let text = `Imported ${parts.join(", ")}.`;
  if (summary.skipped) text += ` ${plural(summary.skipped, "title", "titles")} without an IMDb id were skipped.`;
  if (summary.unreadable) {
    text += ` Episode history for ${plural(summary.unreadable, "show", "shows")} couldn't be read in full; their last watched episode was still imported.`;
  }
  return text;
}

/**
 * Settings → Data: import a Stremio account's watch history into this profile.
 * The server shows Stremio's link code; approving it on stremio.com starts the
 * import, which this polls until it has finished.
 */
export function StremioImportSection({ profileName }: { profileName: string }) {
  const [state, setState] = useState<StremioImportState>({ state: "idle" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const active = state.state === "waiting" || state.state === "importing";

  useEffect(() => {
    if (!active) return;
    const timer = window.setTimeout(() => {
      api
        .pollStremioImport()
        .then((next) => {
          setError(null);
          setState(next);
        })
        .catch((err: unknown) => setError(err instanceof Error ? err.message : "Could not reach the server."));
    }, POLL_MS);
    return () => window.clearTimeout(timer);
  }, [active, state]);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      setState(await api.startStremioImport());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reach Stremio.");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setState(await api.cancelStremioImport().catch(() => ({ state: "idle" }) as StremioImportState));
  };

  return (
    <div className="space-y-3">
      <div>
        <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Import from a Stremio account</p>
        <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
          Bring a Stremio library&apos;s watched films and episodes and its resume points into{" "}
          <strong>{profileName}</strong>. You approve a code on stremio.com; Fetch reads the library once and signs out.
          Nothing is removed, and plays already here are kept.
        </p>
      </div>

      {state.state === "waiting" && (
        <div className="space-y-2 rounded-xl p-4" style={{ border: "1px solid var(--border)" }}>
          <p className="text-sm" style={{ color: "var(--text)" }}>
            Open{" "}
            <a href={state.link} target="_blank" rel="noopener noreferrer" className="underline">
              {state.link} <ExternalLink size={12} className="inline" />
            </a>{" "}
            on any device signed in to Stremio and approve the code:
          </p>
          <p className="text-2xl font-bold tracking-widest" style={{ color: "var(--text)" }} aria-label="Stremio code">
            {state.code}
          </p>
          <p className="flex items-center gap-2 text-sm" role="status" style={{ color: "var(--text-dim)" }}>
            <Loader2 size={14} className="animate-spin" /> Waiting for approval…
          </p>
          <button type="button" onClick={() => void cancel()} className="btn-secondary">
            Cancel
          </button>
        </div>
      )}

      {state.state === "importing" && (
        <p className="flex items-center gap-2 text-sm" role="status" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={14} className="animate-spin" /> Importing your Stremio library…
        </p>
      )}

      {state.state === "done" && (
        <p className="flex items-center gap-2 text-sm text-success" role="status">
          <Check size={16} /> {stremioSummaryText(state.summary)}
        </p>
      )}
      {state.state === "expired" && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> The code wasn&apos;t approved in time. Start again for a new one.
        </p>
      )}
      {state.state === "failed" && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {state.error}
        </p>
      )}
      {error && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}

      {!active && (
        <button type="button" onClick={() => void start()} disabled={busy} className="btn-secondary">
          {busy ? <Loader2 size={16} className="animate-spin" /> : null}
          {state.state === "idle" ? "Import from Stremio" : "Start again"}
        </button>
      )}
    </div>
  );
}
