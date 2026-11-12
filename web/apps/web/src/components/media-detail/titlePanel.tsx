import { Component, createContext, useCallback, useContext, useEffect, useMemo, useRef, type ErrorInfo, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import type { MediaType, SearchResult } from "../../api";
import { useToast } from "../../hooks/useToast";
import { DetailPanel, useDetailPanel } from "../MediaDetailPanel";
import { captureException } from "../../sentry";

/*
 * The address of a title.
 *
 * The detail panel is the central surface of the app and it used to have no
 * URL: its state was a `useState` inside whichever page had opened it, mounted
 * once per page. Back did not close it, a recommendation hop left no trail, and
 * a title could not be bookmarked, reloaded, or linked from a notification.
 *
 * It is a search parameter rather than a route of its own because the panel is
 * an overlay: the page underneath is still the page, and `/calendar?title=…`
 * says both things — where you are and what is open on top of it. A path such
 * as `/title/series/tt123` would have to pick a page to draw behind a cold
 * load, and would unmount the one that was there on every open.
 */
export const TITLE_PARAM = "title";

export type TitleRef = { type: MediaType; imdbId: string };

/** `series:tt0903747` — the type leads so an id containing a colon still parses. */
export const titleParamValue = (ref: TitleRef): string => `${ref.type}:${ref.imdbId}`;

export function parseTitleParam(value: string | null): TitleRef | null {
  if (!value) return null;
  const at = value.indexOf(":");
  if (at < 0) return null;
  const type = value.slice(0, at);
  const imdbId = value.slice(at + 1).trim();
  if ((type !== "movie" && type !== "series") || !imdbId) return null;
  return { type, imdbId };
}

/** A link to `pathname` with a title open over it — for anything that isn't a click handler. */
export function titleHref(pathname: string, ref: TitleRef): string {
  return `${pathname}?${new URLSearchParams({ [TITLE_PARAM]: titleParamValue(ref) })}`;
}

/**
 * What a history entry remembers about the panel that is open on it.
 *
 * `titleDepth` counts the title entries stacked since the panel was opened, so
 * the close button can leave all of them at once — Back walks the
 * recommendation hops one at a time, Close gets out. An entry with no depth is
 * one the app did not push (a cold load, a pasted link), and closing it
 * rewrites the URL in place instead: there may be nothing behind it to go back
 * to, and `navigate(-1)` from the first entry of a tab leaves the app.
 */
type TitleHistoryState = {
  titleDepth?: number;
  /** Opened over a scrim someone else already has up — see `DetailPanel.staticScrim`. */
  titleScrim?: boolean;
};

const readState = (state: unknown): TitleHistoryState =>
  state && typeof state === "object" ? (state as TitleHistoryState) : {};

type OpenOptions = {
  /** The opener already has a scrim on screen and takes it back on close. */
  overScrim?: boolean;
};

type TitlePanelApi = {
  /** Opens a title: pushes a history entry, so Back closes it again. */
  openTitle: (item: SearchResult, options?: OpenOptions) => void;
  /** The title the URL names right now, or `null`. */
  current: (TitleRef & { overScrim: boolean }) | null;
};

/*
 * The default is inert rather than a throw. A page rendered on its own — which
 * is every page test — has no shell above it, and opening a title there has
 * nowhere to draw one; that is a no-op, not a crash.
 */
const TitlePanelContext = createContext<TitlePanelApi>({ openTitle: () => {}, current: null });

/** Opens titles in the panel the shell mounts, and says which one is open. */
export function useTitlePanel(): TitlePanelApi {
  return useContext(TitlePanelContext);
}

/**
 * Runs `onClose` when the panel goes from open to closed.
 *
 * For a page whose own data the panel can change — marking an episode watched
 * from the panel moves the Discover page's progress rows. The panel used to be
 * that page's child and took an `onClose` prop; now it belongs to the shell and
 * the page listens instead.
 */
export function useOnTitleClose(onClose: () => void): void {
  const { current } = useTitlePanel();
  const open = current !== null;
  const wasOpen = useRef(open);
  const callback = useRef(onClose);
  callback.current = onClose;
  useEffect(() => {
    if (wasOpen.current && !open) callback.current();
    wasOpen.current = open;
  }, [open]);
}

// A cold load knows a type and an id and nothing else. The id stands in for
// the name, which is the placeholder `useDetailPanel` already recognises and
// replaces as soon as the bundle lands.
const placeholderItem = (ref: TitleRef): SearchResult => ({
  imdbId: ref.imdbId,
  type: ref.type,
  name: ref.imdbId,
  year: null,
  poster: null,
  description: null,
  genres: [],
  rating: null,
  inWatchlist: false,
  inCollection: false,
  lists: [],
});

/**
 * Contains a panel that throws while rendering.
 *
 * The panel used to be a child of the routed page, inside the page's error
 * boundary. Mounted at the shell it has nothing above it but the app-wide one,
 * whose fallback replaces the whole window — so one bad response in a title's
 * data would take the navigation down with it. Here the panel is dropped, the
 * failure is reported, and the caller is told so it can close the address that
 * named it. Keyed by the title, so the next one opened starts clean.
 */
class PanelBoundary extends Component<{ onError: () => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error);
    captureException(error, { contexts: { react: { componentStack: info.componentStack } } });
    this.props.onError();
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

// What a card knew about a title when it was clicked — name, poster, year — so
// the panel opens already populated rather than as an id waiting on a request.
// Bounded: a long session opens a lot of titles.
const SEED_LIMIT = 80;

/**
 * Owns the one detail panel in the app and the URL that addresses it.
 *
 * Mounted once at the shell. Every page, and the command palette, used to mount
 * its own copy and its own `useDetailPanel`; they now call `openTitle` and this
 * draws the result. The panel is a sibling of `children`, not a descendant, so
 * its focus trap makes the whole shell inert with one attribute.
 */
export function TitlePanelProvider({
  pageTitle,
  appName,
  children,
}: {
  /** What the tab is called when no title is open — the route's own name. */
  pageTitle: string;
  appName: string;
  children: ReactNode;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const {
    selectedItem, setSelectedItem,
    panelHistory, setPanelHistory, panelHistoryLoading,
    detail, detailLoading,
  } = useDetailPanel();

  const paramValue = new URLSearchParams(location.search).get(TITLE_PARAM);
  const ref = parseTitleParam(paramValue);
  const key = ref ? titleParamValue(ref) : null;
  const overScrim = readState(location.state).titleScrim === true;

  const seeds = useRef(new Map<string, SearchResult>());
  // Read at call time, so `openTitle` keeps one identity across navigations and
  // a page that lists it as a dependency doesn't re-run on every URL change.
  const locationRef = useRef(location);
  locationRef.current = location;

  // The URL is the state; this is what turns it into a loaded title. Keyed on
  // the parameter rather than on `ref`, which is a new object every render.
  useEffect(() => {
    const next = parseTitleParam(key);
    setSelectedItem(next ? (seeds.current.get(key!) ?? placeholderItem(next)) : null);
  }, [key, setSelectedItem]);

  const openTitle = useCallback((item: SearchResult, options?: OpenOptions) => {
    const here = locationRef.current;
    const params = new URLSearchParams(here.search);
    const nextKey = titleParamValue(item);
    const openKey = params.get(TITLE_PARAM);
    if (openKey === nextKey) return;

    seeds.current.delete(nextKey);
    seeds.current.set(nextKey, item);
    if (seeds.current.size > SEED_LIMIT) {
      const oldest = seeds.current.keys().next().value;
      if (oldest !== undefined) seeds.current.delete(oldest);
    }

    const state = readState(here.state);
    // A hop from an open panel stacks on it; anything else starts a new run.
    const hop = parseTitleParam(openKey) !== null;
    params.set(TITLE_PARAM, nextKey);
    void navigate(
      { pathname: here.pathname, search: `?${params}` },
      {
        state: {
          ...state,
          titleDepth: hop ? (state.titleDepth ?? 0) + 1 : 1,
          titleScrim: hop ? state.titleScrim === true : options?.overScrim === true,
        } satisfies TitleHistoryState,
      }
    );
  }, [navigate]);

  const close = useCallback(() => {
    const here = locationRef.current;
    const depth = readState(here.state).titleDepth ?? 0;
    if (depth > 0) {
      void navigate(-depth);
      return;
    }
    const params = new URLSearchParams(here.search);
    params.delete(TITLE_PARAM);
    const search = params.toString();
    void navigate({ pathname: here.pathname, search: search ? `?${search}` : "" }, { replace: true });
  }, [navigate]);

  // Drawn only while the URL and the loaded item agree. The effect above lags
  // the URL by a commit; without this check Back would leave the old title on
  // screen for that commit, and a hop would show the previous one under the new
  // address.
  const item = selectedItem && key === titleParamValue(selectedItem) ? selectedItem : null;

  // The tab is named for what is on screen. An id is not a name — see
  // `placeholderItem` — so a cold load keeps the page's title until it has one.
  const titleName = item && item.name !== item.imdbId ? item.name : null;
  useEffect(() => {
    document.title = titleName ? `${titleName} · ${appName}` : pageTitle;
  }, [titleName, pageTitle, appName]);

  const api = useMemo<TitlePanelApi>(
    () => ({ openTitle, current: ref ? { ...ref, overScrim } : null }),
    // `key` stands for `ref`, which is rebuilt every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [openTitle, key, overScrim]
  );

  return (
    <TitlePanelContext.Provider value={api}>
      {children}
      {item && (
        <PanelBoundary
          key={key}
          onError={() => {
            showToast("That title could not be shown", "error");
            close();
          }}
        >
        <DetailPanel
          item={item}
          history={panelHistory}
          historyLoading={panelHistoryLoading}
          detail={detail}
          detailLoading={detailLoading}
          staticScrim={overScrim}
          onClose={close}
          onShowToast={showToast}
          onHistoryChange={setPanelHistory}
          onSelectItem={openTitle}
        />
        </PanelBoundary>
      )}
    </TitlePanelContext.Provider>
  );
}
