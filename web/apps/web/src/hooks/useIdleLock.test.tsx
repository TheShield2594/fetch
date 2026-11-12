import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getIdleLockMinutes, IDLE_ACTIVITY_KEY, IDLE_LOCK_KEY, setIdleLockMinutes, useIdleLock } from "./useIdleLock";

const MINUTE = 60_000;

describe("useIdleLock", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
    window.localStorage.clear();
  });

  it("does nothing while the setting is off", () => {
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));
    act(() => { vi.advanceTimersByTime(120 * MINUTE); });
    expect(onLock).not.toHaveBeenCalled();
  });

  it("locks once after the chosen minutes without activity", () => {
    setIdleLockMinutes(5);
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));

    act(() => { vi.advanceTimersByTime(4 * MINUTE); });
    expect(onLock).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1.5 * MINUTE); });
    expect(onLock).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(30 * MINUTE); });
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it("doesn't lock while someone is active in another tab", () => {
    setIdleLockMinutes(5);
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));

    // Another tab records activity every couple of minutes; this one sits idle.
    for (let i = 0; i < 5; i += 1) {
      act(() => { vi.advanceTimersByTime(2 * MINUTE); });
      window.localStorage.setItem(IDLE_ACTIVITY_KEY, String(Date.now()));
    }
    expect(onLock).not.toHaveBeenCalled();

    act(() => { vi.advanceTimersByTime(6 * MINUTE); });
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it("shares its own activity with other tabs", () => {
    setIdleLockMinutes(5);
    renderHook(() => useIdleLock(true, vi.fn()));
    act(() => { window.dispatchEvent(new Event("keydown")); });
    expect(Number(window.localStorage.getItem(IDLE_ACTIVITY_KEY))).toBe(Date.now());
  });

  it("starts the clock again on activity", () => {
    setIdleLockMinutes(5);
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));

    act(() => { vi.advanceTimersByTime(4 * MINUTE); });
    act(() => { window.dispatchEvent(new Event("keydown")); });
    act(() => { vi.advanceTimersByTime(4 * MINUTE); });
    expect(onLock).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1.5 * MINUTE); });
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it("locks on returning to a tab that was hidden past the limit", () => {
    setIdleLockMinutes(15);
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));

    // A background tab's timers are throttled, so only the clock moves.
    vi.setSystemTime(Date.now() + 20 * MINUTE);
    expect(onLock).not.toHaveBeenCalled();
    act(() => { document.dispatchEvent(new Event("visibilitychange")); });
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it("does nothing for a profile without a PIN", () => {
    setIdleLockMinutes(5);
    const onLock = vi.fn();
    renderHook(() => useIdleLock(false, onLock));
    act(() => { vi.advanceTimersByTime(60 * MINUTE); });
    expect(onLock).not.toHaveBeenCalled();
  });

  it("picks up a change of setting without remounting", () => {
    const onLock = vi.fn();
    renderHook(() => useIdleLock(true, onLock));
    act(() => { setIdleLockMinutes(5); });
    act(() => { vi.advanceTimersByTime(6 * MINUTE); });
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it("reads anything but a known choice as off", () => {
    window.localStorage.setItem(IDLE_LOCK_KEY, "7");
    expect(getIdleLockMinutes()).toBe(0);
    setIdleLockMinutes(30);
    expect(getIdleLockMinutes()).toBe(30);
    setIdleLockMinutes(0);
    expect(window.localStorage.getItem(IDLE_LOCK_KEY)).toBeNull();
  });
});
