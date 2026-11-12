import { useEffect, useId, useRef, useState } from "react";
import { MoreHorizontal, Undo2, X } from "lucide-react";
import { useEscapeKey } from "../../hooks/useEscapeKey";

/**
 * The overflow beside the panel's primary action, holding what is done to a
 * show rarely: dropping it, or taking that back.
 *
 * Dropping was a section of its own above the episode list — a heading, two
 * lines of explanation and a button, for something done to a show once if ever.
 * It keeps its explanation here, where it costs a 50px square until asked for.
 *
 * A disclosure rather than `role="menu"`, as the lists control on this panel
 * is: one item does not need arrow-key navigation promised for it.
 */
export function MoreActionsMenu({
  isDropped,
  onToggleDrop,
}: {
  isDropped: boolean;
  onToggleDrop: () => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  // On the shared Escape stack, above the panel's own entry: Escape closes this
  // and leaves the panel open, with focus back on the button that opened it.
  useEscapeKey(() => {
    setOpen(false);
    triggerRef.current?.focus();
  }, open);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  return (
    <div ref={rootRef} className="relative flex-none">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="More actions"
        title="More actions"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className="btn-secondary h-[3.125rem] w-[3.125rem] rounded-2xl px-0"
      >
        <MoreHorizontal className="h-[1.125rem] w-[1.125rem]" />
      </button>
      {open && (
        <div
          id={panelId}
          role="group"
          aria-label="More actions"
          className="absolute right-0 z-30 mt-2 w-72 max-w-[calc(100vw-2.5rem)] overflow-hidden rounded-xl shadow-e2"
          style={{ background: "var(--bg-0)", border: "1px solid var(--border)" }}
        >
          {/* No confirm: dropping is reversible from the toast the panel raises
              and from this same item. */}
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              onToggleDrop();
            }}
            className="flex w-full items-start gap-3 px-3.5 py-3 text-left transition-colors hover:bg-[var(--surface)]"
          >
            {isDropped ? (
              <Undo2 className="mt-0.5 h-4 w-4 flex-none" style={{ color: "var(--text-dim)" }} aria-hidden="true" />
            ) : (
              <X className="mt-0.5 h-4 w-4 flex-none" style={{ color: "var(--text-dim)" }} aria-hidden="true" />
            )}
            <span className="min-w-0">
              <span className="block text-sm font-medium" style={{ color: "var(--text)" }}>
                {isDropped ? "Undrop this show" : "Drop this show"}
              </span>
              <span className="mt-0.5 block text-xs" style={{ color: "var(--text-mute)" }}>
                {isDropped
                  ? "Puts it back in Continue Watching and up-next suggestions."
                  : "Stops it appearing in Continue Watching and up-next suggestions."}
              </span>
            </span>
          </button>
        </div>
      )}
    </div>
  );
}
