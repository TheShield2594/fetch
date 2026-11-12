import type { ReactNode } from "react";
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
  return (
    <div role="alertdialog" aria-label={label} className={className} style={{ borderColor: "var(--border)" }}>
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
        <button type="button" onClick={onCancel} disabled={busy} className="btn-secondary btn-sm">
          Cancel
        </button>
      </div>
    </div>
  );
}
