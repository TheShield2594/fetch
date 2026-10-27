import { useEffect, useState } from "react";
import { ExternalLink } from "lucide-react";
import { MDBLIST_RATING_LABELS, type MdbListRatingSource } from "@fetch/shared/tv-settings";
import { api, type MdbListRating, type MediaType } from "../../api";
import { ImdbLogo, McIcon, RtLogo, TmdbLogo } from "./RatingLogos";
import { KICKER } from "../typography";

/**
 * The profile's MDBList ratings for a title, in its order. Empty while they
 * load, and for good when the profile has them off or has nothing to read them
 * with; the panel shows its own scores in that case.
 */
export function useMdbListRatings(imdbId: string, type: MediaType): { ratings: MdbListRating[]; loading: boolean } {
  const identity = `${type}:${imdbId}`;
  // Keyed by the title it answers, so a reply for the previous title can't be
  // shown against this one.
  const [state, setState] = useState<{ identity: string; ratings: MdbListRating[] } | null>(null);

  useEffect(() => {
    let current = true;
    void (async () => {
      let ratings: MdbListRating[] = [];
      try {
        ratings = (await api.getMdbListRatings(type, imdbId)).ratings;
      } catch {
        // Decoration: the panel's own scores stand in.
      }
      if (current) setState({ identity: `${type}:${imdbId}`, ratings });
    })();
    return () => {
      current = false;
    };
  }, [imdbId, type]);

  if (state?.identity !== identity) return { ratings: [], loading: true };
  return { ratings: state.ratings, loading: false };
}

const PERCENT = new Set<MdbListRatingSource>(["tomatoes", "audience", "trakt", "tmdb"]);

/** Out of what each source scores: MDBList passes each on its own scale. */
const SCALE: Partial<Record<MdbListRatingSource, number>> = { imdb: 10, mal: 10, letterboxd: 5, metacritic: 100 };

const formatValue = (source: MdbListRatingSource, value: number): string =>
  source === "imdb" || source === "mal" || source === "letterboxd" ? value.toFixed(1) : String(Math.round(value));

const spokenRating = (rating: MdbListRating): string => {
  const label =
    rating.source === "tomatoes" && rating.certified
      ? "Rotten Tomatoes, Certified Fresh"
      : rating.source === "audience" && rating.certified
        ? "Rotten Tomatoes audience, Verified Hot"
        : rating.source === "audience"
          ? "Rotten Tomatoes audience"
          : MDBLIST_RATING_LABELS[rating.source];
  const value = formatValue(rating.source, rating.value);
  return PERCENT.has(rating.source) ? `${label} ${value}%` : `${label} ${value} out of ${SCALE[rating.source]}`;
};

function TextBadge({ label, background, color = "#fff" }: { label: string; background: string; color?: string }) {
  return (
    <span
      className="inline-flex items-center justify-center rounded px-1.5 py-0.5 text-[10px] font-black leading-none"
      style={{ background, color }}
    >
      {label}
    </span>
  );
}

function SourceMark({ rating }: { rating: MdbListRating }) {
  switch (rating.source) {
    case "imdb":
      return <ImdbLogo />;
    case "tmdb":
      return <TmdbLogo />;
    case "tomatoes":
      return <RtLogo score={rating.value} />;
    case "metacritic":
      return <McIcon score={rating.value} />;
    case "audience":
      return <TextBadge label={rating.value >= 60 ? "HOT" : "STALE"} background={rating.value >= 60 ? "#FA320A" : "#69BE28"} />;
    case "trakt":
      return <TextBadge label="trakt" background="#ED1C24" />;
    case "letterboxd":
      return <TextBadge label="LB" background="#202830" color="#40BCF4" />;
    case "mal":
      return <TextBadge label="MAL" background="#2E51A2" />;
  }
}

/** The MDBList counterpart of `ExternalRatings`, with the same heading and the IMDb score as its link. */
export function MdbListRatings({ imdbId, ratings }: { imdbId: string; ratings: MdbListRating[] }) {
  return (
    <div>
      <h3 className={`mb-2 ${KICKER}`} style={{ color: "var(--text-mute)" }}>Ratings</h3>
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-2" aria-label="Ratings from MDBList">
        {ratings.map((rating) => {
          const value = formatValue(rating.source, rating.value);
          const content = (
            <>
              <SourceMark rating={rating} />
              <span className="text-sm font-semibold" style={{ color: "var(--text)" }}>
                {value}
                {PERCENT.has(rating.source) && "%"}
              </span>
              {!PERCENT.has(rating.source) && (
                <span className="text-xs" style={{ color: "var(--text-mute)" }}>/{SCALE[rating.source]}</span>
              )}
            </>
          );
          return (
            <li key={rating.source}>
              {rating.source === "imdb" ? (
                <a
                  href={`https://www.imdb.com/title/${encodeURIComponent(imdbId)}/`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-1.5 rounded-md transition-opacity hover:opacity-80"
                  aria-label={`${spokenRating(rating)} — view on IMDb`}
                >
                  {content}
                  <ExternalLink className="h-3 w-3" style={{ color: "var(--text-mute)" }} />
                </a>
              ) : (
                <span className="flex items-center gap-1.5" role="img" aria-label={spokenRating(rating)}>
                  {content}
                </span>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
