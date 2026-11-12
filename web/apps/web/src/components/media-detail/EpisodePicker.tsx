import type { ChangeEvent } from "react";

/** What the pickers need of a season: its number, its name and how long it is. */
export type PickerSeason = { seasonNumber: number; name: string; episodeCount: number };

/** The seasons an episode can be picked from: the ones that have episodes. */
export function pickableSeasons(seasons: readonly PickerSeason[]): PickerSeason[] {
  return seasons.filter((s) => s.episodeCount > 0);
}

/**
 * The nearest episode to `wanted` that exists.
 *
 * With no season data there is nothing to check against, so the numbers come
 * back as they were (floored at 1). Otherwise the season is the requested one,
 * or the last one before it, or the first; and the episode is held inside that
 * season's count.
 */
export function clampEpisode(
  seasons: readonly PickerSeason[],
  wanted: { season: number; episode: number }
): { season: number; episode: number } {
  const real = pickableSeasons(seasons);
  const first = real[0];
  if (!first) return { season: Math.max(1, wanted.season), episode: Math.max(1, wanted.episode) };
  const season =
    real.find((s) => s.seasonNumber === wanted.season) ??
    real.filter((s) => s.seasonNumber < wanted.season).at(-1) ??
    first;
  return {
    season: season.seasonNumber,
    episode: Math.min(Math.max(1, wanted.episode), season.episodeCount),
  };
}

const FIELD_CLASS = "w-full rounded-xl border px-3 py-2.5 text-sm field-control";
const FIELD_STYLE = { borderColor: "var(--border-field)", background: "var(--surface)", color: "var(--text)" };
const LABEL_CLASS = "mb-1.5 block text-xs font-medium";

/**
 * Season and episode, chosen from the seasons the show has.
 *
 * These were two free number fields, in a dialog opened from a panel that
 * already had the season list and every season's episode count: "season 9
 * episode 400" of a two-season show was accepted and logged. With season data
 * they are two selects, and a number that does not exist cannot be picked.
 *
 * The number fields remain for the one case with nothing to bound them by — a
 * show TMDB has no seasons for, or a bundle that failed to load. Refusing to
 * log anything there would be worse than accepting a typed number.
 */
export function EpisodePicker({
  idPrefix,
  seasons,
  season,
  episode,
  onChange,
}: {
  /** Prefixes the two field ids, so two dialogs' fields cannot share one. */
  idPrefix: string;
  seasons: readonly PickerSeason[];
  season: number;
  episode: number;
  onChange: (next: { season: number; episode: number }) => void;
}) {
  const real = pickableSeasons(seasons);
  const current = real.find((s) => s.seasonNumber === season);
  const seasonId = `${idPrefix}-season`;
  const episodeId = `${idPrefix}-episode`;
  const typed = (e: ChangeEvent<HTMLInputElement>) => Math.max(1, parseInt(e.target.value, 10) || 1);

  return (
    <div className="flex gap-3">
      <div className="min-w-0 flex-1">
        <label htmlFor={seasonId} className={LABEL_CLASS} style={{ color: "var(--text-mute)" }}>Season</label>
        {current ? (
          <select
            id={seasonId}
            value={season}
            // A new season starts at its first episode: carrying "episode 14"
            // from a long season into a short one names nothing.
            onChange={(e) => onChange({ season: Number(e.target.value), episode: 1 })}
            className={FIELD_CLASS}
            style={FIELD_STYLE}
          >
            {real.map((s) => (
              <option key={s.seasonNumber} value={s.seasonNumber}>{s.name}</option>
            ))}
          </select>
        ) : (
          <input
            id={seasonId}
            type="number"
            min={1}
            value={season}
            onChange={(e) => onChange({ season: typed(e), episode })}
            className={FIELD_CLASS}
            style={FIELD_STYLE}
          />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <label htmlFor={episodeId} className={LABEL_CLASS} style={{ color: "var(--text-mute)" }}>Episode</label>
        {current ? (
          <select
            id={episodeId}
            value={episode}
            onChange={(e) => onChange({ season, episode: Number(e.target.value) })}
            className={FIELD_CLASS}
            style={FIELD_STYLE}
          >
            {Array.from({ length: current.episodeCount }, (_, i) => i + 1).map((n) => (
              <option key={n} value={n}>Episode {n}</option>
            ))}
          </select>
        ) : (
          <input
            id={episodeId}
            type="number"
            min={1}
            value={episode}
            onChange={(e) => onChange({ season, episode: typed(e) })}
            className={FIELD_CLASS}
            style={FIELD_STYLE}
          />
        )}
      </div>
    </div>
  );
}
