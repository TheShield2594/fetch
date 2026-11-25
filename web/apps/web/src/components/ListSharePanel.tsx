import { useState } from "react";
import { Link2, Loader2, RefreshCw, X } from "lucide-react";
import { api, type CatalogList } from "../api";
import type { ShowToast } from "../hooks/useToast";
import { sharedListPageUrl } from "../utils/shareLinks";
import { CopyLinkField } from "./CopyLinkField";

/**
 * A list's read-only link: a page anyone with the link can open without an
 * account, showing the list's name and titles and nothing else. Off until
 * shared; replacing the link or unsharing stops the old one working.
 */
export function ListSharePanel({
  list,
  onMessage,
  onChanged,
}: {
  list: CatalogList;
  onMessage: ShowToast;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  // What the last action returned, until the reloaded list catches up with it.
  const [override, setOverride] = useState<{ sharePath: string | null } | null>(null);
  const sharePath = override ? override.sharePath : (list.sharePath ?? null);
  const url = sharePath ? sharedListPageUrl(sharePath) : null;

  const run = async (action: "share" | "unshare", done: string) => {
    setBusy(true);
    try {
      if (action === "share") setOverride(await api.shareList(list.id));
      else {
        await api.unshareList(list.id);
        setOverride({ sharePath: null });
      }
      onMessage(done, "success");
      onChanged();
    } catch (err) {
      onMessage(err instanceof Error ? err.message : "Couldn't change sharing", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section
      className="space-y-3 rounded-xl border p-4 text-sm"
      style={{ borderColor: "var(--border)", background: "var(--bg-1)", color: "var(--text-dim)" }}
      aria-label="Share this list"
    >
      <h3 className="flex items-center gap-2 font-medium" style={{ color: "var(--text)" }}>
        <Link2 className="h-4 w-4" /> Share this list
      </h3>
      {url ? (
        <>
          <p>
            Anyone with this link can see the list's name and titles, without an account. They can't change it, and
            they see nothing else of yours.
          </p>
          <CopyLinkField label="Share link" url={url} />
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy}
              onClick={() => void run("share", "New link made. The old one no longer works.")}
              className="btn-secondary"
            >
              {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />} Replace link
            </button>
            <button type="button" disabled={busy} onClick={() => void run("unshare", "Stopped sharing")} className="btn-secondary">
              <X className="h-4 w-4" /> Stop sharing
            </button>
          </div>
        </>
      ) : (
        <>
          <p>Make a read-only link to send to someone who doesn't have an account here.</p>
          <button type="button" disabled={busy} onClick={() => void run("share", "Link made")} className="btn-secondary">
            {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Link2 className="h-4 w-4" />} Make a share link
          </button>
        </>
      )}
    </section>
  );
}
