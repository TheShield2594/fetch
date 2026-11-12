import type { ReactNode } from "react";
import { BookmarkCheck } from "lucide-react";
import { Poster } from "./Poster";
import { MyRating } from "./MyRating";
import { formatRating, ratingLabel } from "../utils/rating";

/**
 * The poster card: the one way a title is drawn as a poster anywhere in the app.
 *
 * The same title used to change shape on every page — a type badge and a heart
 * on Search, a different badge and a trash can on Lists, an amber disc on
 * Discover, a raw `movie` caption on Stats, and a hand-rolled 7rem button under
 * More Like This. Each was reasonable on its own page and together they made
 * one object look like five.
 *
 * So the card owns the frame *and* the vocabulary, and a page only chooses
 * which named slots to fill:
 *
 *   caption      `title` and `meta` under the frame, always 13px over the
 *                12px meta run, always the same gap.
 *   rating       `{ source: "tmdb" }` is a number with whose it is beside it
 *                ("TMDB 7.8"); `{ source: "mine" }` is the row of stars you
 *                filled in, in the accent. They sit in the same place and never
 *                look alike — a community score and your own are different
 *                facts, and drawing both as an amber badge is how a 4 out of 5
 *                read as worse than a 6 out of 10.
 *   membership   `inLists`: a small bookmark on the art's top-right corner,
 *                and the list names in the accessible name. Not a heart, not a
 *                chip: it is a fact about your library, and it is quiet.
 *   marker       top-left of the art, for the one thing that is about *why*
 *                the card is here — an AI pick, a rank.
 *   progress     along the bottom of the art, where a scrubber would be.
 *   quickAction  bottom-right of the art: the one thing you can do without
 *                opening the title (add to a list).
 *   cornerAction top-right of the art: the one thing that takes it away
 *                (remove from this list). Replaces the membership mark, which
 *                would only say the obvious on a list's own page.
 *
 * There is no type badge. A coloured MOVIE/SERIES pill over the picture says
 * what the meta run already says quietly; pages that need the kind put it
 * first in `meta`.
 */

export type CardRating = { source: "tmdb" | "mine"; value: number };

export function CardRatingMark({ rating }: { rating: CardRating }) {
  if (rating.source === "mine") return <MyRating rating={rating.value} size={11} />;
  const label = `TMDB: ${ratingLabel(rating.value)}`;
  return (
    // Proportional figures: the meta run is tabular, which gives the decimal
    // point a digit's width and reads "7 . 4".
    <span role="img" aria-label={label} title={label} className="proportional-nums">
      <span aria-hidden="true">TMDB {formatRating(rating.value)}</span>
    </span>
  );
}

/** The caption's two text styles, exported for the landscape card and the skeletons. */
export const CARD_TITLE = "mt-2 truncate text-[0.8125rem] font-semibold";
export const CARD_META = "meta truncate";

export function PosterCard({
  poster,
  name,
  onOpen,
  openLabel,
  eager = false,
  sizes,
  hover = true,
  title,
  meta,
  rating,
  inLists,
  marker,
  progress,
  quickAction,
  cornerAction,
  note,
  className = "",
  children,
}: {
  poster: string | null | undefined;
  /** Used as the poster's alt text, the caption's title and, by default, the button's label. */
  name: string;
  /** Omit for a card that is not itself a button. */
  onOpen?: (() => void) | undefined;
  /** Defaults to "View details for <name>". */
  openLabel?: string | undefined;
  eager?: boolean | undefined;
  /** Omit inside a `PosterGrid` or a rail that provides `PosterSizesContext`. */
  sizes?: string | undefined;
  /** The frame lifts slightly on hover. Off for a card inside a dense list. */
  hover?: boolean | undefined;
  /** The caption's first line. Defaults to `name`; `null` draws no caption at all. */
  title?: ReactNode | null | undefined;
  /** The caption's second line: kind, year, episodes — joined with " · " by the caller. */
  meta?: ReactNode | undefined;
  rating?: CardRating | null | undefined;
  /** Names of the lists this title is in. Empty or omitted: no mark. */
  inLists?: readonly string[] | undefined;
  marker?: ReactNode | undefined;
  progress?: ReactNode | undefined;
  /**
   * Controls laid over the art. They land in a box the size of the frame that
   * takes no clicks, so each control has to say `pointer-events-auto` and place
   * itself; the slot only decides the corner.
   */
  quickAction?: ReactNode | undefined;
  cornerAction?: ReactNode | undefined;
  /** A third caption line, clamped to two — the reason a recommendation is here. */
  note?: string | undefined;
  /** Extra classes for the outer wrapper — a fixed width, a flex rule. */
  className?: string | undefined;
  /** Anything that hangs off the caption: a quick-add menu. */
  children?: ReactNode | undefined;
}) {
  const listed = !cornerAction && inLists && inLists.length > 0;
  const captionTitle = title === undefined ? name : title;

  return (
    <div className={`group relative rounded-2xl ${className}`.trimEnd()}>
      {/* A real button stretched over the whole card: Enter and Space, and the
          announcement assistive tech expects, for free. `overflow-hidden`
          belongs to the frame below rather than here, so the poster's hover
          scale is clipped without also clipping this button's focus ring. */}
      {onOpen && (
        <button
          type="button"
          onClick={onOpen}
          aria-label={openLabel ?? `View details for ${name}`}
          className="absolute inset-0 z-10 cursor-pointer rounded-2xl focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
        />
      )}
      <div
        className={`poster-frame relative aspect-poster overflow-hidden rounded-2xl${hover ? " group-hover:scale-[1.03]" : ""}`}
      >
        <Poster src={poster ?? undefined} alt={name} className="h-full w-full" eager={eager} sizes={sizes} />
        {marker && <div className="absolute left-2 top-2">{marker}</div>}
        {listed && (
          // Decorative: the names are in the caption's sr-only text.
          <span
            aria-hidden="true"
            title={`In ${inLists.join(", ")}`}
            className="absolute right-2 top-2 flex h-7 w-7 items-center justify-center rounded-full text-white"
            // Fixed rather than themed: it sits on artwork of unknown colour.
            style={{ background: "rgba(0,0,0,0.6)" }}
          >
            <BookmarkCheck className="h-3.5 w-3.5" />
          </span>
        )}
        {progress && <div className="absolute inset-x-2 bottom-2">{progress}</div>}
      </div>
      {(quickAction || cornerAction) && (
        <div className="pointer-events-none absolute inset-x-0 top-0 z-20 aspect-poster">
          {cornerAction && <div className="absolute right-2 top-2">{cornerAction}</div>}
          {quickAction && <div className="absolute bottom-2 right-2">{quickAction}</div>}
        </div>
      )}
      {children}
      {captionTitle !== null && (
        <>
          <p className={CARD_TITLE} style={{ color: "var(--text)" }}>
            {captionTitle}
          </p>
          {(meta || rating) && (
            <p className={CARD_META} style={{ color: "var(--text-mute)" }}>
              {meta}
              {meta && rating && " · "}
              {rating && <CardRatingMark rating={rating} />}
            </p>
          )}
          {listed && <span className="sr-only">In {inLists.join(", ")}</span>}
          {note && (
            <p className="mt-0.5 line-clamp-2 text-2xs italic leading-snug" style={{ color: "var(--text-dim)" }} title={note}>
              {note}
            </p>
          )}
        </>
      )}
    </div>
  );
}

/** The skeleton of one poster card, the same shape the card lands in. */
export function PosterCardSkeleton() {
  return (
    <div aria-hidden="true">
      <div className="skeleton aspect-poster rounded-2xl" />
      <div className="skeleton mt-2 h-3.5 w-3/4 rounded" />
      <div className="skeleton mt-1 h-3 w-1/2 rounded" />
    </div>
  );
}

/**
 * The kind, as the first token of a card's meta run. "Show" and "Film"
 * everywhere — the Shelf said Show, Stats said Series and Search said Movie,
 * three words for two things.
 */
export const kindWord = (type: string): "Show" | "Film" => (type === "movie" ? "Film" : "Show");

/** Parts joined with the separator every meta run uses; empty parts drop out. */
export const metaRun = (...parts: (string | number | null | undefined | false)[]): string =>
  parts
    .filter((part): part is string | number => (typeof part === "string" && part.length > 0) || typeof part === "number")
    .join(" · ");
