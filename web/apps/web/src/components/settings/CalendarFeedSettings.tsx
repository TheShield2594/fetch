import { useEffect, useState } from "react";
import { AlertCircle, CalendarPlus, Loader2, RefreshCw, X } from "lucide-react";
import { api } from "../../api";
import { apiUrl } from "../../utils/shareLinks";
import { CopyLinkField } from "../CopyLinkField";

/**
 * This profile's calendar as a feed a phone or desktop calendar can subscribe
 * to. A calendar app can't sign in, so the feed's address is its own key: it
 * is off until turned on here, and replacing or turning it off stops the old
 * address working.
 */
export function CalendarFeedSettings() {
  const [path, setPath] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getCalendarFeed().then((feed) => setPath(feed.path)).catch((err: unknown) => {
      setError(err instanceof Error ? err.message : "Couldn't load the calendar feed.");
      setPath(null);
    });
  }, []);

  const run = async (action: () => Promise<string | null>) => {
    setBusy(true);
    setError(null);
    try {
      setPath(await action());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't change the calendar feed.");
    } finally {
      setBusy(false);
    }
  };
  const enable = () => run(async () => (await api.enableCalendarFeed()).path);
  const disable = () => run(async () => {
    await api.disableCalendarFeed();
    return null;
  });

  if (path === undefined) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading…
      </div>
    );
  }

  return (
    <div className="space-y-4 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="leading-relaxed">
        Subscribe to this profile's calendar from your phone or computer: upcoming episodes of what you're watching
        and what's on your watchlist, and your watchlisted films' release dates.
      </p>

      {path ? (
        <>
          <CopyLinkField label="Calendar feed address" url={apiUrl(path)} />
          <p className="text-xs" style={{ color: "var(--text-mute)" }}>
            In your calendar app, add a calendar by URL (Apple: Add Subscription Calendar; Google: From URL) and paste
            this. Anyone with the address can read the feed, so replace it if it gets out.
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" onClick={() => void enable()} disabled={busy} className="btn-secondary">
              {busy ? <Loader2 size={16} className="animate-spin" /> : <RefreshCw size={16} />} Replace address
            </button>
            <button type="button" onClick={() => void disable()} disabled={busy} className="btn-secondary">
              <X size={16} /> Turn off
            </button>
          </div>
        </>
      ) : (
        <button type="button" onClick={() => void enable()} disabled={busy} className="btn-secondary">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <CalendarPlus size={16} />} Turn on the calendar feed
        </button>
      )}

      {error && (
        <p role="alert" className="flex items-center gap-2 text-danger"><AlertCircle size={16} /> {error}</p>
      )}
    </div>
  );
}
