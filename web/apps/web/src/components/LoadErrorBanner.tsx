/**
 * A refresh that failed on a page that still has something to show.
 *
 * The counterpart to `SectionError variant="page"`: that one is the whole
 * screen having nothing, this one sits above content that is still good — the
 * last answer, from the cache — and says the newer one didn't arrive. It never
 * replaces the page, because discarding what the user can already see to
 * report that we couldn't improve on it is the worse of the two outcomes.
 */
export function LoadErrorBanner({
  message,
  onRetry,
  className = "mb-5",
}: {
  message: string;
  onRetry: () => void;
  className?: string | undefined;
}) {
  return (
    <div
      role="alert"
      className={`flex flex-wrap items-center gap-3 rounded-xl px-4 py-3 text-sm text-danger ${className}`}
      style={{
        background: "color-mix(in srgb, var(--status-bad) 8%, transparent)",
        border: "1px solid color-mix(in srgb, var(--status-bad) 25%, transparent)",
      }}
    >
      <span className="flex-1">{message}</span>
      <button type="button" onClick={onRetry} className="btn-secondary btn-sm">
        Retry
      </button>
    </div>
  );
}
