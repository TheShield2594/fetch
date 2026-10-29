import { type DragEvent, useState } from "react";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";
import { ApiError } from "../../api";

/**
 * What the TV panels share: moving a row within a list, by drag or by button,
 * and reading the errors their saves can end in.
 *
 * Reordering has two inputs on purpose. Dragging is what a long list wants with
 * a mouse; the up and down buttons are what a keyboard, a screen reader and a
 * phone (where HTML drag-and-drop doesn't fire) get, and each says which row it
 * moves.
 */

/** A copy of `items` with the entry at `from` moved to `to`. Out-of-range moves return the list unchanged. */
export function moveItem<T>(items: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return [...items];
  const next = [...items];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved as T);
  return next;
}

/**
 * Props for each row of a drag-reorderable list. `onMove(from, to)` runs on
 * drop; `dragOverIndex` is where the dragged row would land, for styling.
 */
export function useDragReorder(onMove: (from: number, to: number) => void) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  const rowProps = (index: number) => ({
    draggable: true,
    onDragStart: (event: DragEvent) => {
      setDragIndex(index);
      event.dataTransfer.effectAllowed = "move";
      // Firefox starts no drag without some data set.
      event.dataTransfer.setData("text/plain", String(index));
    },
    onDragOver: (event: DragEvent) => {
      if (dragIndex === null) return;
      event.preventDefault();
      setDragOverIndex(index);
    },
    onDrop: (event: DragEvent) => {
      event.preventDefault();
      if (dragIndex !== null) onMove(dragIndex, index);
      setDragIndex(null);
      setDragOverIndex(null);
    },
    onDragEnd: () => {
      setDragIndex(null);
      setDragOverIndex(null);
    },
  });

  return { rowProps, dragIndex, dragOverIndex };
}

const iconButton =
  "rounded-lg p-1.5 hover:bg-[var(--surface-strong)] disabled:opacity-30 disabled:hover:bg-transparent";

/** The grip and the move-up / move-down buttons at the start of a row. */
export function ReorderControls({
  index,
  count,
  label,
  disabled = false,
  onMove,
}: {
  index: number;
  count: number;
  /** What the row is called, for the buttons' accessible names. */
  label: string;
  disabled?: boolean;
  onMove: (from: number, to: number) => void;
}) {
  return (
    <span className="flex flex-none items-center" style={{ color: "var(--text-mute)" }}>
      <GripVertical size={16} aria-hidden="true" className="cursor-grab" />
      <button
        type="button"
        className={iconButton}
        disabled={disabled || index === 0}
        onClick={() => onMove(index, index - 1)}
        aria-label={`Move ${label} up`}
        title="Move up"
      >
        <ArrowUp size={14} />
      </button>
      <button
        type="button"
        className={iconButton}
        disabled={disabled || index === count - 1}
        onClick={() => onMove(index, index + 1)}
        aria-label={`Move ${label} down`}
        title="Move down"
      >
        <ArrowDown size={14} />
      </button>
    </span>
  );
}

/** True for the 412 a versioned save gets when someone else saved first. */
export const isVersionConflict = (error: unknown): boolean =>
  error instanceof ApiError && (error.status === 412 || error.code === "version_conflict");

export const errorMessage = (error: unknown, fallback: string): string =>
  error instanceof Error && error.message ? error.message : fallback;

export const inputClass =
  "w-full rounded-xl border px-4 py-2.5 text-sm field-control";
export const inputStyle = { borderColor: "var(--border-strong)", color: "var(--text)", background: "var(--bg-1)" };
export const labelClass = "mb-1 block text-xs font-medium";
export const labelStyle = { color: "var(--text-dim)" };
export const rowClass = "rounded-xl border p-3";
export const rowStyle = { borderColor: "var(--border)", background: "var(--bg-1)" };
