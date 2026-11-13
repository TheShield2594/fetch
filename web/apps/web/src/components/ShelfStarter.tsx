import { type ReactNode, useEffect, useState } from "react";
import { Check, Loader2, Plus } from "lucide-react";
import { api, type CatalogList, type TrendingMeta } from "../api";
import { PosterCard, PosterCardSkeleton } from "./PosterCard";
import { useTitlePanel } from "./media-detail/titlePanel";
import { SECTION_TITLE } from "./typography";

/** Enough for two rows on a desktop and three on a phone, without a scroll of homework. */
export const STARTER_LIMIT = 12;

/**
 * Films and shows alternated, so the first row isn't all one kind, and each
 * title once (trending can carry the same id in both feeds).
 */
export function interleaveTrending(movies: TrendingMeta[], series: TrendingMeta[], limit = STARTER_LIMIT): TrendingMeta[] {
  const out: TrendingMeta[] = [];
  const seen = new Set<string>();
  for (let i = 0; out.length < limit && (i < movies.length || i < series.length); i++) {
    for (const item of [movies[i], series[i]]) {
      if (!item || !item.poster || seen.has(item.id) || out.length >= limit) continue;
      seen.add(item.id);
      out.push(item);
    }
  }
  return out;
}

type AddState = "busy" | "added" | "failed";

/**
 * The Shelf's first-run state: this week's trending titles, in artwork, each
 * one tap from the watchlist.
 *
 * The first screen after setup used to be "Nothing on the shelf yet" and two
 * buttons — no artwork at all, at the moment of first impression. With a TMDB
 * key there is always something to show, so the empty Shelf shows it. Without
 * one (trending fails, or comes back empty) `fallback` — the plain empty state
 * — is shown instead.
 *
 * Adding doesn't reload the Shelf: one tap would swap this grid for a library
 * of one. "Show my shelf" does that, once something has been added.
 */
export function ShelfStarter({
  fallback,
  footer,
  onDone,
}: {
  /** What to show when there is no artwork to offer. */
  fallback: ReactNode;
  /** The other ways in, under the grid. */
  footer?: ReactNode | undefined;
  onDone: () => void;
}) {
  const { openTitle } = useTitlePanel();
  const [items, setItems] = useState<TrendingMeta[] | null>(null);
  const [watchlist, setWatchlist] = useState<CatalogList | null>(null);
  const [failed, setFailed] = useState(false);
  const [states, setStates] = useState<Record<string, AddState>>({});

  useEffect(() => {
    let cancelled = false;
    // Each call inside a `then`, so one that throws as it is made is a
    // rejection like any other rather than an exception out of the effect.
    const ask = <T,>(read: () => Promise<T>) => Promise.resolve().then(read);
    void Promise.allSettled([ask(() => api.getTrending("movie")), ask(() => api.getTrending("series")), ask(() => api.getLists())]).then(
      ([movies, series, lists]) => {
        if (cancelled) return;
        const picked = interleaveTrending(
          movies.status === "fulfilled" ? movies.value.metas : [],
          series.status === "fulfilled" ? series.value.metas : []
        );
        if (picked.length === 0) setFailed(true);
        setItems(picked);
        if (lists.status === "fulfilled") setWatchlist(lists.value.lists.find((l) => l.kind === "watchlist") ?? null);
      }
    );
    return () => {
      cancelled = true;
    };
  }, []);

  if (failed) return <>{fallback}</>;

  const add = async (item: TrendingMeta) => {
    if (!watchlist || states[item.id] === "busy" || states[item.id] === "added") return;
    setStates((prev) => ({ ...prev, [item.id]: "busy" }));
    try {
      await api.addToList(watchlist.id, { type: item.type, imdbId: item.id, title: item.name });
      setStates((prev) => ({ ...prev, [item.id]: "added" }));
    } catch {
      setStates((prev) => ({ ...prev, [item.id]: "failed" }));
    }
  };

  const addedCount = Object.values(states).filter((s) => s === "added").length;
  // Held while an add is in flight, so the Shelf's reload can't land before
  // it and leave that title off until the next visit.
  const adding = Object.values(states).some((s) => s === "busy");

  return (
    <section aria-labelledby="shelf-starter" className="content-in mt-2 space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div className="min-w-0">
          <h2 id="shelf-starter" className={SECTION_TITLE} style={{ color: "var(--text)" }}>
            Start your shelf
          </h2>
          <p className="mt-1 text-[0.9375rem]" style={{ color: "var(--text-dim)" }}>
            {watchlist
              ? "Trending this week. Tap + on anything you want to watch: it goes on your watchlist, and your TV sees it too."
              : "Trending this week. Open one to add it to a list."}
          </p>
        </div>
        {/* Polite, so "3 added" is heard without stealing focus from the grid. */}
        <div className="flex items-center gap-3" aria-live="polite">
          {addedCount > 0 && (
            <>
              <span className="meta-row" style={{ color: "var(--text-mute)" }}>
                {addedCount} added
              </span>
              <button type="button" onClick={onDone} disabled={adding} className="btn-primary btn-sm">
                Show my shelf
              </button>
            </>
          )}
        </div>
      </div>

      <ul className="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {items === null
          ? Array.from({ length: STARTER_LIMIT }, (_, i) => (
              <li key={i}>
                <PosterCardSkeleton />
              </li>
            ))
          : items.map((item, index) => {
              const state = states[item.id];
              const label =
                state === "added"
                  ? `${item.name} is on your watchlist`
                  : state === "failed"
                    ? `Couldn't add ${item.name}. Try again`
                    : `Add ${item.name} to your watchlist`;
              return (
                <li key={item.id}>
                  <PosterCard
                    poster={item.poster}
                    name={item.name}
                    eager={index < 6}
                    meta={[item.type === "series" ? "Show" : "Film", item.year].filter(Boolean).join(" · ")}
                    onOpen={() =>
                      openTitle({
                        imdbId: item.id,
                        type: item.type,
                        name: item.name,
                        year: item.year ?? null,
                        poster: item.poster ?? null,
                        description: item.description ?? null,
                        genres: item.genres ?? [],
                        rating: item.rating ?? null,
                        inWatchlist: state === "added",
                        inCollection: false,
                        lists: [],
                        background: item.background ?? null,
                      })
                    }
                    quickAction={
                      watchlist ? (
                        <button
                          type="button"
                          onClick={() => void add(item)}
                          aria-label={label}
                          title={label}
                          aria-pressed={state === "added"}
                          disabled={state === "busy"}
                          className="pointer-events-auto flex h-9 w-9 items-center justify-center rounded-full shadow-e2 transition-transform duration-fast active:scale-95 focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                          style={
                            state === "added"
                              ? { background: "rgb(var(--accent-rgb))", color: "white" }
                              : { background: "rgba(0,0,0,0.65)", color: "white" }
                          }
                        >
                          {state === "busy" ? (
                            <Loader2 size={16} className="animate-spin" />
                          ) : state === "added" ? (
                            <Check size={16} strokeWidth={2.5} />
                          ) : (
                            <Plus size={18} strokeWidth={2.5} />
                          )}
                        </button>
                      ) : undefined
                    }
                  />
                </li>
              );
            })}
      </ul>

      {footer}
    </section>
  );
}
