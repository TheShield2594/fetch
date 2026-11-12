import type { ReactNode } from "react";
import { kindWord, metaRun, PosterCard } from "../PosterCard";
import { POSTER_CARD_FILL_SIZES, POSTER_CARD_SIZES } from "../Poster";

// `?: T | undefined` rather than `?: T`: every caller forwards a value that is
// already optional, and a missing prop and an undefined one are the same thing
// to React and to everything below. The distinction the flag exists for is
// made where it is real — a Prisma `data`, a `fetch` init, a Fastify option.
export type DiscoveryItem = {
  id: string;
  name: string;
  poster?: string | undefined;
  rating?: number | undefined;
  genres?: string[] | undefined;
  year?: number | undefined;
  type?: string | undefined;
  description?: string | undefined;
};

/**
 * A poster card in a discovery rail: the shared card with a TMDB score, and
 * the reason an AI pick was made when there is one.
 *
 * The title is said once, under the art. It used to be repeated on a scrim
 * over the poster as well, which on a phone (where the scrim never hid) put
 * the same words twice an inch apart.
 */
export function DiscoveryCard({ item, marker, reason, onSelect, eager, fill }: {
  item: DiscoveryItem;
  /** Top-left of the art — the AI mark, on the rails that have one. */
  marker?: ReactNode | undefined;
  reason?: string | undefined;
  onSelect?: ((item: DiscoveryItem) => void) | undefined;
  eager?: boolean | undefined;
  /** Fills the width of a grid cell instead of using a fixed carousel-card width. */
  fill?: boolean | undefined;
}) {
  return (
    <PosterCard
      poster={item.poster}
      name={item.name}
      {...(onSelect ? { onOpen: () => onSelect(item) } : {})}
      eager={eager}
      sizes={fill ? POSTER_CARD_FILL_SIZES : POSTER_CARD_SIZES}
      className={fill ? "w-full" : "w-poster-card flex-none"}
      meta={metaRun(item.type ? kindWord(item.type) : null, item.year)}
      rating={item.rating != null && item.rating > 0 ? { source: "tmdb", value: item.rating } : null}
      marker={marker}
      note={reason}
    />
  );
}
