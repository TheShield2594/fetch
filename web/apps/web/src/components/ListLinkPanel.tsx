import { useEffect, useState } from "react";
import { AlertTriangle, Link2, RefreshCw, Unlink } from "lucide-react";
import { api, type CatalogList, type ListLinkProvider, type ListLinkSyncResult, type ListLinkTarget } from "../api";
import { SelectField } from "./SelectField";
import { timeAgo } from "../utils/timeAgo";

const SERVICE_NAMES: Record<ListLinkProvider, string> = { trakt: "Trakt", mdblist: "MDBList" };

/** "Added 2, sent 1" — what a sync just did, or that nothing needed doing. */
export function describeListLinkSync(sync: ListLinkSyncResult): string {
  const parts = [
    sync.addedLocally && `${sync.addedLocally} added here`,
    sync.removedLocally && `${sync.removedLocally} removed here`,
    sync.pushedAdds && `${sync.pushedAdds} added there`,
    sync.pushedRemovals && `${sync.pushedRemovals} removed there`,
  ].filter(Boolean);
  return parts.length ? parts.join(", ") : "Already in step";
}

/**
 * Linking a custom list to one of the profile's own lists on Trakt or MDBList.
 * Adds and removes then go both ways; deleting either list never deletes the
 * other, and unlinking leaves both as they are.
 */
export function ListLinkPanel({
  list,
  onChanged,
  onMessage,
}: {
  list: CatalogList;
  /** Reload lists and items: a link or a sync can change both. */
  onChanged: () => void;
  onMessage: (message: string, tone: "success" | "error" | "info") => void;
}) {
  const link = list.link ?? null;
  const [open, setOpen] = useState(false);
  const [provider, setProvider] = useState<ListLinkProvider>("trakt");
  const [targets, setTargets] = useState<ListLinkTarget[] | null>(null);
  const [targetsError, setTargetsError] = useState<string | null>(null);
  const [remoteId, setRemoteId] = useState("new");
  const [busy, setBusy] = useState(false);

  // Opening the picker or switching service starts the choice over; the
  // effect below then loads that service's lists.
  const choose = (next: { open?: boolean; provider?: ListLinkProvider }) => {
    setTargets(null);
    setTargetsError(null);
    setRemoteId("new");
    if (next.open !== undefined) setOpen(next.open);
    if (next.provider !== undefined) setProvider(next.provider);
  };

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    api
      .getListLinkTargets(provider, controller.signal)
      .then(({ lists }) => {
        if (!controller.signal.aborted) setTargets(lists);
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setTargetsError(err instanceof Error ? err.message : "Couldn't load lists");
      });
    return () => controller.abort();
  }, [open, provider]);

  // An action resolves to its message; a sync that ran but recorded an error
  // resolves with the error tone, and the link is reloaded either way.
  const run = async (action: () => Promise<string | { error: string }>) => {
    setBusy(true);
    try {
      const result = await action();
      if (typeof result === "string") onMessage(result, "success");
      else onMessage(result.error, "error");
      onChanged();
    } catch (err) {
      onMessage(err instanceof Error ? err.message : "Something went wrong", "error");
    } finally {
      setBusy(false);
    }
  };

  const handleLink = () =>
    run(async () => {
      const { sync } = await api.linkList(list.id, provider, remoteId);
      setOpen(false);
      if (sync.error) return { error: `Linked to ${SERVICE_NAMES[provider]}, but the first sync failed: ${sync.error}` };
      return `Linked to ${SERVICE_NAMES[provider]}. ${describeListLinkSync(sync)}.`;
    });

  const handleSync = () =>
    run(async () => {
      const { sync } = await api.syncListLink(list.id);
      return sync.error ? { error: sync.error } : describeListLinkSync(sync);
    });

  const handleUnlink = () =>
    run(async () => {
      await api.unlinkList(list.id);
      return `Unlinked from ${link ? SERVICE_NAMES[link.provider] : "the service"}. Both lists are kept as they are.`;
    });

  if (link) {
    return (
      <div
        className="mb-5 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border px-4 py-3 text-sm"
        style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}
      >
        <Link2 className="h-4 w-4 flex-none text-claw-text" aria-hidden="true" />
        <p className="min-w-0 flex-1" style={{ color: "var(--text-dim)" }}>
          Kept in step with <span className="font-semibold" style={{ color: "var(--text)" }}>{link.remoteName}</span> on{" "}
          {SERVICE_NAMES[link.provider]}
          <span className="meta-row block" style={{ color: "var(--text-mute)" }}>
            {link.lastSyncedAt ? `Synced ${timeAgo(link.lastSyncedAt)}` : "Not synced yet"}
          </span>
        </p>
        <div className="flex flex-none gap-2">
          <button type="button" disabled={busy} onClick={() => void handleSync()} className="btn-secondary btn-sm">
            <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
            Sync now
          </button>
          <button type="button" disabled={busy} onClick={() => void handleUnlink()} className="btn-secondary btn-sm">
            <Unlink className="h-3.5 w-3.5" aria-hidden="true" />
            Unlink
          </button>
        </div>
        {link.lastError && (
          <p role="alert" className="flex w-full items-start gap-2 text-xs text-danger">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-none" aria-hidden="true" />
            {link.lastError}
          </p>
        )}
      </div>
    );
  }

  if (!open) {
    return (
      <div className="mb-5">
        <button type="button" onClick={() => choose({ open: true })} className="btn-secondary btn-sm">
          <Link2 className="h-3.5 w-3.5" aria-hidden="true" />
          Link to Trakt or MDBList
        </button>
      </div>
    );
  }

  return (
    <div
      className="mb-5 space-y-3 rounded-xl border px-4 py-3 text-sm"
      style={{ borderColor: "var(--border)", background: "var(--bg-1)" }}
    >
      <p style={{ color: "var(--text-dim)" }}>
        Adds and removes go both ways. Deleting either list never deletes the other.
      </p>
      <div className="flex flex-wrap gap-2">
        <SelectField
          aria-label="Service"
          value={provider}
          onChange={(e) => choose({ provider: e.target.value as ListLinkProvider })}
          className="rounded-xl px-3 py-2 text-sm"
        >
          <option value="trakt">Trakt</option>
          <option value="mdblist">MDBList</option>
        </SelectField>
        <SelectField
          aria-label={`${SERVICE_NAMES[provider]} list`}
          value={remoteId}
          onChange={(e) => setRemoteId(e.target.value)}
          disabled={!targets}
          className="rounded-xl px-3 py-2 text-sm"
          wrapperClassName="min-w-0 flex-1"
        >
          <option value="new">New list named "{list.name}"</option>
          {targets?.map((target) => (
            <option key={target.id} value={target.id}>
              {target.name}
              {target.itemCount != null ? ` (${target.itemCount})` : ""}
            </option>
          ))}
        </SelectField>
      </div>
      {targetsError && (
        <p role="alert" className="text-xs text-danger">
          {targetsError}
        </p>
      )}
      <div className="flex gap-2">
        <button
          type="button"
          disabled={busy || !targets}
          onClick={() => void handleLink()}
          className="btn-primary btn-sm"
        >
          {busy ? "Linking…" : "Link"}
        </button>
        <button type="button" disabled={busy} onClick={() => setOpen(false)} className="btn-secondary btn-sm">
          Cancel
        </button>
      </div>
    </div>
  );
}
