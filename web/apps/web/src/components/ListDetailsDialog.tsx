import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { X } from "lucide-react";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { useExitAnimation } from "../hooks/useExitAnimation";
import { useFocusTrap } from "../hooks/useFocusTrap";
import { useScrollLock } from "../hooks/useScrollLock";
import { useVisualViewport } from "../hooks/useVisualViewport";
import { SECTION_TITLE } from "./typography";

/** What the API keeps of a description; the field stops there rather than the save failing. */
export const LIST_DESCRIPTION_MAX = 280;

/**
 * A list's name and description, in a small dialog: "New list" on the lists
 * index, and "Edit details" inside a list.
 *
 * Creating a list used to be a text field and a button that sat on the Lists
 * page permanently, a whole row of a phone's first screen spent on something
 * done once in a while. It is a button now, and this is what the button opens.
 * The chrome — scrim, focus trap, Escape, exit animation, the keyboard-aware
 * viewport — is the same set every other dialog in the app is built from.
 */
export function ListDetailsDialog({
  title,
  submitLabel,
  initialName = "",
  initialDescription = "",
  onSubmit,
  onClose,
}: {
  title: string;
  submitLabel: string;
  initialName?: string | undefined;
  initialDescription?: string | undefined;
  /** Rejects with the reason to show; the dialog stays open for another try. Resolves → it closes. */
  onSubmit: (details: { name: string; description: string }) => Promise<void>;
  onClose: () => void;
}) {
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);
  const viewportStyle = useVisualViewport();
  const titleId = useId();
  const nameId = useId();
  const descriptionId = useId();

  useScrollLock();
  useEscapeKey(requestClose);

  useEffect(() => {
    nameRef.current?.focus();
    nameRef.current?.select();
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ name: trimmed, description: description.trim() });
      requestClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't save the list");
      setSaving(false);
    }
  };

  const fieldStyle = { borderColor: "var(--border-field)", color: "var(--text)", background: "var(--bg-1)" };

  return (
    <div
      className={`overlay-scrim overlay-fade fixed inset-0 z-50 flex items-start justify-center overflow-y-auto p-4 sm:items-center ${exiting ? "overlay-exit" : ""}`}
      style={viewportStyle}
      onClick={requestClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        inert={exiting}
        className={`glass-surface overlay-dialog w-full max-w-sm rounded-3xl border p-6 shadow-e3 ${exiting ? "overlay-exit" : ""}`}
        style={{ borderColor: "var(--border)", background: "var(--bg-0)" }}
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={onExitAnimationEnd}
      >
        <div className="mb-5 flex items-center justify-between">
          <h3 id={titleId} className={SECTION_TITLE} style={{ color: "var(--text)" }}>{title}</h3>
          <button
            type="button"
            onClick={requestClose}
            aria-label="Close"
            className="rounded-lg p-1 hover:opacity-80"
            style={{ color: "var(--text-mute)" }}
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <form onSubmit={(e) => void submit(e)} className="space-y-4">
          <div>
            <label htmlFor={nameId} className="mb-1 block text-xs font-medium" style={{ color: "var(--text-dim)" }}>
              Name
            </label>
            <input
              ref={nameRef}
              id={nameId}
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-xl border px-3.5 py-2.5 text-sm field-control"
              style={fieldStyle}
            />
          </div>
          <div>
            <label htmlFor={descriptionId} className="mb-1 block text-xs font-medium" style={{ color: "var(--text-dim)" }}>
              Description <span style={{ color: "var(--text-mute)" }}>(optional)</span>
            </label>
            <textarea
              id={descriptionId}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={LIST_DESCRIPTION_MAX}
              rows={3}
              className="w-full resize-none rounded-xl border px-3.5 py-2.5 text-sm field-control"
              style={fieldStyle}
            />
            <p className="meta mt-1 text-right" style={{ color: "var(--text-mute)" }} aria-live="polite">
              {description.length > LIST_DESCRIPTION_MAX - 40 ? `${LIST_DESCRIPTION_MAX - description.length} left` : ""}
            </p>
          </div>
          {error && (
            <p role="alert" className="rounded-lg border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-xs text-danger">{error}</p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={requestClose} className="btn-secondary">Cancel</button>
            <button type="submit" disabled={!name.trim() || saving} className="btn-primary">
              {saving ? "Saving…" : submitLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
