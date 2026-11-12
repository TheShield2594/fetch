import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { formatCountdown, useCountdown } from "./useCountdown";

describe("useCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-12T12:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("counts whole seconds down to the deadline and stops at 0", () => {
    const until = Date.now() + 3_000;
    const { result } = renderHook(() => useCountdown(until));
    expect(result.current).toBe(3);

    act(() => { vi.advanceTimersByTime(1_000); });
    expect(result.current).toBe(2);

    act(() => { vi.advanceTimersByTime(5_000); });
    expect(result.current).toBe(0);
  });

  it("is 0 with nothing to count down to", () => {
    const { result } = renderHook(() => useCountdown(null));
    expect(result.current).toBe(0);
  });
});

describe("formatCountdown", () => {
  it.each([
    [0, "0:00"],
    [9, "0:09"],
    [65, "1:05"],
    [300, "5:00"],
  ])("writes %i seconds as %s", (seconds, text) => {
    expect(formatCountdown(seconds)).toBe(text);
  });
});
