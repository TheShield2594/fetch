import { useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";
import { type AccountEvent, api } from "../../api";
import { timeAgo } from "../../utils/timeAgo";
import { describeUserAgent } from "../../utils/userAgent";

/** What each event the API records is called here. Unknown kinds show their code. */
const EVENT_LABELS: Record<string, string> = {
  sign_in: "Signed in",
  sign_in_failed: "Failed sign-in attempt",
  password_changed: "Password changed",
  password_reset: "Password reset",
  pin_changed: "Profile PIN changed",
  pin_removed: "Profile PIN removed",
  pin_cleared: "Forgotten PIN cleared",
  tv_paired: "TV signed in",
  tv_signed_out: "TV signed out",
  session_revoked: "A browser was signed out",
  signed_out_everywhere: "Signed out everywhere else",
  username_changed: "Username changed",
  admin_granted: "Became the admin",
  admin_transferred: "Handed admin on",
};

/** Events worth a second look: someone else tried, or did, something to the account. */
const NOTABLE = new Set(["sign_in_failed", "password_reset", "pin_cleared"]);

export const describeEvent = (event: AccountEvent): string => EVENT_LABELS[event.kind] ?? event.kind;

const SHOWN_AT_FIRST = 8;

/**
 * Settings → Account → "Recent activity": the last 50 sign-ins, failed
 * attempts, TV pairings and password and PIN changes, from the last 90 days.
 */
export function ActivitySettings({ epoch = 0 }: { epoch?: number }) {
  const [events, setEvents] = useState<AccountEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.getActivity().then(
      (result) => {
        if (cancelled) return;
        setEvents(result.events);
        setError(null);
      },
      (err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Could not load recent activity.");
      }
    );
    return () => {
      cancelled = true;
    };
  }, [epoch]);

  if (error) {
    return <p role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle size={16} /> {error}</p>;
  }
  if (!events) {
    return (
      <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading…
      </p>
    );
  }
  if (events.length === 0) {
    return <p className="text-sm" style={{ color: "var(--text-mute)" }}>Nothing recorded yet.</p>;
  }

  const shown = showAll ? events : events.slice(0, SHOWN_AT_FIRST);
  return (
    <div className="space-y-2">
      <ul className="space-y-1.5" aria-label="Recent activity">
        {shown.map((event) => {
          const where = [
            event.userAgent ? describeUserAgent(event.userAgent) : null,
            event.ipAddress ? event.ipAddress : null,
          ]
            .filter(Boolean)
            .join(" · ");
          return (
            <li key={event.id} className="flex items-baseline justify-between gap-3 text-sm">
              <span className="min-w-0">
                <span className={NOTABLE.has(event.kind) ? "font-medium text-danger" : ""} style={NOTABLE.has(event.kind) ? undefined : { color: "var(--text)" }}>
                  {describeEvent(event)}
                </span>
                {event.detail && <span style={{ color: "var(--text-dim)" }}> · {event.detail}</span>}
                {where && <span className="block text-xs" style={{ color: "var(--text-mute)" }}>{where}</span>}
              </span>
              <time
                dateTime={event.createdAt}
                title={new Date(event.createdAt).toLocaleString()}
                className="flex-none text-xs tabular-nums"
                style={{ color: "var(--text-mute)" }}
              >
                {timeAgo(event.createdAt)}
              </time>
            </li>
          );
        })}
      </ul>
      {events.length > SHOWN_AT_FIRST && (
        <button type="button" onClick={() => setShowAll((v) => !v)} className="text-sm font-medium text-accent-text">
          {showAll ? "Show less" : `Show all ${events.length}`}
        </button>
      )}
      <p className="text-xs" style={{ color: "var(--text-mute)" }}>Kept for 90 days.</p>
    </div>
  );
}
