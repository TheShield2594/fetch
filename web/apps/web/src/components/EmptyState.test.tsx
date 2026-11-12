import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("says what is missing, why, and offers one way forward", () => {
    render(
      <EmptyState title="Nothing here yet" action={<button type="button">Search</button>}>
        Titles you add show up here.
      </EmptyState>
    );
    expect(screen.getByText("Nothing here yet")).toBeInTheDocument();
    expect(screen.getByText("Titles you add show up here.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Search" })).toBeInTheDocument();
  });

  it("draws the mark's dog as its illustration, hidden from assistive tech", () => {
    const { container } = render(<EmptyState title="Empty" />);
    const svg = container.querySelector("svg");
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(svg?.querySelector("path")).not.toBeNull();
  });
});
