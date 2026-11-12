import { useId, useState } from "react";
import { AlertCircle, Check, Circle, Eye, EyeOff } from "lucide-react";

/** The server's rule for a new password (lib/account-admin.ts). */
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 256;

export const PASSWORD_INPUT_CLASS =
  "w-full rounded-xl border border-[var(--border-field)] bg-[var(--bg-0)] px-4 py-3 text-sm text-[var(--text)] field-control";
export const FIELD_LABEL_CLASS = "block text-xs font-semibold";

/** Why a new password would be refused, or null when it wouldn't be. */
export function newPasswordProblem(password: string): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  if (password.length > MAX_PASSWORD_LENGTH) return `Use at most ${MAX_PASSWORD_LENGTH} characters.`;
  return null;
}

/** The message under a field that failed validation, read out as its description. */
export function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null;
  return (
    <p id={id} className="mt-1.5 flex items-center gap-1.5 text-xs text-danger">
      <AlertCircle size={14} aria-hidden="true" /> {message}
    </p>
  );
}

/**
 * A labelled password field with a show/hide toggle. The label stays above the
 * field while you type, unlike a placeholder.
 *
 * `newPassword` shows the length rule under the field and ticks it as it is
 * met, so the rule is known before submitting rather than reported after.
 */
export function PasswordField({
  label,
  value,
  onChange,
  autoComplete,
  error,
  inputRef,
  newPassword = false,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete: string;
  error?: string | undefined;
  inputRef?: React.Ref<HTMLInputElement>;
  newPassword?: boolean;
  autoFocus?: boolean;
}) {
  const [visible, setVisible] = useState(false);
  const id = useId();
  const errorId = `${id}-error`;
  const ruleId = `${id}-rule`;
  const ruleMet = value.length >= MIN_PASSWORD_LENGTH;
  const describedBy = [newPassword ? ruleId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  return (
    <div>
      <label htmlFor={id} className={FIELD_LABEL_CLASS} style={{ color: "var(--text)" }}>
        {label}
      </label>
      <div className="relative mt-1.5">
        <input
          id={id}
          ref={inputRef}
          type={visible ? "text" : "password"}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          autoComplete={autoComplete}
          // eslint-disable-next-line jsx-a11y/no-autofocus -- only where the field is what was just opened
          autoFocus={autoFocus}
          maxLength={MAX_PASSWORD_LENGTH}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={`${PASSWORD_INPUT_CLASS} pr-12`}
        />
        <button
          type="button"
          onClick={() => setVisible((p) => !p)}
          className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1.5 text-[var(--text-mute)] transition-colors hover:bg-[var(--surface-strong)] hover:text-[var(--text)]"
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
          aria-pressed={visible}
        >
          {visible ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
      {newPassword && (
        <p
          id={ruleId}
          className={`mt-1.5 flex items-center gap-1.5 text-xs ${ruleMet ? "text-success" : ""}`}
          style={ruleMet ? undefined : { color: "var(--text-mute)" }}
        >
          {ruleMet ? <Check size={14} aria-hidden="true" /> : <Circle size={14} aria-hidden="true" />}
          At least {MIN_PASSWORD_LENGTH} characters
        </p>
      )}
      <FieldError id={errorId} message={error} />
    </div>
  );
}
