import { useRef } from "react";

/*
 * The segmented control.
 *
 * One exclusive choice out of two to five peers, all of them visible at once —
 * the kind filter on the Shelf, the tab strip in Settings, the range picker on
 * Stats. It replaces the row of independently-filling pills each of those had
 * built for itself.
 *
 * Two things make it the platform's control rather than a restyled pill row:
 *
 *   The thumb slides. There is one raised surface and it moves to the option
 *   you picked, which is the only animation in the component and the only place
 *   its state lives. Four pills filling and emptying in place say the same
 *   thing and say it as a jump cut.
 *
 *   The options are equal-width columns. A segmented control is a fixed piece
 *   of furniture — the selection changes, the geometry doesn't — so the thumb's
 *   position is arithmetic (`index * 100%` of a `100/count` percent slot) and
 *   nothing is measured. No ResizeObserver, nothing to re-measure on rotation,
 *   and it is correct in the first frame after a hydration.
 *
 * Shape and paint are in `src/index.css` (`.segmented`); this file owns the
 * geometry and the semantics.
 */

export type SegmentedOption<T extends string> = {
  value: T;
  label: string;
  /**
   * What the option announces, where the visible label isn't the whole story —
   * "Shows, 12 titles". The visible label has to be a prefix of it, which is
   * what SC 2.5.3 asks of any control whose name is longer than its text.
   */
  ariaLabel?: string;
  /** Selectable but empty — dimmed, and skipped by the keyboard. */
  disabled?: boolean;
};

/**
 * What the options are, to assistive tech and to the keyboard.
 *
 *   toggles  (the default) a named group of `aria-pressed` buttons, each its
 *            own Tab stop. Right for a filter, which has no panel to point at.
 *   tabs     a real `tablist`: `role="tab"` with `aria-selected`, one Tab stop
 *            (roving tabindex), the arrow keys and Home/End moving and
 *            selecting in one step, and `aria-controls` naming the panel. Right
 *            for a strip that switches what a panel below it shows — Settings.
 *            The caller renders the panel with `id={panelId}` and
 *            `aria-labelledby={segmentedTabId(idPrefix, value)}`.
 */
export type SegmentedSemantics =
  | { kind: "toggles" }
  | { kind: "tabs"; idPrefix: string; panelId: string };

/** The id a `tabs`-mode option carries, for the panel's `aria-labelledby`. */
export const segmentedTabId = (idPrefix: string, value: string) => `${idPrefix}-tab-${value}`;

export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  label,
  className = "",
  semantics = { kind: "toggles" },
}: {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Names the group. A control with three unnamed toggles in it is three unnamed toggles. */
  label: string;
  className?: string;
  semantics?: SegmentedSemantics | undefined;
}) {
  const index = options.findIndex((option) => option.value === value);
  const groupRef = useRef<HTMLDivElement>(null);
  const tabs = semantics.kind === "tabs" ? semantics : null;

  /*
   * Automatic activation: an arrow moves focus and selects in one step, which
   * the tabs pattern recommends when switching panels is cheap. Focus is moved
   * by hand, because with a roving tabindex the newly selected tab is the only
   * one that can hold it, and it doesn't get it merely by being re-rendered.
   * Disabled options are skipped.
   */
  const onTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    const enabled = options.map((o, i) => ({ o, i })).filter(({ o }) => !o.disabled || o.value === value);
    if (enabled.length === 0) return;
    const at = Math.max(0, enabled.findIndex(({ o }) => o.value === value));
    const deltas: Record<string, number> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    const delta = deltas[event.key];
    let next: number;
    if (delta !== undefined) next = (at + delta + enabled.length) % enabled.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = enabled.length - 1;
    else return;
    const target = enabled[next];
    if (!target) return;
    event.preventDefault();
    onChange(target.o.value);
    groupRef.current?.querySelectorAll<HTMLElement>('[role="tab"]')[target.i]?.focus();
  };

  return (
    /*
     * In `toggles` mode, a named group of toggle buttons rather than a
     * `tablist`: the tabs pattern is a promise of a roving tabindex, arrow
     * keys and a `tabpanel` each tab controls, and a filter like the Shelf's
     * could not honestly have the third — it filters two sections at once.
     */
    <div ref={groupRef} role={tabs ? "tablist" : "group"} aria-label={label} className={`segmented ${className}`}>
      {/*
       * Hidden from assistive tech and from the layout both: the thumb is the
       * visual form of the selection, and it is positioned rather than laid
       * out so it can't push the options it sits behind.
       *
       * Held off-screen rather than unmounted when nothing matches (a filter
       * the URL named that no longer exists), so the first real selection
       * slides in from the edge rather than appearing under the option.
       */}
      <span
        aria-hidden="true"
        className="segmented-thumb"
        style={{
          // The track's 2px padding is inside the percentage — an absolutely
          // positioned child measures against the padding box — so it comes off
          // the total before the slot is divided out, not after.
          width: `calc((100% - 4px) / ${options.length})`,
          transform: `translateX(${index < 0 ? -110 : index * 100}%)`,
          opacity: index < 0 ? 0 : 1,
        }}
      />
      {options.map((option) => {
        const selected = option.value === value;
        // A selected option is never disabled: the control would then have
        // no way back to itself, and the dimming would read as a fault.
        const disabled = option.disabled && !selected;
        const className =
          "segmented-option tap-target truncate focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset";
        return tabs ? (
          <button
            key={option.value}
            type="button"
            role="tab"
            id={segmentedTabId(tabs.idPrefix, option.value)}
            aria-selected={selected}
            aria-controls={tabs.panelId}
            aria-label={option.ariaLabel}
            // Roving tabindex: the tablist is one Tab stop, and the arrow keys
            // move within it.
            tabIndex={selected ? 0 : -1}
            disabled={disabled}
            onKeyDown={onTabKeyDown}
            onClick={() => onChange(option.value)}
            className={className}
          >
            {option.label}
          </button>
        ) : (
          <button
            key={option.value}
            type="button"
            aria-pressed={selected}
            aria-label={option.ariaLabel}
            disabled={disabled}
            onClick={() => onChange(option.value)}
            className={className}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
