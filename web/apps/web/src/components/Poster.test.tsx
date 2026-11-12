import { render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import { describe, expect, it } from "vitest";
import { Poster } from "./Poster";
import { PosterGrid, posterGridSizes } from "./PosterGrid";

/**
 * jsdom never loads an image, so `complete` is always false and `naturalWidth`
 * always 0. Patching the prototype's accessors for the duration of one render
 * is how a test can put an <img> into the one state that matters here: already
 * decoded at the instant React's ref callback runs, which is synchronous and
 * during the commit — so nothing asynchronous can set it up in time.
 */
function whileEveryImageIsAlreadyDecoded<T>(run: () => T): T {
  const original = {
    complete: Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "complete"),
    naturalWidth: Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "naturalWidth"),
  };
  Object.defineProperty(HTMLImageElement.prototype, "complete", { get: () => true, configurable: true });
  Object.defineProperty(HTMLImageElement.prototype, "naturalWidth", { get: () => 500, configurable: true });
  try {
    return run();
  } finally {
    if (original.complete) Object.defineProperty(HTMLImageElement.prototype, "complete", original.complete);
    if (original.naturalWidth) Object.defineProperty(HTMLImageElement.prototype, "naturalWidth", original.naturalWidth);
  }
}

const image = () => screen.getByRole("img") as HTMLImageElement;

describe("Poster", () => {
  it("draws the title on its own tint when there is no artwork to show", () => {
    const { container } = render(<Poster src={undefined} alt="The Long Shore" />);

    // Initials for a thumbnail; the full title, from an attribute rather than
    // as text, for a slot wide enough to hold it. CSS picks between them.
    expect(screen.getByText("TL")).toBeInTheDocument();
    expect(container.querySelector(".poster-fallback-title")).toHaveAttribute("data-title", "The Long Shore");
    expect(screen.queryByText("The Long Shore")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("tints each slot from the theme, differently per title", () => {
    const tintOf = (name: string) => {
      const { container, unmount } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt={name} />);
      const style = (container.firstElementChild as HTMLElement).style;
      const tint = `${style.getPropertyValue("--poster-tint")} ${style.getPropertyValue("--poster-tint-mix")}`;
      unmount();
      return tint;
    };

    const tints = ["Dune", "Arrival", "Andor", "Chernobyl", "Fleabag", "Barry"].map(tintOf);
    for (const tint of tints) expect(tint).toMatch(/var\(--accent(-2)?-rgb\)/);
    expect(new Set(tints).size).toBeGreaterThan(1);
  });

  it("shows a blurred w92 of the same picture until the picture arrives", () => {
    const { container } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" />);

    const blur = container.querySelector("img.poster-blur");
    expect(blur).toHaveAttribute("src", "https://image.tmdb.org/t/p/w92/a.jpg");
    // Lazy like the image it stands in for, and silent to assistive tech.
    expect(blur).toHaveAttribute("loading", "lazy");
    expect(blur).toHaveAttribute("aria-hidden", "true");

    fireEvent.load(image());
    expect(container.querySelector("img.poster-blur")).toBeNull();
  });

  it("has no small rendition to show for artwork from another host", () => {
    const { container } = render(<Poster src="https://example.test/poster.jpg" alt="A" />);
    expect(container.querySelector("img.poster-blur")).toBeNull();
  });

  it("takes its sizes from the grid it is in, unless told otherwise", () => {
    const src = "https://image.tmdb.org/t/p/w500/a.jpg";
    const { rerender } = render(
      <PosterGrid density="library"><Poster src={src} alt="A" /></PosterGrid>
    );
    expect(image()).toHaveAttribute("sizes", posterGridSizes("library"));

    rerender(<PosterGrid density="library"><Poster src={src} alt="A" sizes="40px" /></PosterGrid>);
    expect(image()).toHaveAttribute("sizes", "40px");
  });

  it("falls back to the same placeholder when the artwork fails to load", () => {
    render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="The Long Shore" />);
    fireEvent.error(image());

    expect(screen.getByText("TL")).toBeInTheDocument();
  });

  // #505: backdrops were offered only the poster ladder, which stops at w780.
  it("offers a backdrop the landscape ladder, up to w1280, cropped from the top", () => {
    const { container } = render(
      <Poster src="https://image.tmdb.org/t/p/w500/bd.jpg" alt="Severance" variant="backdrop" sizes="100vw" />
    );
    const img = container.querySelector("img.poster-image") as HTMLImageElement;

    expect(img.getAttribute("srcset")).toBe(
      "https://image.tmdb.org/t/p/w300/bd.jpg 300w, https://image.tmdb.org/t/p/w780/bd.jpg 780w, https://image.tmdb.org/t/p/w1280/bd.jpg 1280w"
    );
    expect(img).toHaveClass("poster-image-backdrop");
  });

  it("fails a backdrop to the tint alone, without spelling out the title over the caption", () => {
    const { container } = render(
      <Poster src="https://image.tmdb.org/t/p/w500/bd.jpg" alt="Severance" variant="backdrop" />
    );
    fireEvent.error(container.querySelector("img.poster-image") as HTMLImageElement);

    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector(".poster-art")).toBeInTheDocument();
    expect(screen.queryByText("SE")).toBeNull();
  });

  it("fades a poster in over its placeholder while the bytes are in flight", () => {
    const { container } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" />);

    expect(container.querySelector(".poster-loading")).not.toBeNull();
    expect(image().className).toContain("opacity-0");

    fireEvent.load(image());

    expect(container.querySelector(".poster-loading")).toBeNull();
    expect(image().className).toContain("opacity-100");
  });

  /*
   * The regression this component's ref callback exists to prevent, and which
   * a mount effect used to reintroduce one tick later: an image that is
   * already decoded when React first sees it has no `load` event left to fire,
   * so anything that clears the loaded flag after the ref has set it leaves
   * the poster invisible for good.
   */
  it("paints an already-decoded image opaque on the first frame, and keeps it that way", () => {
    whileEveryImageIsAlreadyDecoded(() => render(<Poster src="data:image/svg+xml;utf8,<svg/>" alt="A" eager />));

    // No `load` event is fired here on purpose: for an image the browser
    // completed on assignment there would never be one, so this is the whole
    // of what the component gets to work with.
    expect(image().className).toContain("opacity-100");
  });

  it("goes back to the placeholder when the artwork itself changes", () => {
    const { container, rerender } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" />);
    fireEvent.load(image());
    expect(container.querySelector(".poster-loading")).toBeNull();

    rerender(<Poster src="https://image.tmdb.org/t/p/w500/b.jpg" alt="B" />);

    expect(container.querySelector(".poster-loading")).not.toBeNull();
    expect(image().className).toContain("opacity-0");
  });

  it("re-tries a new src after a failure, rather than staying on the placeholder", () => {
    const { rerender } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" />);
    fireEvent.error(image());
    expect(screen.queryByRole("img")).toBeNull();

    rerender(<Poster src="https://image.tmdb.org/t/p/w500/b.jpg" alt="A" />);

    expect(image()).toHaveAttribute("src", "https://image.tmdb.org/t/p/w500/b.jpg");
  });

  it("asks TMDB for the width the slot actually needs, and leaves other hosts alone", () => {
    const { rerender } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" sizes="192px" />);

    expect(image().getAttribute("srcset")).toContain("https://image.tmdb.org/t/p/w92/a.jpg 92w");
    expect(image()).toHaveAttribute("sizes", "192px");

    rerender(<Poster src="https://cdn.example.com/a.jpg" alt="A" sizes="192px" />);

    expect(image().getAttribute("srcset")).toBeNull();
    expect(image().getAttribute("sizes")).toBeNull();
  });

  it("prioritises above-fold artwork and defers the rest", () => {
    const { rerender } = render(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" eager />);
    expect(image()).toHaveAttribute("loading", "eager");
    expect(image()).toHaveAttribute("fetchpriority", "high");

    rerender(<Poster src="https://image.tmdb.org/t/p/w500/a.jpg" alt="A" />);
    expect(image()).toHaveAttribute("loading", "lazy");
    expect(image()).toHaveAttribute("fetchpriority", "low");
  });
});
