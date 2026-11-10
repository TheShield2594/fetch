import { useEffect, useRef } from "react";

let stack: Array<() => void> = [];

/**
 * True while any overlay registered below is open.
 *
 * The stack is the app's only record of what is layered over the page, so it is
 * also the only thing a global shortcut can ask before putting something else on
 * top. Used by the ⌘K handler, which otherwise opened the palette over an open
 * detail panel.
 */
export const hasOpenModalLayer = (): boolean => stack.length > 0;

// One document listener for the whole stack, there only while something is on
// it. Each hook instance used to add its own, so a single Escape ran the
// topmost handler once per mounted instance — harmless only while every
// handler happened to be idempotent.
const onKeyDown = (e: KeyboardEvent) => {
  // Topmost handler wins: the innermost overlay is the one Escape closes.
  const innermost = stack.at(-1);
  if (e.key !== "Escape" || !innermost) return;
  innermost();
};

const push = (entry: () => void) => {
  if (stack.length === 0) document.addEventListener("keydown", onKeyDown);
  stack.push(entry);
};

const remove = (entry: () => void) => {
  stack = stack.filter((e) => e !== entry);
  if (stack.length === 0) document.removeEventListener("keydown", onKeyDown);
};

/**
 * Registers `onEscape` for the Escape key, but only the most recently
 * mounted handler (i.e. the topmost modal in a stack) actually fires.
 * Prevents nested modals (e.g. a sub-modal opened on top of a detail panel)
 * from all closing at once on a single Escape press.
 */
export function useEscapeKey(onEscape: () => void, active = true) {
  const callbackRef = useRef(onEscape);
  useEffect(() => {
    callbackRef.current = onEscape;
  });

  useEffect(() => {
    if (!active) return;
    const entry = () => callbackRef.current();
    push(entry);
    return () => remove(entry);
  }, [active]);
}
