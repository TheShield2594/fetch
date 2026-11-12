import { type ReactNode, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Link, NavLink, useLocation } from "react-router";
import { BarChart3, CalendarDays, Compass, History, Library, List, Pin, PinOff, Search, Settings, Tv } from "lucide-react";
import { api, type CatalogList } from "../api";
import { BrandMark, BrandWordmark } from "./BrandMark";
import { healthDotClass } from "./settings/health";
import { listTint, orderListsForRail, railSources } from "./sidebar-data";
import { useCachedState } from "../hooks/useCachedState";
import { useMediaQuery } from "../hooks/useMediaQuery";
import { useSettingsHealth } from "../hooks/useSettingsHealth";
import { useIsKidsProfile } from "../hooks/useProfile";
import { prefetchRoute } from "../utils/routePrefetch";

/*
 * The destinations: six places to look at what you watch.
 *
 * What you have (Shelf), what you might want (Discover), what you're looking
 * for (Search), what's coming (Calendar), what you've watched (History) and
 * what it adds up to (Stats). History sits here rather than under a "manage"
 * heading because the watch record is one of the things the app is *for*, not
 * an admin screen beside Collections and Profiles.
 */
export const SIDEBAR_NAV_ITEMS = [
  { to: "/", label: "Shelf", icon: Library, end: true },
  { to: "/discover", label: "Discover", icon: Compass, end: false },
  { to: "/search", label: "Search", icon: Search, end: false },
  { to: "/calendar", label: "Calendar", icon: CalendarDays, end: false },
  { to: "/history", label: "History", icon: History, end: false },
  { to: "/stats", label: "Stats", icon: BarChart3, end: false },
] as const;

/**
 * The last group: where you go to *change* things, as opposed to the six
 * above, which are places to look at them. The TV leads it — managing the TV
 * is half of what the website is for, and it was the third group inside
 * Settings — and Settings closes the column, where a source list keeps it.
 * Profiles are managed in Settings → Profiles, reached from the profile menu in
 * the footer.
 */
export const SIDEBAR_MANAGE_ITEMS = [
  { to: "/tv", label: "TV", icon: Tv, end: false },
  { to: "/settings", label: "Settings", icon: Settings, end: false },
] as const;

/** The width from which the rail opens pinned unless someone has said otherwise. */
export const PIN_BY_DEFAULT_QUERY = "(min-width: 1024px)";

/**
 * Whether the rail starts pinned: what the person last chose, or — if they have
 * never touched the pin — open from `lg` up, where there is room for labels
 * beside the page, and collapsed below it, where there isn't.
 *
 * Collapsed by default everywhere was the wrong way round on a desktop: a
 * column of icons that only says what it holds on hover, floating over the
 * first 240px of the page every time it does.
 */
export function initialPinned(): boolean {
  try {
    const stored = localStorage.getItem(PIN_KEY);
    if (stored === "1") return true;
    if (stored === "0") return false;
  } catch {
    // Storage blocked: fall through to the width.
  }
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia(PIN_BY_DEFAULT_QUERY).matches
    : false;
}

/**
 * Every row in the rail is the same box — 30px tall, 7px radius, 13px label,
 * a 17px leading glyph — whether it is a route, a list or a source. Written
 * once here because a source list that draws its three kinds of row at three
 * slightly different heights stops reading as one column.
 */
const RAIL_ROW =
  "group relative flex items-center gap-2.5 rounded-[0.4375rem] px-2.5 py-[0.4375rem] text-[0.8125rem] transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset";

/** The label over a group. Sentence case, and it fades out with the labels it heads. */
function RailSectionLabel({ children, expanded }: { children: string; expanded: boolean }) {
  return (
    <p
      className="px-2.5 pb-1 pt-4 text-2xs font-semibold transition-opacity"
      // Quaternary rather than the secondary the rows take: a heading that
      // competes with the rows under it is a row.
      style={{ color: "var(--text-mute)", opacity: expanded ? 1 : 0 }}
      // A heading over a column of unlabelled icons names nothing, and a
      // screen reader should not read one out while the rail is a rail.
      aria-hidden={!expanded}
    >
      {children}
    </p>
  );
}

export const PIN_KEY = "fetch:sidebar-pinned";
const HINT_KEY = "fetch:sidebar-hint-seen";
const HINT_AUTO_DISMISS_MS = 15000;

/**
 * The desktop source-list rail (hidden below `sm`, where the tab bar navigates
 * instead): the primary navigation rows with a sliding selection marker,
 * tooltips on the collapsed icons, keyboard-focus-to-expand, a pin toggle
 * persisted to localStorage, and a one-time hint that points at the pin.
 */
export function Sidebar({
  pinned,
  onPinnedChange,
  profileMenu,
}: {
  pinned: boolean;
  onPinnedChange: (pinned: boolean) => void;
  /** The footer's profile control, given whether the rail is showing labels. */
  profileMenu?: ((expanded: boolean) => ReactNode) | undefined;
}) {
  /*
   * Pointer hover no longer opens the rail. It used to, after 200ms, and the
   * open rail floated over the left 240px of the page — the Shelf's title and
   * first card — every time the cursor crossed to the edge of the window. Each
   * icon names itself in a tooltip instead, and the pin is how to keep labels.
   *
   * Keyboard focus still opens it: a tooltip needs a pointer, and without the
   * labels a keyboard user tabbing through would meet a column of bare icons.
   * Only *visible* focus counts, so a mouse click on a row (which focuses it
   * too) doesn't throw the rail open over the page it is navigating to.
   */
  const [focused, setFocused] = useState(false);
  // One-time discovery hint: that the rail can be pinned open is invisible
  // until someone finds the control, so surface it once, then never again.
  const [showHint, setShowHint] = useState(false);
  const hintSeenRef = useRef(true);
  const hintTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  // Read once: the tip is about the state the rail was found in, and re-reading
  // `pinned` would resurrect it the first time someone unpins.
  const pinnedAtMountRef = useRef(pinned);
  // Matches the `hidden sm:flex` this component styles itself with.
  const railVisible = useMediaQuery("(min-width: 640px)");
  const expanded = pinned || focused;

  /*
   * The two things the rail shows that aren't routes.
   *
   * Lists share the Lists page's own cache key, so arriving there paints in the
   * first frame and a list renamed or deleted on that page updates the rail
   * without either of them knowing about the other — `useCachedState`
   * broadcasts on write. The fetch is the rail's own because the rail is on
   * screen long before anyone visits /lists.
   *
   * Health comes from the shell's provider, which the Settings page reads too;
   * see the note there for why it is one report rather than two.
   *
   * Both are gated on the rail actually being on screen. Below `sm` this
   * component is `display: none` and the tab bar navigates instead, so a phone
   * pays for neither.
   */
  const [lists, setLists] = useCachedState<CatalogList[]>("lists:all", []);
  const { sections: health } = useSettingsHealth();
  const railLists = useMemo(() => orderListsForRail(lists), [lists]);
  // A kids profile is kept out of Settings (see KidsGate), so the rail drops
  // every way into it: the Sources shortcuts and the Settings row.
  const isKids = useIsKidsProfile();
  const sources = useMemo(() => (isKids ? [] : railSources(health)), [health, isKids]);


  useEffect(() => {
    if (!railVisible) return;
    const controller = new AbortController();
    // Silent on failure: the rail is chrome, and a column of lists that failed
    // to load should be an absent section, not an error message parked beside
    // every page in the app. The Lists page reports its own failures.
    void api
      .getLists(controller.signal)
      .then((res) => setLists(res.lists))
      .catch(() => {});
    return () => controller.abort();
  }, [railVisible, setLists]);

  // One marker for the whole rail, moved to the active item — rather than one
  // per item, mounted and unmounted, which teleported the rail's only piece of
  // state from wherever it was to wherever it now belongs. Measured off the
  // active link rather than derived from the index, so it stays correct if an
  // item's height ever stops matching its neighbours'.
  //
  // The mechanism is unchanged and the shape is not: it was a 3px bar down the
  // leading edge, and a source list on the platform selects a row by filling
  // it. A rule beside a row is a margin note about it; a fill is the row being
  // the one you are on, and it is also the only treatment that still reads once
  // the rail is collapsed to icons.
  const navRef = useRef<HTMLElement>(null);
  const location = useLocation();
  /*
   * Which row is the page you are on — computed here rather than left to
   * `NavLink`, because `NavLink` matches the pathname and nothing else.
   *
   * The list rows differ only by their query string, so every one of them
   * matched `/lists` and announced itself as the current page at once; `end`
   * did not save the "All Lists" row either, since it narrows the *path* and
   * has no opinion about `?list=`. Three rows carrying `aria-current="page"`
   * is three answers to "where am I", and the marker — which takes the first
   * one it finds — parked on the wrong row.
   */
  const onListsRoute = location.pathname === "/lists";
  const openListId = onListsRoute ? new URLSearchParams(location.search).get("list") : null;
  // The open list's own row only exists while the rail is open and the list is
  // in it; otherwise the Lists row is where you are.
  const openListRowShown = expanded && openListId !== null && railLists.some((list) => list.id === openListId);
  const listsRowCurrent = onListsRoute && !openListRowShown;
  const [marker, setMarker] = useState<{ top: number; height: number } | null>(null);
  useLayoutEffect(() => {
    // NavLink writes aria-current on whichever link matched, which spares us
    // re-implementing its `end`/trailing-slash rules to find the same one.
    const active = navRef.current?.querySelector<HTMLElement>('a[aria-current="page"]');
    setMarker(active ? { top: active.offsetTop, height: active.offsetHeight } : null);
    // The search string as well as the path: the list rows differ only by
    // `?list=`, so moving between two of them is a navigation that changes
    // which row is current without changing the pathname at all.
    //
    // `railVisible` too: below `sm` the whole rail is `display: none`, where
    // every offset measures zero. Widening the window past 640px is not a
    // navigation, so without this the rail would appear with its marker still
    // holding those zeroes until the next route change.
    //
    // `expanded` and the lists too: the list and source rows come and go with
    // the rail opening, which moves every row below them.
    //
    // `isKids` as well: a kids profile's rail has no Settings row, and the
    // profile lands after the rail has measured, so the marker would otherwise
    // stay where that row was.
  }, [location.pathname, location.search, railVisible, expanded, railLists.length, sources.length, isKids]);

  useEffect(() => {
    hintSeenRef.current = localStorage.getItem(HINT_KEY) === "1";
  }, []);

  useEffect(() => {
    // Shown unprompted rather than on first hover-expand, which was the bug:
    // the tip explaining that hovering expands the rail could only be read by
    // someone who had already discovered that hovering expands the rail. It
    // renders beside the rail, so the collapsed state doesn't clip it.
    //
    // Gated on the rail being on screen at all — below `sm` this component is
    // `display: none` and the bottom tab bar navigates instead, so firing here
    // would spend a one-time tip on a viewport that never showed it. A rail
    // already pinned when the app loaded needs no tip about pinning it either.
    if (!railVisible || hintSeenRef.current || pinnedAtMountRef.current) return;
    setShowHint(true);
    hintSeenRef.current = true;
    localStorage.setItem(HINT_KEY, "1");
    // A discovery tip is not an alert — let it retire on its own if it is
    // ignored, rather than leaving a card parked over the page forever.
    hintTimerRef.current = setTimeout(() => setShowHint(false), HINT_AUTO_DISMISS_MS);
  }, [railVisible]);

  useEffect(() => () => {
    clearTimeout(hintTimerRef.current);
  }, []);

  const dismissHint = () => {
    clearTimeout(hintTimerRef.current);
    setShowHint(false);
    hintSeenRef.current = true;
    localStorage.setItem(HINT_KEY, "1");
  };

  const handleFocus = (event: React.FocusEvent<HTMLElement>) => {
    // `:focus-visible` is the browser's own answer to "did this come from the
    // keyboard". jsdom doesn't implement the selector, so a throw counts as yes.
    let visible: boolean;
    try {
      visible = event.target.matches(":focus-visible");
    } catch {
      visible = true;
    }
    if (visible) setFocused(true);
  };

  // React's onFocus/onBlur are focusin/focusout, so they also fire as focus
  // moves between two controls inside the rail. That is not focus leaving, and
  // collapsing on it would snap the labels shut mid-traversal.
  const handleBlur = (event: React.FocusEvent<HTMLElement>) => {
    if (event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return;
    setFocused(false);
  };

  const togglePin = () => {
    const next = !pinned;
    onPinnedChange(next);
    localStorage.setItem(PIN_KEY, next ? "1" : "0");
    if (showHint) dismissHint();
  };

  return (
    <aside
      // Named because the Lists page has a complementary landmark of its own,
      // and two unnamed ones are indistinguishable in a landmark list.
      aria-label="Sidebar"
      className="fixed inset-y-0 left-0 z-40 hidden sm:flex"
      onFocus={handleFocus}
      onBlur={handleBlur}
    >
      {/*
       * The expand/collapse animates `width`, a layout property, which is the
       * deliberate exception to "animate transform/opacity only". A transform
       * can't stand in here: the icons are pinned to the rail's leading edge and
       * stay visible while collapsed, so translating the panel would slide them
       * off screen, and the collapsed rail has to physically occupy 4rem or it
       * would sit over the page's content instead of beside it. The reflow is
       * bounded — one column of rows, `duration-base`, off a pin or focus — and the
       * page itself doesn't move (App.tsx's `sidebarPad` reserves the footprint
       * and the expanded rail floats over content on its shadow). Named as
       * `transition-[width]`, not `transition-all`, so nothing else eases with it.
       */}
      <div
        className="glass-surface flex h-full flex-col overflow-hidden py-3 backdrop-blur-xl transition-[width] duration-base"
        style={{
          // In landscape on a notched phone the rail's left edge is under the
          // sensor housing. The material runs all the way to the physical edge
          // — a translucent pane that stops short of it would show the page
          // through the gap — and the rows are held clear of the housing
          // instead, with the rail widened by the same inset so the visible
          // rail is the width it has always been. App.tsx's `sidebarPad`
          // reserves the same widened footprint.
          width: `calc(${expanded ? "15rem" : "4rem"} + env(safe-area-inset-left))`,
          paddingLeft: "env(safe-area-inset-left)",
          // Translucent rather than opaque: a source list on the platform is a
          // pane the window's material shows through, and the app's own content
          // scrolls past behind it.
          background: "color-mix(in srgb, var(--bg-1) 86%, transparent)",
          borderRight: "0.5px solid var(--border)",
          boxShadow: expanded && !pinned ? "8px 0 24px rgba(0,0,0,0.18)" : "none",
          transitionTimingFunction: "var(--ease-settle)",
        }}
      >
        <div className="flex items-center gap-2.5 px-4 pb-5" style={{ color: "var(--text)" }}>
          <BrandMark className="h-8 w-8 flex-none" />
          <BrandWordmark
            className="h-4 w-auto flex-none transition-opacity"
            style={{ opacity: expanded ? 1 : 0 }}
          />
        </div>

        {/* Scrolls on a short window rather than squeezing the rows: four
            groups plus a library's worth of lists is taller than a laptop in
            landscape, and a source list is a thing you scroll. The marker
            inside is positioned against this element, which is what
            `relative` is for — it stays correct at any scroll offset. */}
        <nav
          ref={navRef}
          aria-label="Primary"
          // Not `scrollbar-hide`: the carousels hide theirs because a rail of
          // posters says "there is more" by showing half of the next one, and
          // a column of rows clipped at the footer says nothing at all.
          className="relative flex flex-1 flex-col gap-1 overflow-y-auto px-2.5"
        >
          {marker && (
            <span
              aria-hidden="true"
              className="pointer-events-none absolute inset-x-2.5 top-0 rounded-[0.4375rem] transition-transform duration-slow"
              style={{
                height: marker.height,
                transform: `translateY(${marker.top}px)`,
                background: "var(--surface-strong)",
                transitionTimingFunction: "var(--ease-settle)",
              }}
            />
          )}
          {SIDEBAR_NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => `${RAIL_ROW} ${isActive ? "font-medium" : "hover:bg-[var(--surface)]"}`}
              style={({ isActive }) => ({ color: isActive ? "var(--text)" : "var(--text-dim)" })}
              // Pointer-user fallback while the label is hidden: without it the
              // collapsed rail is a column of icons with nothing to hover for a name.
              title={expanded ? undefined : item.label}
              // Start the route's chunk on the approach rather than on the
              // click, so the page is usually already in memory by the time it
              // is asked for. Keyboard focus counts as an approach too.
              onPointerEnter={() => prefetchRoute(item.to)}
              onFocus={() => prefetchRoute(item.to)}
            >
              {/* --nav-glyph: accent-tinted whether or not the row is selected
                  in most themes, which is how a source list draws its glyphs —
                  the fill behind the row says which one you are on. The Fetch
                  theme follows the TV sidebar instead: quiet glyphs, and the
                  accent only on the current page. See --nav-glyph in index.css. */}
              <item.icon
                className="h-[1.0625rem] w-[1.0625rem] flex-none"
                strokeWidth={2}
                style={{ color: "var(--nav-glyph)" }}
              />
              <span className="whitespace-nowrap" style={{ opacity: expanded ? 1 : 0 }}>
                {item.label}
              </span>
            </NavLink>
          ))}

          {/*
            * The lists themselves, not a link to the page that manages them.
            *
            * This is the difference between a rail and a source list: "Lists"
            * as one row is a menu item, and it makes you open a page to find
            * out that your watchlist has 27 things in it. The dot is identity
            * (see `listTint`) and the count is the fact the page was hiding.
            *
            * Only while the rail is open. Collapsed, a list is a coloured dot
            * with no name or count — five of them in a column said nothing
            * until hovered — so the collapsed rail has the one Lists row below
            * instead, which is an icon with a tooltip like every other.
            *
            * The heading is absent until there is something under it, so a
            * fresh install gets no heading over a single row.
            */}
          {expanded && railLists.length > 0 && (
            <>
              <RailSectionLabel expanded={expanded}>Lists</RailSectionLabel>
              {railLists.map((list) => {
                const isCurrent = openListId === list.id;
                return (
                  <Link
                    key={list.id}
                    to={`/lists?list=${encodeURIComponent(list.id)}`}
                    aria-current={isCurrent ? "page" : undefined}
                    className={`${RAIL_ROW} ${isCurrent ? "font-medium" : "hover:bg-[var(--surface)]"}`}
                    style={{ color: isCurrent ? "var(--text)" : "var(--text-dim)" }}
                    onPointerEnter={() => prefetchRoute("/lists")}
                    onFocus={() => prefetchRoute("/lists")}
                  >
                    {/* Sized and placed like the routes' glyphs so the two kinds
                        of row share one text column. */}
                    <span className="flex h-[1.0625rem] w-[1.0625rem] flex-none items-center justify-center">
                      <span
                        aria-hidden="true"
                        className="h-2 w-2 rounded-full"
                        style={{ background: listTint(list) }}
                      />
                    </span>
                    <span className="min-w-0 flex-1 truncate">{list.name}</span>
                    <span className="flex-none tabular-nums" style={{ color: "var(--text-mute)" }}>
                      {list.itemCount}
                    </span>
                  </Link>
                );
              })}
            </>
          )}
          {/* The page where lists are made, renamed and deleted — and, while
              the rail is collapsed, the only list row there is. Current on the
              bare /lists, and on any /lists while collapsed, when the row for
              the open list isn't drawn to carry it. */}
          <Link
            to="/lists"
            aria-current={listsRowCurrent ? "page" : undefined}
            className={`${RAIL_ROW} ${listsRowCurrent ? "font-medium" : "hover:bg-[var(--surface)]"}`}
            style={{ color: listsRowCurrent ? "var(--text)" : expanded && railLists.length > 0 ? "var(--text-mute)" : "var(--text-dim)" }}
            title={expanded ? undefined : "Lists"}
            onPointerEnter={() => prefetchRoute("/lists")}
            onFocus={() => prefetchRoute("/lists")}
          >
            <List
              className="h-[1.0625rem] w-[1.0625rem] flex-none"
              strokeWidth={2}
              style={{ color: "var(--nav-glyph)" }}
            />
            <span className="whitespace-nowrap" style={{ opacity: expanded ? 1 : 0 }}>
              {expanded && railLists.length > 0 ? "All lists" : "Lists"}
            </span>
          </Link>

          {/*
            * Sources, with whether they are working — open rail only, for the
            * same reason as the lists: collapsed, each was a bare health dot.
            *
            * Only the ones that are actually set up — see `railSources`. Each
            * opens its own section of Settings, so "Token expired" is one click
            * from the field that fixes it rather than a tab away.
            *
            * Plain links, and never `aria-current`: they are shortcuts into
            * one page, and the Settings row below is the one that *is* that
            * page.
            *
            * The dot is never the whole message (SC 1.4.1): the words beside
            * it are what separate "Synced 4m ago" from "Token expired", and
            * the row's `title` carries the unabbreviated version.
            */}
          {expanded && sources.length > 0 && (
            <>
              <RailSectionLabel expanded={expanded}>Sources</RailSectionLabel>
              {sources.map((source) => (
                <Link
                  key={source.id}
                  to={source.href}
                  className={`${RAIL_ROW} hover:bg-[var(--surface)]`}
                  style={{ color: "var(--text-dim)" }}
                  title={`${source.label} — ${source.health.label}`}
                  onPointerEnter={() => prefetchRoute("/settings")}
                  onFocus={() => prefetchRoute("/settings")}
                >
                  <span className="flex h-[1.0625rem] w-[1.0625rem] flex-none items-center justify-center">
                    <span aria-hidden="true" className={healthDotClass(source.health.tone)} />
                  </span>
                  <span className="min-w-0 flex-1 truncate">{source.label}</span>
                  <span className="max-w-[6.5rem] flex-none truncate" style={{ color: "var(--text-mute)" }}>
                    {source.status}
                  </span>
                </Link>
              ))}
            </>
          )}

          {!isKids && <RailSectionLabel expanded={expanded}>TV &amp; settings</RailSectionLabel>}
          {(isKids ? [] : SIDEBAR_MANAGE_ITEMS).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => `${RAIL_ROW} ${isActive ? "font-medium" : "hover:bg-[var(--surface)]"}`}
              style={({ isActive }) => ({ color: isActive ? "var(--text)" : "var(--text-dim)" })}
              title={expanded ? undefined : item.label}
              onPointerEnter={() => prefetchRoute(item.to)}
              onFocus={() => prefetchRoute(item.to)}
            >
              <item.icon
                className="h-[1.0625rem] w-[1.0625rem] flex-none"
                strokeWidth={2}
                style={{ color: "var(--nav-glyph)" }}
              />
              <span className="whitespace-nowrap" style={{ opacity: expanded ? 1 : 0 }}>
                {item.label}
              </span>
            </NavLink>
          ))}
        </nav>

        {/* A hairline and a little air, so the two controls at the bottom read
            as the pane's footer rather than as two more rows of the group above
            them — which is what they became once the nav grew tall enough to
            reach down here. */}
        <div className="mt-2 flex-none px-2.5 pt-2" style={{ borderTop: "0.5px solid var(--border)" }}>
          {profileMenu?.(expanded)}
          <button
            type="button"
            onClick={togglePin}
            className="flex w-full items-center gap-2.5 rounded-[0.4375rem] px-2.5 py-[0.4375rem] text-[0.8125rem] transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
            style={{ color: "var(--text-dim)" }}
            aria-label={pinned ? "Unpin sidebar" : "Pin sidebar open"}
            title={expanded ? undefined : pinned ? "Unpin sidebar" : "Pin sidebar open"}
          >
            {pinned ? (
              <PinOff className="h-[1.0625rem] w-[1.0625rem] flex-none" strokeWidth={2} />
            ) : (
              <Pin className="h-[1.0625rem] w-[1.0625rem] flex-none" strokeWidth={2} />
            )}
            <span className="whitespace-nowrap" style={{ opacity: expanded ? 1 : 0 }}>
              {pinned ? "Unpin" : "Pin open"}
            </span>
          </button>
        </div>
      </div>

      {/* Outside the rail, which clips its own overflow — so the tip is legible
          while the rail is still collapsed, which is the only state it is
          actually about. Positioned rather than laid out, so it never widens
          the rail or shifts the nav. */}
      {showHint && (
        <div
          className="absolute bottom-4 w-56 rounded-lg p-3 text-xs shadow-e2 transition-[left] duration-base ease-out"
          style={{
            left: expanded ? "15.5rem" : "4.5rem",
            background: "var(--surface-strong)",
            border: "1px solid var(--border-strong)",
          }}
        >
          {/* The live region holds the prose and nothing else. It used to wrap the
              button as well, so the tip was announced with a control folded into
              the middle of it that then had to be hunted down — a live region
              reads its contents out, it doesn't hand you what's inside. Outside
              it, the button is still the very next thing in the tab order. */}
          <div role="status">
            <p className="font-semibold" style={{ color: "var(--text)" }}>Quick tip</p>
            <p className="mt-1 leading-snug" style={{ color: "var(--text-dim)" }}>
              Point at an icon for its name, or pin the sidebar open to keep the labels.
            </p>
          </div>
          <button
            type="button"
            onClick={dismissHint}
            // "Got it" alone says nothing about what it acts on once the tip is no
            // longer being read. The visible text stays inside the accessible name,
            // which is what SC 2.5.3 asks.
            aria-label="Got it — dismiss quick tip"
            className="btn-primary btn-xs mt-2"
          >
            Got it
          </button>
        </div>
      )}
    </aside>
  );
}
