import { Bookmark, BookmarkCheck, RotateCw } from "lucide-react";
import { DISPLAY_TITLE } from "../typography";
import type { TrendingMeta } from "../../api";
import { Poster } from "../Poster";
import { metaRun, kindWord } from "../PosterCard";
import { formatRating } from "../../utils/rating";
import { HERO_BACKDROP_SIZES } from "./hero";

/**
 * Discover's lead: one title the viewer has not seen, drawn large.
 *
 * It used to be a 300px band across the top of the page holding the show the
 * viewer was already in the middle of — a face cropped at the eyebrows, a
 * small title in the corner, and the one thing on the page they already knew
 * about. A discovery page leads with a discovery: roughly half to
 * three-fifths of the screen, so the backdrop is a picture rather than a
 * strip, and the title at the size of the thing it is.
 *
 * The whole card opens the title; the one button on it puts it on the
 * watchlist. Everything on the art is white over a fixed dark scrim — not a
 * theme colour, which on the light theme is a cream that vanishes into a
 * bright still.
 */

export type FeatureWatchlist = {
  state: "idle" | "busy" | "added";
  onAdd: () => void;
};

// Dark from the floor up and from the reading edge across: the title and the
// paragraph under it need a floor, and the picture still needs its middle.
const FEATURE_SCRIM =
  "linear-gradient(to top, rgba(0,0,0,0.88) 0%, rgba(0,0,0,0.55) 32%, rgba(0,0,0,0) 65%), linear-gradient(to right, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0) 55%)";

export function DiscoverFeature({
  item,
  reason,
  onOpen,
  watchlist,
}: {
  item: TrendingMeta;
  /** Why this title — "Trending this week". Said above the title. */
  reason: string;
  onOpen: () => void;
  /** Null when the profile has no watchlist to add to. */
  watchlist: FeatureWatchlist | null;
}) {
  const art = item.background ?? item.poster;
  const isBackdrop = Boolean(item.background);
  const genres = item.genres?.slice(0, 2).join(", ");
  const rating = item.rating != null && item.rating > 0 ? `TMDB ${formatRating(item.rating)}` : null;
  const meta = metaRun(kindWord(item.type), item.year, genres || null, rating);

  return (
    <section
      aria-labelledby="discover-feature-title"
      className="card-pressable relative isolate flex min-h-[26rem] flex-col justify-end overflow-hidden rounded-3xl sm:min-h-[50vh] lg:min-h-[min(60vh,42rem)]"
      style={{ background: "#111" }}
    >
      <Poster
        src={art}
        alt=""
        variant={isBackdrop ? "backdrop" : "poster"}
        eager
        sizes={HERO_BACKDROP_SIZES}
        className="absolute inset-0 h-full w-full"
      />
      <div aria-hidden="true" className="absolute inset-0" style={{ background: FEATURE_SCRIM }} />
      {/* The card's open target, under the caption. The caption is
          pointer-events-none above it and the watchlist button opts back in,
          because a button can't sit inside another button. */}
      <button
        type="button"
        onClick={onOpen}
        aria-label={`Open details for ${item.name}`}
        className="card-hit absolute inset-0 h-full w-full rounded-3xl focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-inset"
      />
      <div className="pointer-events-none relative z-10 max-w-2xl p-5 sm:p-8">
        <p className="eyebrow" style={{ color: "rgba(255,255,255,0.78)" }}>
          {reason}
        </p>
        <h2
          id="discover-feature-title"
          className={`mt-1.5 text-white ${DISPLAY_TITLE}`}
        >
          {item.name}
        </h2>
        {meta && (
          <p className="meta-row mt-2" style={{ color: "rgba(255,255,255,0.78)" }}>
            {meta}
          </p>
        )}
        {item.description && (
          <p
            className="mt-3 line-clamp-3 max-w-xl text-[0.9375rem] leading-relaxed"
            style={{ color: "rgba(255,255,255,0.86)" }}
          >
            {item.description}
          </p>
        )}
        {watchlist && (
          <button
            type="button"
            onClick={watchlist.onAdd}
            disabled={watchlist.state !== "idle"}
            className="pointer-events-auto mt-5 inline-flex min-h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold transition-transform duration-fast active:scale-[0.97] disabled:cursor-default focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
            // White on the art, as the landscape card's action is: it is the
            // one fill legible over a still of any colour.
            style={{ background: "rgba(255,255,255,0.94)", color: "#000" }}
          >
            {watchlist.state === "busy" ? (
              <RotateCw aria-hidden="true" className="h-4 w-4 animate-spin" />
            ) : watchlist.state === "added" ? (
              <BookmarkCheck aria-hidden="true" className="h-4 w-4" />
            ) : (
              <Bookmark aria-hidden="true" className="h-4 w-4" />
            )}
            {watchlist.state === "added" ? "On your watchlist" : "Add to watchlist"}
          </button>
        )}
      </div>
    </section>
  );
}
