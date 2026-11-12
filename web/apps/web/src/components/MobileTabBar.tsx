import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import {
  BarChart3,
  CalendarDays,
  Compass,
  History,
  Library,
  List,
  MoreHorizontal,
  Search,
  Settings,
  type LucideIcon,
} from "lucide-react";
import { prefetchRoute } from "../utils/routePrefetch";
import { useIsKidsProfile } from "../hooks/useProfile";
import { useExitAnimation } from "../hooks/useExitAnimation";
import { useEscapeKey } from "../hooks/useEscapeKey";
import { useFocusTrap } from "../hooks/useFocusTrap";
import { useScrollLock } from "../hooks/useScrollLock";

type NavItem = { to: string; label: string; icon: LucideIcon; end?: boolean };

// Five tabs is the platform convention, and the practical ceiling: with eight,
// the bar's labels could not shrink below their own width, so at 320px it
// overflowed by 24px. Everything past the fifth slot lives behind "More".
//
// The four are what the product is for: what you have (Shelf), what you might
// want (Discover), finding a title (Search), and the lists you keep (Lists).
// Search was a circle of its own beside the pill for a while, which left the
// pill five tabs in a narrower space — at 375px "Discover" and "Calendar" ran
// together — and gave its slot to Stats, a page most people open now and then.
// Calendar, Stats and History are a tap further, in More.
export const PRIMARY_NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Shelf", icon: Library, end: true },
  { to: "/discover", label: "Discover", icon: Compass },
  { to: "/search", label: "Search", icon: Search },
  { to: "/lists", label: "Lists", icon: List },
];

export const MORE_NAV_ITEMS: NavItem[] = [
  { to: "/calendar", label: "Calendar", icon: CalendarDays },
  { to: "/stats", label: "Stats", icon: BarChart3 },
  { to: "/history", label: "History", icon: History },
  { to: "/settings", label: "Settings", icon: Settings },
];

const isItemActive = (item: NavItem, pathname: string) =>
  item.end ? pathname === item.to : pathname.startsWith(item.to);

// `px-1` on each tab is the gap between two labels: without it, a label as wide
// as its slot sat flush against its neighbour's.
const TAB_CLASSES =
  "relative flex min-w-0 flex-1 flex-col items-center justify-center gap-[3px] rounded-[1.75rem] px-1 py-2 text-2xs leading-none transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-focus";

/**
 * The floating tab bar.
 *
 * It used to be a full-width band pinned to the bottom edge with a hairline
 * along the top of it — a toolbar, which is a different piece of furniture. The
 * bar the platform floats is a rounded pill inset from all three edges with the
 * page visibly running underneath it, and the difference is not decoration: an
 * edge-to-edge band cuts the screen off at a line, and a floating one says the
 * content continues and this is on top of it.
 *
 * Two things carry that:
 *
 *   The glass. `.bar-glass` (see index.css) — a translucent tint over a blur
 *   with saturation, so the colour of whatever is scrolling behind it comes
 *   through. This is the surface the effect was invented for.
 *
 *   The fade. A gradient from transparent to the page colour, tall enough to
 *   cover the bar and the gap under it, so content dissolves as it passes
 *   behind rather than sliding out from under a hard edge.
 *
 * The sliding marker is gone with the band. There is no underline under a
 * selected tab on the platform: the accent tint on the icon and the label *is*
 * the selection, and a marker that slid between five slots was a second answer
 * to a question the colour had already answered.
 */
export function MobileTabBar({ pathname }: { pathname: string }) {
  const [moreOpen, setMoreOpen] = useState(false);
  const moreButtonRef = useRef<HTMLButtonElement>(null);
  const moreActive = MORE_NAV_ITEMS.some((item) => isItemActive(item, pathname));

  const closeMore = () => {
    setMoreOpen(false);
    // Send focus back to the tab that opened the sheet rather than dropping it
    // at the top of the document.
    moreButtonRef.current?.focus();
  };

  // --accent-text, not --accent: the label is text on the bar, and on the light
  // theme --accent measures 2.2:1 there against 5.5:1 for --accent-text.
  const tabStyle = (isActive: boolean) =>
    isActive ? { color: "var(--accent-text)" } : { color: "var(--text-dim)" };

  return (
    <>
      {moreOpen && <MoreSheet pathname={pathname} onClose={closeMore} />}

      {/* The fade. Purely paint — it takes no clicks, and it is a sibling of
          the bar rather than a shadow on it so it can be much taller than the
          bar without the bar growing a halo. */}
      <div
        aria-hidden="true"
        className="pointer-events-none fixed inset-x-0 bottom-0 z-30 h-36 sm:hidden"
        style={{
          // Solid by the bar's top edge, so a poster's caption passing under
          // it is gone rather than cut in half behind the glass.
          background:
            "linear-gradient(180deg, transparent, color-mix(in srgb, var(--bg-0) 85%, transparent) 45%, var(--bg-0) 75%)",
        }}
      />

      <nav
        aria-label="Mobile navigation"
        className="fixed z-40 flex items-center sm:hidden"
        // 0.75rem clear of the safe area, which on a phone with a home
        // indicator is the gap the platform leaves under a floating bar and on
        // one without it is simply a margin.
        //
        // The side insets are `max()` rather than an addition: they are zero in
        // portrait, which is the only orientation this bar is shown in today,
        // so the bar keeps its 0.75rem margin and the rule only does something
        // if the breakpoint ever lets the bar meet a notch.
        style={{
          bottom: "calc(0.75rem + env(safe-area-inset-bottom))",
          left: "max(0.75rem, env(safe-area-inset-left))",
          right: "max(0.75rem, env(safe-area-inset-right))",
        }}
      >
        <div className="bar-glass flex h-16 min-w-0 flex-1 items-stretch rounded-[2rem] px-1.5">
          {PRIMARY_NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const isActive = isItemActive(item, pathname);
            return (
              <Link
                key={item.to}
                to={item.to}
                aria-current={isActive ? "page" : undefined}
                className={`${TAB_CLASSES} ${isActive ? "font-semibold" : "font-medium"}`}
                style={tabStyle(isActive)}
                // Touch-down leads the click by ~100 ms, and on a warm
                // connection that is most of the chunk fetch. The idle pass in
                // App.tsx has usually beaten us here; this covers the taps that
                // come first.
                onTouchStart={() => prefetchRoute(item.to)}
              >
                <Icon className="h-6 w-6 flex-none" strokeWidth={isActive ? 2.1 : 1.9} />
                {/* `min-w-0` on the tab plus `truncate` here means a label
                    gives way before the bar can outgrow the viewport. */}
                <span className="max-w-full truncate">{item.label}</span>
              </Link>
            );
          })}

          <button
            ref={moreButtonRef}
            type="button"
            onClick={() => setMoreOpen(true)}
            aria-haspopup="dialog"
            aria-expanded={moreOpen}
            className={`${TAB_CLASSES} ${moreActive ? "font-semibold" : "font-medium"}`}
            style={tabStyle(moreActive)}
          >
            <MoreHorizontal className="h-6 w-6 flex-none" strokeWidth={moreActive ? 2.1 : 1.9} />
            <span className="max-w-full truncate">More</span>
          </button>
        </div>

      </nav>
    </>
  );
}

function MoreSheet({ pathname, onClose }: { pathname: string; onClose: () => void }) {
  // No Settings row for a kids profile: see KidsGate.
  const isKids = useIsKidsProfile();
  const firstLinkRef = useRef<HTMLAnchorElement>(null);
  const { exiting, requestClose, onExitAnimationEnd } = useExitAnimation(onClose);

  // This sheet declared `aria-modal="true"` while trapping nothing — alone among
  // the app's overlays, all of which share these three hooks. Tab walked straight
  // out into the page the attribute had just declared inert, and on touch the page
  // scrolled behind it. It is the nav surface for five of the app's mobile
  // destinations, so it is also the overlay that got out of a keyboard user's way
  // least willingly.
  const sheetRef = useFocusTrap<HTMLDivElement>();
  useScrollLock();
  // Was a bare `window` keydown listener, which fires for every open overlay at
  // once. useEscapeKey is the modal stack: only the topmost handler runs.
  useEscapeKey(requestClose);

  useEffect(() => {
    firstLinkRef.current?.focus();
  }, []);

  return (
    <div className="fixed inset-0 z-50 sm:hidden" role="dialog" aria-modal="true" aria-label="More destinations">
      <button
        type="button"
        aria-label="Close menu"
        onClick={requestClose}
        className={`overlay-scrim overlay-fade absolute inset-0 h-full w-full ${exiting ? "overlay-exit" : ""}`}
      />
      {/*
       * A card of its own, inset from the edges, rather than a panel welded to
       * the bottom of the screen — the same move the tab bar makes, and for the
       * same reason: the sheet is over the page, not part of its frame. The
       * grab handle is what the platform puts at the top of one.
       */}
      <div
        ref={sheetRef}
        className={`overlay-sheet absolute inset-x-3 bottom-0 rounded-3xl p-2 pt-2.5 shadow-e3 ${exiting ? "overlay-exit" : ""}`}
        onAnimationEnd={onExitAnimationEnd}
        style={{
          background: "var(--bg-1)",
          border: "0.5px solid var(--border)",
          marginBottom: "calc(0.75rem + env(safe-area-inset-bottom))",
        }}
      >
        <span
          aria-hidden="true"
          className="mx-auto mb-2 block h-1 w-9 rounded-full"
          style={{ background: "var(--border-strong)" }}
        />
        <ul>
          {(isKids ? MORE_NAV_ITEMS.filter((item) => item.to !== "/settings") : MORE_NAV_ITEMS).map((item, index) => {
            const Icon = item.icon;
            const isActive = isItemActive(item, pathname);
            return (
              // The rows are one list, hairline-separated and inset past the
              // icon column, rather than five floating pills — see `.list-row`.
              <li key={item.to} className="list-row" style={{ "--list-inset": "3.25rem" } as React.CSSProperties}>
                <Link
                  ref={index === 0 ? firstLinkRef : undefined}
                  to={item.to}
                  // Navigation happens on the click; the sheet slides out over
                  // the destination page.
                  onClick={requestClose}
                  onTouchStart={() => prefetchRoute(item.to)}
                  onPointerEnter={() => prefetchRoute(item.to)}
                  aria-current={isActive ? "page" : undefined}
                  className="flex min-h-[3.25rem] items-center gap-3 rounded-2xl px-3 text-[1.0625rem] transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                  style={{ color: isActive ? "var(--accent-text)" : "var(--text)" }}
                >
                  <Icon
                    className="h-[1.375rem] w-[1.375rem] flex-none"
                    strokeWidth={1.9}
                    style={{ color: "rgb(var(--accent-rgb))" }}
                  />
                  {item.label}
                </Link>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
