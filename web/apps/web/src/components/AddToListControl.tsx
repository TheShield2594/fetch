import { type ReactNode, useCallback, useEffect, useId, useRef, useState } from "react";
import { Check, ListPlus, Plus } from "lucide-react";
import type { CatalogList } from "../api";
import { type ListMembership, type ListTitle, useListMembership } from "../hooks/useListMembership";
import type { ShowToast } from "../hooks/useToast";

/**
 * The add-to-list control: a trigger and a panel listing the profile's lists,
 * each a toggle with a tick for the lists the title is already in, and a
 * "New list…" row that makes one and adds the title to it.
 *
 * Search's cards and the title panel both use it; the Lists page's own Add
 * dialog goes through the same `useListMembership` underneath. See that hook
 * for why there is one of these and not three.
 *
 * Deliberately not role="menu" — the decision both earlier copies had already
 * reached. The panel holds a text field and a submit button for the inline
 * create flow, which the ARIA menu pattern doesn't allow, and announcing a
 * menu promises arrow-key navigation and a roving tabindex. It is a
 * disclosure: the trigger says aria-expanded/aria-controls, the rows stay in
 * the tab order, each toggle says aria-pressed, Escape closes and puts focus
 * back on the trigger, and focus leaving the panel closes it.
 */

export type AddToListProps = {
  title: ListTitle;
  /** The profile's lists, in the order to offer them. */
  lists: readonly CatalogList[];
  /** IDs of the lists the title is in. */
  memberIds: readonly string[];
  /** Called optimistically on a toggle, and again with the opposite value if it fails. */
  onMemberChange: (listId: string, member: boolean) => void;
  /** A list made from the panel. Called even when the add into it then fails. */
  onListCreated: (list: CatalogList) => void;
  notify: ShowToast;
  /** Share a caller's instance when it also toggles membership outside the panel. */
  membership?: ListMembership | undefined;
  /** Lists not to offer here — the title panel gives the watchlist its own button. */
  hiddenListIds?: readonly string[] | undefined;
  /** "icon": a round + over a poster. "pill": a labelled button in a row of chips. */
  variant?: "icon" | "pill";
  /** Classes placing the panel. Defaults to opening below the trigger, left-aligned. */
  panelClassName?: string | undefined;
};

/**
 * The control as two pieces, for a caller that has to put them in different
 * places — a poster card draws its trigger over the art and the panel under
 * the caption, where it has the card's width instead of the button's.
 */
export function useAddToList({
  title,
  lists,
  memberIds,
  onMemberChange,
  onListCreated,
  notify,
  membership: sharedMembership,
  hiddenListIds,
  variant = "icon",
  panelClassName = "absolute left-0 top-full z-30 mt-1 w-56",
}: AddToListProps): { trigger: ReactNode; panel: ReactNode; open: boolean } {
  const ownMembership = useListMembership(notify);
  const membership = sharedMembership ?? ownMembership;
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newListName, setNewListName] = useState("");
  const [savingNewList, setSavingNewList] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const createButtonRef = useRef<HTMLButtonElement>(null);
  const restoreCreateFocus = useRef(false);
  const panelId = useId();

  const close = useCallback(() => {
    setOpen(false);
    setCreating(false);
    setNewListName("");
  }, []);

  const closeAndFocusTrigger = useCallback(() => {
    close();
    triggerRef.current?.focus();
  }, [close]);

  // Focus moves into the panel on open: without it a keyboard user is left on
  // the trigger with the panel hanging open behind them.
  useEffect(() => {
    if (!open) return;
    panelRef.current?.querySelector<HTMLElement>("button:not([disabled]), input")?.focus();
  }, [open]);

  // A press anywhere outside the trigger and the panel closes it. The trigger
  // is exempt so that its own click can be the thing that closes the panel,
  // rather than a mousedown closing it and the click reopening it.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return;
      close();
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open, close]);

  // Backing out of the create field unmounts the focused input; focus goes to
  // the row that replaces it, or the panel would be left with nothing focused
  // and the blur check below would close it.
  useEffect(() => {
    if (creating || !restoreCreateFocus.current) return;
    restoreCreateFocus.current = false;
    createButtonRef.current?.focus();
  }, [creating]);

  // Checked on the next frame rather than off relatedTarget alone: swapping
  // the create row for its field unmounts the focused button and fires a
  // focusout with nowhere to go before the field mounts to take focus.
  const handlePanelBlur = (event: React.FocusEvent) => {
    const next = event.relatedTarget as Node | null;
    if (event.currentTarget.contains(next) || (next && next === triggerRef.current)) return;
    requestAnimationFrame(() => {
      const active = document.activeElement;
      if (active === triggerRef.current) return;
      if (panelRef.current && !panelRef.current.contains(active)) close();
    });
  };

  const offered = hiddenListIds ? lists.filter((list) => !hiddenListIds.includes(list.id)) : lists;

  const toggle = (list: CatalogList) => {
    if (membership.isPending(list.id, title)) return;
    const member = !memberIds.includes(list.id);
    void membership.setMembership(list, title, member, (value) => onMemberChange(list.id, value));
    // One tap, one change: the panel goes once the choice is made, as both
    // earlier copies did. The toast says what happened, and carries the Undo.
    closeAndFocusTrigger();
  };

  const submitNewList = async () => {
    if (!newListName.trim() || savingNewList) return;
    setSavingNewList(true);
    try {
      await membership.createListWithTitle(newListName, title, { onCreated: onListCreated, apply: onMemberChange });
    } finally {
      setSavingNewList(false);
      closeAndFocusTrigger();
    }
  };

  const label = `Add ${title.name} to a list`;
  const trigger =
    variant === "icon" ? (
      <button
        ref={triggerRef}
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          if (open) close();
          else setOpen(true);
        }}
        className="pointer-events-auto flex h-8 w-8 items-center justify-center rounded-full bg-accent-500 text-accent-on shadow-e2 transition-[transform,background-color] duration-base hover:bg-accent-600 active:scale-95 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
      >
        <Plus className="h-4 w-4" strokeWidth={3} />
      </button>
    ) : (
      <button
        ref={triggerRef}
        type="button"
        onClick={() => (open ? close() : setOpen(true))}
        className="inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors hover:text-[var(--text)]"
        style={{ borderColor: "var(--border-strong)", color: "var(--text-dim)" }}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
      >
        <ListPlus className="h-3 w-3" /> Add to list
      </button>
    );

  const divider = { borderTopWidth: offered.length > 0 ? 1 : 0, borderTopStyle: "solid", borderTopColor: "var(--border)" } as const;

  const panel = open ? (
    <div
      ref={panelRef}
      id={panelId}
      role="group"
      aria-label={label}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          closeAndFocusTrigger();
        }
      }}
      onBlur={handlePanelBlur}
      onClick={(event) => event.stopPropagation()}
      className={`pointer-events-auto max-h-80 overflow-y-auto overscroll-contain rounded-2xl shadow-e2 ${panelClassName}`}
      style={{ background: "var(--bg-0)", borderWidth: 1, borderStyle: "solid", borderColor: "var(--border)" }}
    >
      {offered.length === 0 && !creating && (
        <p className="px-3 py-2.5 text-2xs" style={{ color: "var(--text-mute)" }}>
          No lists yet — create one below.
        </p>
      )}
      {offered.map((list) => {
        const member = memberIds.includes(list.id);
        const pending = membership.isPending(list.id, title);
        return (
          <button
            key={list.id}
            type="button"
            disabled={pending}
            aria-pressed={member}
            onClick={() => toggle(list)}
            className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm transition-colors hover:bg-[var(--surface)] disabled:opacity-50"
          >
            {member ? (
              <Check className="h-3.5 w-3.5 flex-none text-success" aria-hidden="true" />
            ) : (
              <Plus className="h-3.5 w-3.5 flex-none" style={{ color: "var(--text-mute)" }} aria-hidden="true" />
            )}
            <span className="min-w-0 flex-1 truncate" style={{ color: "var(--text-dim)" }}>{list.name}</span>
            {pending && (
              <span
                aria-hidden="true"
                className="inline-block h-3 w-3 flex-none animate-spin rounded-full border border-accent-500 border-t-transparent"
              />
            )}
          </button>
        );
      })}
      {creating ? (
        <div className="flex items-center gap-2 px-3 py-2.5" style={divider}>
          <input
            autoFocus
            value={newListName}
            onChange={(event) => setNewListName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void submitNewList();
              }
              // Escape here backs out of the field only; the panel's own
              // handler would otherwise close everything in the same keystroke.
              if (event.key === "Escape") {
                event.stopPropagation();
                setCreating(false);
                setNewListName("");
                restoreCreateFocus.current = true;
              }
            }}
            placeholder="List name…"
            aria-label="New list name"
            className="min-w-0 flex-1 rounded-md px-2 py-1 text-sm field-control"
            style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-1)", color: "var(--text)" }}
          />
          <button
            type="button"
            disabled={!newListName.trim() || savingNewList}
            onClick={() => void submitNewList()}
            className="btn-primary btn-xs flex-none"
          >
            {savingNewList ? "…" : "Add"}
          </button>
        </div>
      ) : (
        <button
          ref={createButtonRef}
          type="button"
          onClick={() => setCreating(true)}
          className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm text-accent-text transition-colors hover:bg-[var(--surface)]"
          style={divider}
        >
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          New list…
        </button>
      )}
    </div>
  ) : null;

  return { trigger, panel, open };
}

/** The control with its panel hung off the trigger. */
export function AddToListControl(props: AddToListProps) {
  const { trigger, panel } = useAddToList(props);
  return (
    <div className="relative inline-flex">
      {trigger}
      {panel}
    </div>
  );
}
