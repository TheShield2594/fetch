import { useId } from "react";
import { Check } from "lucide-react";
import { THEMES, type ThemeOption, useThemeChoice } from "../hooks/useTheme";

/**
 * A theme, drawn as a small page of itself: its ground, a card on it, a line
 * of its text and a pill of its accent. Painted from fixed colours rather than
 * the live tokens, so every swatch shows its own theme whichever one is on.
 */
function Swatch({ theme, size }: { theme: ThemeOption; size: "card" | "dot" }) {
  const { bg, card, accent, text } = theme.swatch;
  if (size === "dot") {
    return (
      <span
        aria-hidden="true"
        className="flex h-8 w-8 items-center justify-center overflow-hidden rounded-full"
        style={{ background: `linear-gradient(135deg, ${bg} 50%, ${card} 50%)`, boxShadow: "inset 0 0 0 1px rgba(128,128,128,0.35)" }}
      >
        <span className="h-3 w-3 rounded-full" style={{ background: accent }} />
      </span>
    );
  }
  return (
    <span
      aria-hidden="true"
      className="flex aspect-[4/3] w-full flex-col justify-end gap-1.5 overflow-hidden rounded-lg p-2.5"
      style={{ background: bg, boxShadow: "inset 0 0 0 1px rgba(128,128,128,0.3)" }}
    >
      <span className="flex flex-col gap-1 rounded-md p-2" style={{ background: card }}>
        <span className="h-1.5 w-3/4 rounded-full" style={{ background: text, opacity: 0.85 }} />
        <span className="h-1.5 w-1/2 rounded-full" style={{ background: text, opacity: 0.4 }} />
      </span>
      <span className="h-3 w-10 rounded-full" style={{ background: accent }} />
    </span>
  );
}

/**
 * The theme choice, as swatches. A radio group under the hood — native radios,
 * so arrow keys move the choice and a screen reader says "1 of 5" — with each
 * option drawn as its theme rather than named in a list of words.
 *
 * `cards` is the Settings → Appearance layout, a preview and a line about each;
 * `dots` is the profile menu's row of circles, named by their labels.
 */
export function ThemePicker({ layout }: { layout: "cards" | "dots" }) {
  const { theme: current, chooseTheme } = useThemeChoice();
  const name = useId();

  return (
    <div
      role="radiogroup"
      aria-label="Theme"
      className={layout === "cards" ? "grid grid-cols-2 gap-3 sm:grid-cols-3" : "flex flex-wrap gap-2"}
    >
      {THEMES.map((theme) => {
        const checked = theme.id === current;
        return (
          <label
            key={theme.id}
            title={layout === "dots" ? theme.label : undefined}
            className={
              layout === "cards"
                ? "group relative flex cursor-pointer flex-col gap-2 rounded-xl p-1.5 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-focus"
                : "relative cursor-pointer rounded-full p-0.5 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-focus"
            }
            style={{
              boxShadow: checked ? "0 0 0 2px var(--accent-text)" : undefined,
              background: layout === "cards" && checked ? "var(--surface)" : undefined,
            }}
          >
            <input
              type="radio"
              name={name}
              value={theme.id}
              checked={checked}
              onChange={() => chooseTheme(theme.id)}
              className="sr-only"
              aria-label={theme.label}
              aria-describedby={layout === "cards" ? `${name}-${theme.id}` : undefined}
            />
            <Swatch theme={theme} size={layout === "cards" ? "card" : "dot"} />
            {layout === "cards" && (
              <span className="px-1 pb-1">
                <span className="flex items-center justify-between gap-2 text-sm font-semibold" style={{ color: "var(--text)" }}>
                  {theme.label}
                  {checked && <Check className="h-4 w-4 flex-none" aria-hidden="true" style={{ color: "var(--accent-text)" }} />}
                </span>
                <span id={`${name}-${theme.id}`} className="mt-0.5 block text-xs" style={{ color: "var(--text-dim)" }}>
                  {theme.description}
                </span>
              </span>
            )}
          </label>
        );
      })}
    </div>
  );
}
