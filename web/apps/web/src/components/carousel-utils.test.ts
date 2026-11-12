import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  TITLE_TINT_COUNT,
  titleTint,
  getInitials,
  useHorizontalScroll,
} from "./carousel-utils";

describe("getInitials", () => {
  it("takes the first letter of the first two words", () => {
    expect(getInitials("Breaking Bad")).toBe("BB");
    expect(getInitials("The Lord of the Rings")).toBe("TL");
  });

  it("uppercases single-word titles", () => {
    expect(getInitials("severance")).toBe("S");
  });

  it("splits on colons and every dash variant", () => {
    expect(getInitials("Star Wars: A New Hope")).toBe("SW");
    expect(getInitials("Spider-Man")).toBe("SM");
    expect(getInitials("Mission–Impossible")).toBe("MI");
    expect(getInitials("Mission—Impossible")).toBe("MI");
  });

  it("ignores separators that would otherwise yield empty initials", () => {
    expect(getInitials("  -  Fargo")).toBe("F");
  });

  it("falls back to '?' for missing names", () => {
    expect(getInitials(null)).toBe("?");
    expect(getInitials(undefined)).toBe("?");
    expect(getInitials("")).toBe("?");
  });
});

describe("titleTint", () => {
  const key = (name: string | null) => {
    const tint = titleTint(name);
    return `${tint.color}@${tint.strength}`;
  };

  it("is built from theme tokens, not from a fixed palette", () => {
    for (const name of ["Dune", "Arrival", "Andor", "Chernobyl", "Fleabag"]) {
      expect(titleTint(name).color).toMatch(/var\(--accent(-2)?-rgb\)/);
    }
  });

  it("is stable for the same name", () => {
    expect(key("Succession")).toBe(key("Succession"));
  });

  it("spreads different names across more than one tint", () => {
    const names = ["Dune", "Arrival", "Andor", "Chernobyl", "Fleabag", "Barry", "Severance"];
    expect(new Set(names.map(key)).size).toBeGreaterThan(1);
  });

  it("can reach every tint it defines", () => {
    const seen = new Set(Array.from({ length: 400 }, (_, i) => key(`Title ${i}`)));
    expect(seen.size).toBe(TITLE_TINT_COUNT);
  });

  it("gives a missing name a tint rather than nothing", () => {
    expect(key(null)).toBe(key(""));
    expect(titleTint(null).strength).toBeGreaterThan(0);
  });

  it("stays in range for names whose hash overflows to a negative int", () => {
    const tint = titleTint("x".repeat(500));
    expect(tint.color).toBeTruthy();
    expect(tint.strength).toBeGreaterThan(0);
  });
});

/** jsdom has no layout, so scroll metrics have to be faked onto the node. */
function scrollableDiv({ scrollLeft = 0, scrollWidth = 1000, clientWidth = 400 } = {}) {
  const el = document.createElement("div");
  Object.defineProperties(el, {
    scrollLeft: { value: scrollLeft, writable: true, configurable: true },
    scrollWidth: { value: scrollWidth, writable: true, configurable: true },
    clientWidth: { value: clientWidth, writable: true, configurable: true },
  });
  document.body.append(el);
  return el;
}

describe("useHorizontalScroll", () => {
  it("reports no scrollability before a node is attached", () => {
    const { result } = renderHook(() => useHorizontalScroll());
    expect(result.current.canScrollLeft).toBe(false);
    expect(result.current.canScrollRight).toBe(false);
  });

  it("detects room to scroll right once attached to an overflowing row", () => {
    const el = scrollableDiv();
    const { result } = renderHook(() => useHorizontalScroll());

    act(() => result.current.ref(el));

    expect(result.current.canScrollLeft).toBe(false);
    expect(result.current.canScrollRight).toBe(true);
  });

  it("updates both directions as the row is scrolled", () => {
    const el = scrollableDiv();
    const { result } = renderHook(() => useHorizontalScroll());
    act(() => result.current.ref(el));

    el.scrollLeft = 300;
    act(() => {
      el.dispatchEvent(new Event("scroll"));
    });
    expect(result.current.canScrollLeft).toBe(true);
    expect(result.current.canScrollRight).toBe(true);

    el.scrollLeft = 600;
    act(() => {
      el.dispatchEvent(new Event("scroll"));
    });
    expect(result.current.canScrollLeft).toBe(true);
    expect(result.current.canScrollRight).toBe(false);
  });

  it("ignores sub-pixel scroll offsets on either end", () => {
    const el = scrollableDiv({ scrollLeft: 3 });
    const { result } = renderHook(() => useHorizontalScroll());
    act(() => result.current.ref(el));

    expect(result.current.canScrollLeft).toBe(false);

    el.scrollLeft = 597;
    act(() => {
      el.dispatchEvent(new Event("scroll"));
    });
    expect(result.current.canScrollRight).toBe(false);
  });

  it("scrolls by three quarters of the visible width in each direction", () => {
    const el = scrollableDiv();
    const scrollBy = vi.fn();
    el.scrollBy = scrollBy;
    const { result } = renderHook(() => useHorizontalScroll());
    act(() => result.current.ref(el));

    act(() => result.current.scroll("right"));
    expect(scrollBy).toHaveBeenCalledWith({ left: 300, behavior: "smooth" });

    act(() => result.current.scroll("left"));
    expect(scrollBy).toHaveBeenCalledWith({ left: -300, behavior: "smooth" });
  });

  it("jumps without animation when the user prefers reduced motion", () => {
    const el = scrollableDiv();
    const scrollBy = vi.fn();
    el.scrollBy = scrollBy;
    vi.spyOn(window, "matchMedia").mockReturnValue({ matches: true } as MediaQueryList);

    const { result } = renderHook(() => useHorizontalScroll());
    act(() => result.current.ref(el));
    act(() => result.current.scroll("right"));

    expect(scrollBy).toHaveBeenCalledWith({ left: 300, behavior: "auto" });
  });

  it("stops listening once the node is detached", () => {
    const el = scrollableDiv();
    const remove = vi.spyOn(el, "removeEventListener");
    const { result } = renderHook(() => useHorizontalScroll());
    act(() => result.current.ref(el));

    act(() => result.current.ref(null));

    expect(remove).toHaveBeenCalledWith("scroll", expect.any(Function));
  });
});
