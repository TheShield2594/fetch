import { useCallback, useEffect, useRef, useState } from "react";
import { api, runtimeConfig, type CheckIn, type SeriesProgress } from "../api";
import { useToast } from "./useToast";
import { WatchDateModal } from "../components/media-detail/WatchDateModal";
import type { WatchWhen } from "../components/media-detail/detailPanelUtils";
import { loadEpisodeAirDate } from "../utils/releaseDates";

/**
 * "Mark the next episode watched" — the one implementation of it.
 *
 * The Shelf and Discover used to answer the same tap differently: the Shelf
 * raised a toast, Discover flipped its button to "Marked" for 1.2 seconds and
 * reloaded behind a timer of its own. Same action, two kinds of feedback, and
 * only one of them said which episode it had been. Now both raise the same
 * toast, naming the episode, with an Undo that takes exactly that episode back
 * off.
 *
 * The tap opens the watch dialog first — when was it watched: now, on its air
 * date, some other time, or not known — and the mark goes in from there. The
 * page renders `markDialog` wherever it likes; it is null while closed.
 *
 * `onChanged` is how the page refetches once the mark (or its undo) lands. It
 * is skipped once the page has gone: the request can outlive the screen that
 * made it, and a refetch for a page nobody is looking at writes its answer
 * into whichever profile's cache is current by the time it lands.
 *
 * `checkIn` is the dialog's "Now watching": `onCheckedIn` hears the check-in it
 * starts, and `canCheckIn: false` (one is already running) leaves it out.
 */
export function useMarkNextEpisode(
  onChanged: () => unknown,
  checkIn: { canCheckIn: boolean; onCheckedIn: (checkin: CheckIn) => void } = { canCheckIn: false, onCheckedIn: () => {} }
) {
  const { showToast } = useToast();
  const [marking, setMarking] = useState<ReadonlySet<string>>(() => new Set());

  // The latest callback, read at the time it is needed: an Undo can be pressed
  // several renders after the mark that raised it.
  const onChangedRef = useRef(onChanged);
  useEffect(() => {
    onChangedRef.current = onChanged;
  }, [onChanged]);

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const changed = useCallback(async () => {
    if (mountedRef.current) await onChangedRef.current();
  }, []);

  const logNext = useCallback(
    async (s: SeriesProgress, when: WatchWhen) => {
      const { imdbId, name } = s;
      // The profile the mark is recorded against. The toast outlives the page
      // and survives a profile switch, and the Undo's DELETE would otherwise go
      // out under whichever profile is active when it is pressed.
      const profileId = runtimeConfig.getProfileId();
      setMarking((prev) => new Set(prev).add(imdbId));
      try {
        // What the server marked, which is not always what the card showed: a
        // card can be stale if another device moved the show on. An API from
        // before the answer had a body (204) can't say, so that toast names no
        // episode and offers no Undo — one aimed at the card's guess could take
        // back the wrong episode.
        const marked = await api.markNextEpisodeWatched(imdbId, {
          watchedAt: when.watchedAt,
          dateUnknown: when.dateUnknown,
        });
        if (!marked) {
          showToast(`Marked the next episode of ${name}`, "success");
        } else {
          const { season, episode: episodeNumber } = marked;
          const episode = `S${season}:E${episodeNumber}`;
          showToast(`Marked ${episode} of ${name}`, "success", {
            action: {
              label: "Undo",
              onAction: () => {
                if (runtimeConfig.getProfileId() !== profileId) {
                  showToast(`Couldn't undo: ${episode} of ${name} was marked on another profile`, "error");
                  return;
                }
                void api
                  .unmarkEpisodeWatched(imdbId, season, episodeNumber)
                  .then(changed)
                  .catch((err: unknown) =>
                    showToast(err instanceof Error ? err.message : `Couldn't unmark ${episode}`, "error")
                  );
              },
            },
          });
        }
        // The whole in-progress block moves when an episode lands — the show
        // can finish a season, change position, or leave the list entirely —
        // so the page refetches rather than patching one row.
        await changed();
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Couldn't mark that episode", "error");
        // A failed mark (the series turned out to be finished already, say) can
        // leave a stale card; a refetch drops it.
        await changed();
      } finally {
        if (mountedRef.current) {
          setMarking((prev) => {
            const next = new Set(prev);
            next.delete(imdbId);
            return next;
          });
        }
      }
    },
    [changed, showToast]
  );

  const isMarking = useCallback((imdbId: string) => marking.has(imdbId), [marking]);

  /** The show the dialog is open for. */
  const [asking, setAsking] = useState<SeriesProgress | null>(null);
  const markNext = useCallback((s: SeriesProgress) => setAsking(s), []);

  const loadReleaseDate = useCallback(
    async (episode: { season: number; episode: number } | undefined) =>
      asking && episode ? loadEpisodeAirDate(asking.imdbId, episode.season, episode.episode) : null,
    [asking]
  );

  const markDialog = asking ? (
    <WatchDateModal
      target={{
        kind: "episode",
        seriesImdbId: asking.imdbId,
        season: asking.nextSeason,
        episode: asking.nextEpisode,
        fixed: true,
        // Not known here — looked up, below.
        airDate: undefined,
        name: asking.name,
      }}
      onLog={(when) => logNext(asking, when)}
      onWatchNow={!checkIn.canCheckIn ? undefined : async () => {
        const res = await api.startCheckin({
          type: "episode",
          imdbId: asking.imdbId,
          seriesImdbId: asking.imdbId,
          name: asking.name,
          poster: asking.poster,
          season: asking.nextSeason,
          episode: asking.nextEpisode,
        });
        if (mountedRef.current) checkIn.onCheckedIn(res.checkin);
        showToast(`Checked in to S${asking.nextSeason}:E${asking.nextEpisode} of ${asking.name}`, "info");
      }}
      loadReleaseDate={loadReleaseDate}
      onClose={() => setAsking(null)}
    />
  ) : null;

  return { markNext, isMarking, markDialog };
}
