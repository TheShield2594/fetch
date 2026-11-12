import { type ReactNode, useEffect, useRef } from "react";
import { Loader2, Trash2 } from "lucide-react";

/**
 * The one way a destructive action is confirmed: an inline panel under the
 * thing it acts on, saying what will happen, with the action named on its
 * button and Cancel beside it. It started as the profile delete review
 * (ProfileSettings.tsx); revoking an invite, removing an account, signing out
 * a TV and clearing data use it too, rather than the browser's own `confirm()`
 * or no confirmation at all.
 *
 * `children` goes between the message and the buttons: a password asked again,
 * an option such as "also sign out TVs".
 */
export function ConfirmReview({
  label,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
  busy = false,
  disabled = false,
  error,
  icon = <Trash2 size={14} />,
  tone = "danger",
  children,
  className = "mt-3 space-y-3 border-t pt-3",
}: {
  /** The panel's accessible name, as a question: "Remove alex?". */
  label: string;
  message: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  busy?: boolean;
  disabled?: boolean;
  error?: string | null | undefined;
  icon?: ReactNode;
  tone?: "danger" | "primary";
  children?: ReactNode;
  className?: string;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  // Callers swap the button that opened this panel for the panel itself, so
  // focus would otherwise fall to the page. It moves to the first field (a
  // password asked again) or else to Cancel — the safe choice under a
  // keyboard. On closing, focus goes back to the row the panel sat in, to the
  // control it opened from where that control is back, rather than to the top
  // of the page.
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const host = panel.parentElement;
    const first = panel.querySelector<HTMLElement>("input, select, textarea") ?? panel.querySelector<HTMLElement>("[data-confirm-cancel]");
    first?.focus();
    return () => {
      // After the caller has re-rendered without the panel.
      requestAnimationFrame(() => {
        const active = document.activeElement;
        if (active && active !== document.body) return;
        if (!host?.isConnected) return;
        host.querySelector<HTMLElement>("button:not([disabled]), a[href], input:not([disabled])")?.focus();
      });
    };
  }, []);

  return (
    <div ref={panelRef} role="alertdialog" aria-label={label} className={className} style={{ borderColor: "var(--border)" }}>
      <div className="text-sm" style={{ color: "var(--text)" }}>{message}</div>
      {children}
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onConfirm}
          disabled={busy || disabled}
          className={`${tone === "danger" ? "btn-danger" : "btn-primary"} btn-sm`}
        >
          {busy ? <Loader2 size={14} className="animate-spin" /> : icon} {confirmLabel}
        </button>
        <button type="button" onClick={onCancel} disabled={busy} data-confirm-cancel className="btn-secondary btn-sm">
          Cancel
        </button>
      </div>
    </div>
  );
}
