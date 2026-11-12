import { useId, useState } from "react";
import { MAX_PIN_LENGTH, MIN_PIN_LENGTH } from "@fetch/shared/pin";

/** Boxes to draw: the minimum, then one more for the next digit, up to the maximum; or exactly `length`. */
export function pinBoxCount(typed: number, length?: number | undefined): number {
  if (length) return length;
  return Math.min(MAX_PIN_LENGTH, Math.max(MIN_PIN_LENGTH, typed + 1));
}

/**
 * A PIN as a row of digit boxes, the way the TV asks for one.
 *
 * One real input sits over the boxes, transparent, so the platform's own
 * numeric keypad comes up on a phone, paste and password managers work, and a
 * screen reader meets a single labelled field rather than eight. The boxes
 * only draw what it holds.
 *
 * A PIN is 4 to 8 digits, so there is no length at which "done" is certain:
 * like the TV, entry goes ahead by itself at 8 digits, or at `length` when one
 * is given (confirming a new PIN, whose length is already known), and
 * otherwise on Enter or the form's button.
 */
export function PinField({
  id,
  label,
  value,
  onChange,
  onComplete,
  onReject,
  length,
  autoFocus = false,
  disabled = false,
  invalid = false,
  shakeKey = 0,
  describedBy,
  autoComplete = "off",
  size = "md",
}: {
  id?: string | undefined;
  /** The field's accessible name; the visible label is the caller's. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** Called once the PIN reaches the length that submits it by itself. */
  onComplete?: ((value: string) => void) | undefined;
  /**
   * More digits arrived at once than a PIN holds (a paste): the field keeps
   * what it had rather than quietly cutting the PIN short, and says so here.
   */
  onReject?: (() => void) | undefined;
  /** Exactly this many digits, as when confirming a PIN already typed once. */
  length?: number | undefined;
  autoFocus?: boolean | undefined;
  disabled?: boolean | undefined;
  invalid?: boolean | undefined;
  /** Bump to shake the boxes once, for a PIN that was just refused. */
  shakeKey?: number | undefined;
  describedBy?: string | undefined;
  autoComplete?: string | undefined;
  size?: "md" | "lg" | undefined;
}) {
  const fallbackId = useId();
  const inputId = id ?? fallbackId;
  const [focused, setFocused] = useState(false);
  const max = length ?? MAX_PIN_LENGTH;

  // Replays the shake each time the caller asks for one. Adjusted during render
  // rather than in an effect, so the class is on the first paint after the ask.
  const [seenShake, setSeenShake] = useState(shakeKey);
  const [shaking, setShaking] = useState(false);
  if (shakeKey !== seenShake) {
    setSeenShake(shakeKey);
    setShaking(true);
  }

  const boxes = pinBoxCount(value.length, length);
  // A basis, not a width: eight boxes and their gaps are wider than a narrow
  // phone's column, so they shrink with it and stay inside the input's area.
  const box = size === "lg" ? "h-14 basis-11 sm:h-16 sm:basis-12" : "h-12 basis-10";

  return (
    <div
      className={`relative inline-flex max-w-full gap-1.5 sm:gap-2 ${shaking ? "pin-shake" : ""}`}
      onAnimationEnd={() => setShaking(false)}
    >
      {Array.from({ length: boxes }, (_, index) => {
        const filled = index < value.length;
        const current = focused && !disabled && (index === value.length || (index === boxes - 1 && value.length >= boxes));
        return (
          <span
            key={index}
            aria-hidden="true"
            className={`flex min-w-0 shrink grow-0 items-center justify-center rounded-xl border-2 transition-colors duration-fast ${box}`}
            style={{
              borderColor: invalid ? "var(--status-bad)" : current ? "var(--focus-ring)" : filled ? "var(--border-strong)" : "var(--border-field)",
              background: "var(--bg-1)",
              opacity: disabled ? 0.5 : 1,
            }}
          >
            {filled && <span className="h-2.5 w-2.5 rounded-full" style={{ background: "var(--text)" }} />}
          </span>
        );
      })}
      <input
        id={inputId}
        type="password"
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete={autoComplete}
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        autoFocus={autoFocus}
        disabled={disabled}
        value={value}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onChange={(e) => {
          const digits = e.target.value.replace(/\D/g, "");
          // One digit past the end is a key pressed once too often, and is
          // dropped as the TV drops it. More than that is a paste, and a
          // 9-digit PIN cut to 8 would be a PIN nobody chose.
          if (digits.length > max) {
            if (digits.length - value.length > 1) onReject?.();
            return;
          }
          onChange(digits);
          if (onComplete && digits.length === max && digits !== value) onComplete(digits);
        }}
        // Over the boxes and invisible: a tap anywhere on the row lands in it.
        // The caret and the characters would be drawn twice otherwise.
        className="absolute inset-0 h-full w-full cursor-text opacity-0 disabled:cursor-not-allowed"
        style={{ caretColor: "transparent" }}
      />
    </div>
  );
}
