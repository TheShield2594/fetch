/**
 * The app's heading scale, as four class strings.
 *
 * A heading's type is a decision about the product, not about the screen it
 * happens to sit on, so it belongs in one place that every screen reads from. Before this file the same
 * four roles were spelled eleven different ways — page titles were `text-2xl
 * font-bold` on six pages but `font-heading text-2xl font-extrabold
 * tracking-tight` on the hero and the detail panels, section headers were
 * `font-bold` on Discover, `font-semibold` on stats and a size smaller
 * again in settings, and the uppercase labels drifted across four size/weight
 * pairs, twice within a single panel.
 *
 * Each constant carries size, weight and tracking together, because those three
 * are what make a tier recognisable — splitting them is how the drift started.
 * Colour stays with the caller: the same tier is `--text` on a page, `--text-mute`
 * over a poster and `--accent-text` on a live badge.
 *
 * Five tiers, loudest first.
 */

/**
 * The display rank: one element per page, for the moment a screen should feel
 * like a cinema rather than a form — the Discover feature, the lead card on the
 * Shelf, a title's own header.
 *
 * Fluid between 44px on a phone and 64px on a wide screen, so it is always a
 * clear step above `PAGE_TITLE` (whose 34px was the largest type in the app,
 * shared by a film's name and the word "Settings"). Extra-bold with tight
 * leading and negative tracking, because at this size the face's own spacing
 * reads as loose. Callers that sit in a narrow column cap it (`lg:text-…`).
 *
 * If a screen wants two, the second is a `PAGE_TITLE`.
 */
export const DISPLAY_TITLE =
  "text-[clamp(2.75rem,1.9rem+3.6vw,4rem)] font-extrabold leading-[1.02] tracking-[-0.025em] [text-wrap:balance]";

/**
 * The loudest thing on a screen: page `h1`s, and the title of whatever a panel
 * or an empty state has been opened to show.
 *
 * Size is what separates a title from a merely large label, not weight: this is
 * the platform's large title — 34px at `sm` and up, 28px on a phone, where 34
 * over a 375px column leaves "Watch Statistics" nowhere to break. Tracking goes
 * *out* slightly at the large size and in at the small one, which is what the
 * system face does across the same range.
 *
 * Only one of these should be visible at a time; if a screen wants two, one of
 * them is a `SECTION_TITLE`.
 */
export const PAGE_TITLE =
  "text-[1.75rem] font-bold leading-[1.15] tracking-[-0.01em] sm:text-[2.125rem] sm:leading-[1.2] sm:tracking-[0.01em]";

/**
 * The header of a card, a panel, a modal or a run of content under a page title.
 *
 * 22px bold — the platform's title2, and the size a section header takes above
 * a shelf of cards. A clear step down from the large title so the two never
 * compete, and clearly not body copy: at 17px a bold line reads as an emphatic
 * sentence rather than as the name of what is under it.
 */
export const SECTION_TITLE = "text-[1.375rem] font-bold leading-tight tracking-[-0.01em]";

/**
 * The label that names a block inside a panel — "Overview", "Cast", "Now
 * watching", "Seasons".
 *
 * Sentence case, at 15px semibold: a small section head rather than a tag.
 * These used to be 13px uppercase with opened-out tracking, and with a dozen of
 * them across a panel ("SERIES · IN PROGRESS", "YOUR TRACKING") the app read as
 * generic UI labelling. Uppercase is kept for what really is a tag — a
 * `MICRO_LABEL` on a control or a filled badge on a poster.
 *
 * Still smaller than `SECTION_TITLE`, because these appear several to a panel
 * and would out-shout the title they sit under at heading size.
 */
export const KICKER = "text-[0.9375rem] font-semibold leading-snug tracking-[-0.005em]";

/**
 * The same idea one notch quieter: form-field labels, table and grid column
 * headings, the caption on a stat tile, the group headers in the command
 * palette.
 *
 * The distinction from `KICKER` is what it labels, not how loud it is — a
 * kicker names a section of content, a micro label names a single control or
 * value. Reach for this when the label sits *inside* a component rather than
 * above one.
 */
export const MICRO_LABEL = "text-2xs font-semibold uppercase tracking-[0.04em]";

/*
 * Two kinds of uppercase text deliberately sit outside this file.
 *
 * The filled badges — the "MOVIE"/"SERIES"/"PLAYING" chips on posters and panel
 * headers — are `uppercase tracking-wide` on a coloured fill. They read as a
 * shape rather than as type, and a chip's tracking has to stay tighter than a
 * label's or the pill grows wide enough to cover the artwork underneath.
 *
 * The brand tagline in `BrandLockup` runs at `tracking-[0.2em]`, far wider than
 * `MICRO_LABEL`. That is the lockup's spacing, not the UI's, and it belongs with
 * the mark it is set under.
 */
