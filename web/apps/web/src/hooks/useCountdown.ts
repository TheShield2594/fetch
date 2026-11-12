import { useEffect, useState } from "react";

const secondsUntil = (until: number | null, now: number) => (until === null ? 0 : Math.max(0, Math.ceil((until - now) / 1000)));

/**
 * Whole seconds left until `until` (an epoch time in ms), ticking once a
 * second, and 0 once it has passed or when there is nothing to count down to.
 *
 * The clock is read when the hook mounts and on each tick, so a component
 * that shows a new deadline should be keyed on it (or mount with it), or the
 * first frame counts from the previous tick.
 */
export function useCountdown(until: number | null): number {
  // The clock as of the last tick. Kept in state rather than read in render,
  // so a render is a function of what it was given.
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (until === null) return;
    const tick = () => {
      const current = Date.now();
      setNow(current);
      if (current >= until) clearInterval(id);
    };
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [until]);

  return secondsUntil(until, now);
}

/** "4:05", "0:09": minutes and seconds, for a lockout that lasts a few minutes. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  const minutes = Math.floor(s / 60);
  const rest = s % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}
