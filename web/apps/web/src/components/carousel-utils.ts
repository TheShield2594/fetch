import { useCallback, useEffect, useRef, useState } from "react";

/* ─── Poster fallback helpers ─── */

export function getInitials(name: string | null | undefined): string {
  if (!name) return "?";
  return name
    .split(/[\s:–—-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();
}

/**
 * A colour for a title, drawn from the theme it is shown in.
 *
 * Two jobs. It is the floor of every poster slot while the artwork is in
 * flight, so a loading grid is a wall of different colours rather than of
 * identical grey boxes; and it is the whole of the fallback when there is no
 * artwork at all.
 *
 * It used to be one of eight fixed Tailwind `*-900 → slate-900` gradients,
 * which belonged to none of the six themes — rose and emerald tiles on a page
 * whose only accent is orange. What varies now is which of the theme's two
 * accents a title takes and how much of it, so the spread comes from the
 * palette already on screen. The pick is a hash of the name: stable across
 * sessions, with nothing to store.
 */
export type TitleTint = {
  /** A CSS colour built from theme tokens. */
  color: string;
  /** How much of `color` goes into the surface, as a percentage. */
  strength: number;
};

const TINT_COLORS: [string, ...string[]] = [
  "rgb(var(--accent-rgb))",
  "rgb(var(--accent-2-rgb))",
  "color-mix(in srgb, rgb(var(--accent-rgb)), rgb(var(--accent-2-rgb)))",
];
const TINT_STRENGTHS: [number, ...number[]] = [14, 20, 26, 32];

/** How many distinct tints there are — for the test that they are all reachable. */
export const TITLE_TINT_COUNT = TINT_COLORS.length * TINT_STRENGTHS.length;

export function titleTint(name: string | null | undefined): TitleTint {
  let hash = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) hash = (hash * 31 + name!.charCodeAt(i)) | 0;
  const pick = Math.abs(hash) % TITLE_TINT_COUNT;
  return {
    color: TINT_COLORS[pick % TINT_COLORS.length] ?? TINT_COLORS[0],
    strength: TINT_STRENGTHS[Math.floor(pick / TINT_COLORS.length)] ?? TINT_STRENGTHS[0],
  };
}

/* ─── Horizontal scroll hook ─── */

export function useHorizontalScroll() {
  const ref = useRef<HTMLDivElement>(null);
  const [node, setNode] = useState<HTMLDivElement | null>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  const setRef = useCallback((el: HTMLDivElement | null) => {
    (ref as React.MutableRefObject<HTMLDivElement | null>).current = el;
    setNode(el);
  }, []);

  const checkScroll = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    setCanScrollLeft(el.scrollLeft > 4);
    setCanScrollRight(el.scrollLeft < el.scrollWidth - el.clientWidth - 4);
  }, []);

  useEffect(() => {
    const el = node;
    if (!el) return;
    checkScroll();
    el.addEventListener("scroll", checkScroll, { passive: true });
    const observer = new ResizeObserver(checkScroll);
    observer.observe(el);
    return () => {
      el.removeEventListener("scroll", checkScroll);
      observer.disconnect();
    };
  }, [node, checkScroll]);

  const scroll = useCallback((direction: "left" | "right") => {
    const el = ref.current;
    if (!el) return;
    const amount = el.clientWidth * 0.75;
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.scrollBy({ left: direction === "left" ? -amount : amount, behavior: reduceMotion ? "auto" : "smooth" });
  }, []);

  return { ref: setRef, canScrollLeft, canScrollRight, scroll, checkScroll };
}

/* The edge fade for these rows lives in `CarouselTrack`, which renders the
   scroll container itself — the fade has to be an overlay rather than a style,
   so it can't be handed back as a plain CSS object. */
