import { lazy, Suspense, type ComponentProps } from "react";
import { X } from "lucide-react";
// Type-only: a value import here would defeat the lazy() split below.
import type { DetailPanel as DetailPanelImpl } from "./media-detail/DetailPanel";
import { loadDetailPanel } from "../utils/routePrefetch";
import { useEscapeKey } from "../hooks/useEscapeKey";

export { useDetailPanel } from "./media-detail/useDetailPanel";

// The detail panel pulls in the whole `media-detail/` tree — seasons, cast,
// providers, recommendations, watch history, the rating and check-in modals — but
// none of it renders until a title is opened, so it stays out of the entry
// chunk.
//
// Suspense is local rather than borrowed from the router boundary in App.tsx:
// the one place this is rendered — `TitlePanelProvider`, at the shell — sits
// outside `<Routes>`, so there is no ancestor boundary there to catch the
// suspend.
//
// `loadDetailPanel` is the same loader the idle prefetch in App.tsx warms, which
// keeps the chunk ready most of the time. When it isn't — a slow connection, a
// tap before the idle prefetch ran — the fallback below holds the scrim and the
// panel's frame up from the first frame, so the tap visibly did something.
const LazyDetailPanel = lazy(() => loadDetailPanel().then((m) => ({ default: m.DetailPanel })));

/**
 * The panel's shape while its code arrives: the same scrim and surface the real
 * one opens with, a skeleton where the hero and sections will be, and a close
 * button that works — a tap on the wrong poster should not have to wait out the
 * download to be undone. Same idea as `ProfileSwitcherFallback`.
 */
function DetailPanelFallback({ name, onClose, staticScrim }: { name: string; onClose: () => void; staticScrim: boolean }) {
  useEscapeKey(onClose);
  return (
    <div
      className={`overlay-scrim fixed inset-0 z-50 flex items-center justify-center overflow-hidden sm:p-6 lg:p-0 ${staticScrim ? "" : "overlay-fade"}`}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={name}
        aria-busy="true"
        onClick={(e) => e.stopPropagation()}
        className="glass-surface overlay-panel relative flex h-full w-full max-h-screen flex-col overflow-hidden shadow-e3 sm:h-[36rem] sm:max-h-[88vh] sm:max-w-2xl sm:rounded-3xl sm:border lg:h-full lg:max-h-none lg:max-w-[110rem] lg:rounded-none lg:border-y-0"
        style={{ background: "var(--bg-0)", borderColor: "var(--border)" }}
      >
        <button
          type="button"
          onClick={onClose}
          className="tap-target bar-glass absolute right-4 top-4 z-30 flex h-9 w-9 items-center justify-center rounded-full transition-colors hover:text-[var(--text)] lg:right-6 lg:top-6"
          style={{ color: "var(--text-dim)" }}
          aria-label="Close detail panel"
        >
          <X className="h-4 w-4" />
        </button>
        <div aria-hidden="true" className="skeleton h-[42vh] max-h-[26rem] min-h-[15rem] w-full rounded-none sm:h-[22rem]" />
        <div aria-hidden="true" className="space-y-3 px-5 pt-6 sm:px-8 lg:mx-auto lg:w-full lg:max-w-[86rem] lg:px-12">
          <div className="skeleton h-8 w-2/3 max-w-md rounded-lg" />
          <div className="skeleton h-4 w-1/3 max-w-xs rounded" />
          <div className="skeleton mt-4 h-11 w-40 rounded-xl" />
        </div>
        <span className="sr-only">Loading {name}…</span>
      </div>
    </div>
  );
}

export function DetailPanel(props: ComponentProps<typeof DetailPanelImpl>) {
  return (
    <Suspense
      fallback={
        <DetailPanelFallback
          // A cold open names the panel by its id until the bundle arrives — not
          // a name worth reading out.
          name={props.item.name !== props.item.imdbId ? props.item.name : "Title"}
          onClose={props.onClose}
          staticScrim={props.staticScrim ?? false}
        />
      }
    >
      <LazyDetailPanel {...props} />
    </Suspense>
  );
}
