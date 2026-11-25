import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { Poster } from "../components/Poster";
import { PosterGrid } from "../components/PosterGrid";
import { PAGE_TITLE } from "../components/typography";
import { apiUrl } from "../utils/shareLinks";

type SharedItem = {
  type: "movie" | "series";
  imdbId: string;
  name: string;
  year: number | null;
  poster: string | null;
};

type SharedList = {
  list: { name: string; description: string | null; itemCount: number };
  items: SharedItem[];
};

type State = { status: "loading" } | { status: "ready"; data: SharedList } | { status: "missing" } | { status: "failed" };

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? "" : "s"}`;

/**
 * A shared list, for someone with the link and no account here.
 *
 * Rendered in place of the whole app (see main.tsx), so nothing about it asks
 * who is signed in: no session, no profile, no sidebar. It reads the one public
 * route the link is a key to, and can change nothing.
 */
export function SharedListPage({ listId, shareKey }: { listId: string; shareKey: string }) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        // A plain fetch, without the app's client: no cookie and no profile
        // header belong on a request anyone with the link can make.
        const response = await fetch(apiUrl(`/shared/lists/${listId}/${shareKey}`), {
          signal: controller.signal,
          credentials: "omit",
          headers: { Accept: "application/json" },
        });
        if (response.status === 404) return setState({ status: "missing" });
        if (!response.ok) return setState({ status: "failed" });
        setState({ status: "ready", data: (await response.json()) as SharedList });
      } catch {
        if (!controller.signal.aborted) setState({ status: "failed" });
      }
    })();
    return () => controller.abort();
  }, [listId, shareKey]);

  useEffect(() => {
    document.title = state.status === "ready" ? `${state.data.list.name} · Fetch` : "Shared list · Fetch";
  }, [state]);

  return (
    <main className="mx-auto min-h-dvh w-full max-w-[1400px] px-5 py-8 sm:px-8" style={{ color: "var(--text)" }}>
      {state.status === "loading" && (
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading the list…
        </p>
      )}

      {state.status === "missing" && (
        <div className="mx-auto max-w-prose py-16 text-center">
          <h1 className={PAGE_TITLE}>This list isn't shared</h1>
          <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>
            The link is out of date, or the list's owner stopped sharing it. Ask them for a new link.
          </p>
        </div>
      )}

      {state.status === "failed" && (
        <div className="mx-auto max-w-prose py-16 text-center">
          <h1 className={PAGE_TITLE}>The list couldn't be loaded</h1>
          <p className="mt-3 text-sm" style={{ color: "var(--text-dim)" }}>Check your connection and reload the page.</p>
        </div>
      )}

      {state.status === "ready" && (
        <>
          <header className="mb-6">
            <p className="text-xs font-medium uppercase tracking-wide" style={{ color: "var(--text-mute)" }}>
              Shared list
            </p>
            <h1 className={`mt-1 ${PAGE_TITLE}`}>{state.data.list.name}</h1>
            <p className="meta-row mt-1" style={{ color: "var(--text-mute)" }}>
              {plural(state.data.list.itemCount, "title")}
            </p>
            {state.data.list.description && (
              <p className="mt-2 max-w-prose text-sm" style={{ color: "var(--text-dim)" }}>
                {state.data.list.description}
              </p>
            )}
          </header>

          {state.data.items.length === 0 ? (
            <p className="text-sm" style={{ color: "var(--text-dim)" }}>Nothing on this list yet.</p>
          ) : (
            <PosterGrid density="library">
              {state.data.items.map((item) => (
                <figure key={`${item.type}:${item.imdbId}`} className="min-w-0">
                  <Poster src={item.poster ?? undefined} alt={item.name} className="aspect-[2/3] w-full rounded-lg" />
                  <figcaption className="mt-1.5">
                    <p className="truncate text-sm font-medium">{item.name}</p>
                    <p className="text-xs" style={{ color: "var(--text-mute)" }}>
                      {[item.type === "movie" ? "Film" : "Series", item.year].filter(Boolean).join(" · ")}
                    </p>
                  </figcaption>
                </figure>
              ))}
            </PosterGrid>
          )}

          <footer className="mt-10 text-xs" style={{ color: "var(--text-mute)" }}>
            Shared from Fetch. This page is read-only.
          </footer>
        </>
      )}
    </main>
  );
}
