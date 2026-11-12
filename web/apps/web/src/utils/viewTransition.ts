/*
 * Poster-to-panel continuity when a title opens.
 *
 * Opening a title was a hard cut: a scrim faded and a card popped from 0.97,
 * and the poster that was tapped had no relationship to the header that
 * appeared. Where the browser has the View Transitions API, the tapped art is
 * named `title-art` for the old snapshot, the panel names its own art the same
 * (`.vt-title-hero` / `.vt-title-poster` in index.css), and the browser morphs
 * one into the other. Everywhere else — and under reduced motion — the open
 * happens exactly as before and the overlay's own pop plays.
 */

const NAME = "title-art";
/** How long the new state may take to draw its art before the morph gives up and crossfades. */
const WAIT_MS = 400;
/** The panel marks the art it wants the morph to land on with this attribute. */
export const TITLE_ART_ATTR = "data-title-art";

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => Promise<void> | void) => { finished: Promise<void> };
};

let running = false;

function reducedMotion(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Resolves once an element carrying the panel's art marker is in the document, or after `WAIT_MS`. */
function waitForTarget(): Promise<void> {
  return new Promise((resolve) => {
    const start = performance.now();
    const tick = () => {
      if (document.querySelector(`[${TITLE_ART_ATTR}]`) || performance.now() - start > WAIT_MS) resolve();
      else requestAnimationFrame(tick);
    };
    tick();
  });
}

/**
 * Runs `open` — something that opens the title panel — as a view transition
 * from `source`, the art of the card that was tapped. Falls back to calling
 * `open` directly.
 */
export function openWithTransition(source: HTMLElement | null, open: () => void): void {
  const doc = document as ViewTransitionDocument;
  // A panel already open is a hop between titles, not a card becoming a page.
  const panelOpen = document.querySelector(`[${TITLE_ART_ATTR}]`) !== null;
  if (!source || running || panelOpen || typeof doc.startViewTransition !== "function" || reducedMotion()) {
    open();
    return;
  }
  running = true;
  const root = document.documentElement;
  source.style.setProperty("view-transition-name", NAME);
  root.classList.add("vt-title-open");
  try {
    const transition = doc.startViewTransition(async () => {
      open();
      // The navigation is a router transition and the panel mounts a commit
      // after it, so the new snapshot waits for the panel rather than for a
      // flush. The source gives its name up before that snapshot: two elements
      // named alike would abort the whole transition.
      await waitForTarget();
      source.style.removeProperty("view-transition-name");
    });
    const done = () => {
      source.style.removeProperty("view-transition-name");
      root.classList.remove("vt-title-open");
      running = false;
    };
    // `finished` rejects when the browser skips the transition; either way the
    // page is in its new state and only the bookkeeping is left.
    transition.finished.then(done, done);
  } catch {
    source.style.removeProperty("view-transition-name");
    root.classList.remove("vt-title-open");
    running = false;
    open();
  }
}
