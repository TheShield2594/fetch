import { useCallback, useEffect, useRef, useState } from "react";
import { api, runtimeConfig, type SeriesProgress } from "../api";
import { useToast } from "./useToast";

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
 * `onChanged` is how the page refetches once the mark (or its undo) lands. It
 * is skipped once the page has gone: the request can outlive the screen that
 * made it, and a refetch for a page nobody is looking at writes its answer
 * into whichever profile's cache is current by the time it lands.
 */
export function useMarkNextEpisode(onChanged: () => unknown) {
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

  const markNext = useCallback(
    async (s: SeriesProgress) => {
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
        const marked = await api.markNextEpisodeWatched(imdbId);
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

  return { markNext, isMarking };
}
