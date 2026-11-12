/* The cinematic hero's shared parts: the scrim and the backdrop's `sizes`. */

/**
 * The scrim over a hero's backdrop: solid under the text, clear over the art.
 *
 * Bottom-up rather than diagonal, because the cinematic hero anchors its title
 * and actions at the bottom edge over a full-bleed backdrop — the streaming
 * treatment, where the art fills the card and the words sit in a well of shade
 * that rises from the floor of it. The bottom ~fifth resolves to a solid --bg-0
 * so the card melts into the page below it rather than ending on a hard line,
 * the same "content continues under this" move the tab bar and sheets make.
 *
 * Only painted when there is a real TMDB backdrop to darken. It used to be
 * unconditional, over a blurred copy of the poster when `background` was null —
 * and with no art behind it the ramp is just --bg-0 fading to the page, which
 * on the light theme runs cream to grey-brown across the card holding nothing
 * and reads as a rendering fault rather than a choice. A hero without a
 * backdrop is a flat panel like every other one on the page instead.
 *
 * It fades to --bg-0 (the page colour) and the text over it is --text, so the
 * pairing is legible on every theme without the scrim naming a colour of its
 * own: black text in a light well on the light theme, white in a dark one on
 * the dark themes.
 *
 * Used by the Now Watching hero and Discover's lead feature.
 */
export const HERO_SCRIM =
  "linear-gradient(to top, var(--bg-0) 6%, color-mix(in srgb, var(--bg-0) 82%, transparent) 30%, color-mix(in srgb, var(--bg-0) 30%, transparent) 58%, transparent 88%)";

/**
 * `sizes` for a hero's backdrop: the page column, which is the viewport less
 * its gutters up to the content's 1400px cap. Literal lengths, because the
 * preload scanner reads this before any stylesheet.
 */
export const HERO_BACKDROP_SIZES = "(min-width: 1400px) 1350px, 100vw";
