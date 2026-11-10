import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { hasOpenModalLayer, useEscapeKey } from "./useEscapeKey";

function Layer({ onEscape, active = true }: { onEscape: () => void; active?: boolean }) {
  useEscapeKey(onEscape, active);
  return null;
}

describe("useEscapeKey", () => {
  it("runs only the topmost handler, once, however many layers are mounted", () => {
    const bottom = vi.fn();
    const middle = vi.fn();
    const top = vi.fn();
    render(
      <>
        <Layer onEscape={bottom} />
        <Layer onEscape={middle} />
        <Layer onEscape={top} />
      </>
    );

    fireEvent.keyDown(document, { key: "Escape" });

    expect(top).toHaveBeenCalledTimes(1);
    expect(middle).not.toHaveBeenCalled();
    expect(bottom).not.toHaveBeenCalled();
  });

  it("skips inactive layers and stops listening once nothing is open", () => {
    const below = vi.fn();
    const inactive = vi.fn();
    const { unmount } = render(
      <>
        <Layer onEscape={below} />
        <Layer onEscape={inactive} active={false} />
      </>
    );

    fireEvent.keyDown(document, { key: "Escape" });
    expect(below).toHaveBeenCalledTimes(1);
    expect(inactive).not.toHaveBeenCalled();

    unmount();
    expect(hasOpenModalLayer()).toBe(false);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(below).toHaveBeenCalledTimes(1);
  });

  it("ignores other keys", () => {
    const onEscape = vi.fn();
    render(<Layer onEscape={onEscape} />);
    fireEvent.keyDown(document, { key: "Enter" });
    expect(onEscape).not.toHaveBeenCalled();
  });
});
