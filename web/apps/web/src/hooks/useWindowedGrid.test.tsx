import { render, act } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useWindowedGrid } from "./useWindowedGrid";

function Grid({ count }: { count: number }) {
  const { gridRef, start, end, style } = useWindowedGrid(count, { threshold: 100 });
  return (
    <div ref={gridRef} data-testid="grid" style={style}>
      {Array.from({ length: end - start }, (_, i) => (
        <div key={start + i} data-cell={start + i} />
      ))}
    </div>
  );
}

// jsdom has no layout: give the grid four columns, rows 200px apart, and a top
// that follows a fake scroll position.
let scrollY = 0;
function fakeLayout() {
  vi.spyOn(window, "getComputedStyle").mockImplementation(
    () => ({ gridTemplateColumns: "100px 100px 100px 100px", rowGap: "20px" }) as CSSStyleDeclaration
  );
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.dataset.testid === "grid") return { top: 100 - scrollY } as DOMRect;
    return { height: 180 } as DOMRect;
  });
  Object.defineProperty(window, "innerHeight", { value: 800, configurable: true });
}

afterEach(() => {
  vi.restoreAllMocks();
  scrollY = 0;
});

const cells = (container: HTMLElement) => [...container.querySelectorAll("[data-cell]")].map((el) => Number((el as HTMLElement).dataset.cell));

describe("useWindowedGrid", () => {
  it("draws everything below the threshold", () => {
    fakeLayout();
    const { container } = render(<Grid count={80} />);
    expect(cells(container)).toHaveLength(80);
  });

  it("draws only the rows near the viewport past it, and pads for the rest", async () => {
    fakeLayout();
    const { container, getByTestId } = render(<Grid count={1000} />);
    // 800px of viewport plus 1200px of overscan below, from a top of 100px:
    // ceil((800 + 1200 - 100) / 200) = 10 rows of 4.
    expect(cells(container)).toHaveLength(40);
    expect(getByTestId("grid").style.paddingBottom).toBe(`${(250 - 10) * 200}px`);

    scrollY = 10_000;
    await act(async () => {
      window.dispatchEvent(new Event("scroll"));
      await new Promise((r) => requestAnimationFrame(() => r(undefined)));
    });
    const drawn = cells(container);
    expect(drawn[0]).toBeGreaterThan(0);
    expect(drawn.length).toBeLessThan(80);
    expect(getByTestId("grid").style.paddingTop).toBe(`${(drawn[0]! / 4) * 200}px`);
  });
});
