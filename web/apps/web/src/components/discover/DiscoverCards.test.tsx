import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DiscoveryCard } from "./DiscoveryCard";

describe("DiscoveryCard", () => {
  it("offers its action as a real button rather than a div wearing the role", async () => {
    const onSelect = vi.fn();
    const { container } = render(
      <DiscoveryCard item={{ id: "tt1", name: "Arrival" }} onSelect={onSelect} />
    );

    expect(container.querySelector('[role="button"]')).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "View details for Arrival" }));
    expect(onSelect).toHaveBeenCalledWith({ id: "tt1", name: "Arrival" });
  });

  it("is not focusable at all when there is nothing to select", () => {
    render(<DiscoveryCard item={{ id: "tt1", name: "Arrival" }} />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
