import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FetchLoader } from "./FetchLoader";
import { dotMatrix, hasDotGlyphs } from "./dotMatrix";

describe("FetchLoader", () => {
  it("announces itself as a live status with a default label", () => {
    render(<FetchLoader />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading…");
  });

  it("takes a caller-supplied label, so the wait can say what it is waiting on", () => {
    render(<FetchLoader label="Loading profiles…" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading profiles…");
  });

  it("draws the FETCH wordmark's dots, hidden from assistive tech", () => {
    const { container } = render(<FetchLoader />);
    const svg = container.querySelector("svg.fetch-loader")!;
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(svg.querySelectorAll("circle")).toHaveLength(dotMatrix("FETCH").dots.length);
  });

  it("times each dot off its column, so the sweep moves as a band", () => {
    const { container } = render(<FetchLoader />);
    const columns = [...container.querySelectorAll<SVGCircleElement>("circle")].map((c) =>
      Number(c.style.getPropertyValue("--dot-column"))
    );
    expect(Math.min(...columns)).toBe(0);
    expect(Math.max(...columns)).toBe(32);
  });
});

describe("dotMatrix", () => {
  it("lays characters out on a 7-unit pitch with no trailing gap", () => {
    expect(dotMatrix("FETCH").width).toBe(33);
    expect(dotMatrix("404").width).toBe(19);
  });

  it("covers the digits the 404 and stat numerals use", () => {
    expect(hasDotGlyphs("0123456789")).toBe(true);
    expect(hasDotGlyphs("fetch")).toBe(true);
    expect(hasDotGlyphs("dog")).toBe(false);
  });

  it("refuses a character it has no glyph for rather than drawing a gap", () => {
    expect(() => dotMatrix("4X4")).toThrow(/X/);
  });
});
