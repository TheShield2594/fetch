import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import type { WatchEvent } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { readCache, resetDataCacheForTests } from "../utils/dataCache";
import { HistoryPage } from "./HistoryPage";
import { first } from "../test/present";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getWatchHistory: vi.fn(),
      getWatchHistoryMonths: vi.fn(),
      deleteWatchEvent: vi.fn(),
      updateWatchEventNote: vi.fn(),
      logWatch: vi.fn(),
    },
  };
});

// The panel belongs to the shell and has suites of its own. What this file
// needs from it is the opener, hoisted so a case can assert that a row did —
// or did *not* — open a title.
const openTitle = vi.hoisted(() => vi.fn());

vi.mock("../components/media-detail/titlePanel", () => ({
  useTitlePanel: () => ({ openTitle, current: null }),
}));

const { api } = await import("../api");
const getWatchHistory = vi.mocked(api.getWatchHistory);
const getWatchHistoryMonths = vi.mocked(api.getWatchHistoryMonths);
const deleteWatchEvent = vi.mocked(api.deleteWatchEvent);
const updateWatchEventNote = vi.mocked(api.updateWatchEventNote);

function watched(name: string, over: Partial<WatchEvent> = {}): WatchEvent {
  return {
    id: `ev-${name.toLowerCase()}`,
    imdbId: `tt-${name.toLowerCase()}`,
    type: "movie",
    name,
    watchedAt: new Date().toISOString(),
    dateUnknown: false,
    note: null,
    ...over,
  };
}

const ALIEN = watched("Alien");
const SOLARIS = watched("Solaris", { note: "Rewatch — still holds up" });
const EPISODE = watched("Twin Peaks", { type: "episode", season: 2, episode: 7 });

function renderPage(initialEntry = "/history") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ToastProvider>
        <HistoryPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

// The cache outlives a mount by design — that is what it is for — so it has to
// be cleared between cases, or one case's history is the next one's first paint.
beforeEach(() => {
  resetDataCacheForTests();
  getWatchHistory.mockResolvedValue([ALIEN, SOLARIS]);
  getWatchHistoryMonths.mockResolvedValue([]);
  openTitle.mockClear();
  // jsdom has no viewport, so the infinite-scroll sentinel never intersects on
  // its own — which is what these cases want: one page, loaded once.
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  );
});

describe("HistoryPage", () => {
  it("asks the server for the filtered type rather than filtering the loaded page", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: "Episodes" }));

    await waitFor(() =>
      expect(getWatchHistory).toHaveBeenLastCalledWith(25, 0, {
        type: "episode",
        signal: expect.any(AbortSignal),
      })
    );
  });

  it("keeps the filter pills reachable when the fetch fails", async () => {
    getWatchHistory.mockRejectedValue(new Error("Network down"));
    renderPage();

    expect(await screen.findByText("Network down")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Movies" })).toBeInTheDocument();
  });

  // A button inside a button has no defined accessible mapping, and assistive
  // tech resolves it differently from one implementation to the next — the row
  // was a role="button" wrapping the note and delete controls. All three are
  // still here; none of them contains another.
  it("keeps the row's three controls as siblings, not nested buttons", async () => {
    renderPage();
    await screen.findByText("Alien");

    const open = screen.getByRole("button", { name: /view details for alien/i });
    const note = screen.getByRole("button", { name: /add note to alien/i });
    const remove = screen.getByRole("button", { name: /delete watch of alien/i });

    const pairs: [HTMLElement, HTMLElement][] = [[open, note], [open, remove], [note, remove]];
    for (const [a, b] of pairs) {
      expect(a.contains(b)).toBe(false);
      expect(b.contains(a)).toBe(false);
    }
  });

  it("removes a row optimistically and offers the way back", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockResolvedValue(undefined as never);
    renderPage();
    await screen.findByText("Alien");

    await user.click(first(screen.getAllByRole("button", { name: /delete watch of alien/i }), "delete button"));

    await waitFor(() => expect(screen.queryByText("Alien")).not.toBeInTheDocument());
    expect(await screen.findByText(/Removed Alien from history/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /undo/i })).toBeInTheDocument();
  });

  // The row's controls used to sit inside a role="button" that swallowed Enter
  // and Space, so a keystroke bubbling up from one of them was cancelled there
  // and the detail panel opened where a keyboard user had asked to delete. The
  // row-opening control is a sibling of these two now, not their ancestor —
  // which is also what stops the pair reading as a button inside a button.
  it("deletes from the keyboard rather than opening the panel", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockResolvedValue(undefined as never);
    renderPage();
    await screen.findByText("Alien");

    first(screen.getAllByRole("button", { name: /delete watch of alien/i }), "delete button").focus();
    await user.keyboard("{Enter}");

    await waitFor(() => expect(deleteWatchEvent).toHaveBeenCalledWith(ALIEN.id));
    expect(openTitle).not.toHaveBeenCalled();
  });

  it("opens the note editor from the keyboard, on Space as well", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Alien");

    screen.getByRole("button", { name: /add note to alien/i }).focus();
    await user.keyboard(" ");

    expect(await screen.findByLabelText(/^note on alien$/i)).toBeInTheDocument();
    expect(openTitle).not.toHaveBeenCalled();
  });

  it("puts a row back when the delete fails", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockRejectedValue(new Error("Server said no"));
    renderPage();
    await screen.findByText("Alien");

    await user.click(first(screen.getAllByRole("button", { name: /delete watch of alien/i }), "delete button"));

    expect(await screen.findByText("Server said no")).toBeInTheDocument();
    expect(screen.getByText("Alien")).toBeInTheDocument();
  });
});

describe("HistoryPage group headings", () => {
  it("re-labels an overnight tab instead of leaving yesterday's watches under Today", async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date(2026, 4, 15, 23, 50));
      getWatchHistory.mockResolvedValue([
        watched("Alien", { watchedAt: new Date(2026, 4, 15, 20, 0).toISOString() }),
      ]);

      renderPage();
      // The fetch resolves on a microtask, which fake timers do not hold up.
      await act(async () => {});
      expect(screen.getByText("Today")).toBeInTheDocument();

      // Past midnight, without the tab being touched.
      await act(async () => {
        vi.advanceTimersByTime(11 * 60 * 1000);
      });

      expect(screen.getByText("Yesterday")).toBeInTheDocument();
      expect(screen.queryByText("Today")).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("HistoryPage caching and pagination", () => {
  const byFilter = async (
    _limit: number,
    _offset: number,
    opts?: { type?: "movie" | "episode" }
  ) => (opts?.type === "movie" ? [ALIEN] : [ALIEN, EPISODE]);

  /**
   * Replaces the inert observer from `beforeEach` with one the case drives:
   * jsdom lays nothing out, so the sentinel never intersects on its own.
   * Returns the trigger, which always fires the newest observer — the effect
   * builds a fresh one each time the list it is watching changes.
   */
  /*
   * Stubs IntersectionObserver and hands back a trigger for the scroll sentinel.
   *
   * The trigger waits for the page to have constructed its observer, and that
   * wait is the whole point. `fire` is assigned from the constructor, and the
   * page only constructs an observer from an effect that runs once the first
   * page has landed and reported more to come — which is a later commit than
   * the one `findByText` resolves on. Firing before it is a lost edge: the call
   * reaches the placeholder, nothing retries it, and the `waitFor` that follows
   * then polls an assertion that can never come true. It fails as a timeout, so
   * it reads like slowness and is not — a longer timeout would not have helped.
   *
   * That was an intermittent failure of the in-flight-page test below, which
   * only showed under full-suite load and never in the file on its own.
   */
  function observeScrollSentinel() {
    let fire: (() => void) | null = null;
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
          fire = () => callback([{ isIntersecting: true }]);
        }
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    );
    return async () => {
      await waitFor(() => expect(fire).not.toBeNull());
      await act(async () => {
        fire!();
      });
    };
  }

  // An episode row writes its title and its S2E7 in separate elements, so the
  // row's accessible name is what names one whole row.
  const findEpisode = () => screen.findByRole("button", { name: "View details for Twin Peaks S2E7" });
  const episodeRow = () => screen.queryByRole("button", { name: "View details for Twin Peaks S2E7" });

  /*
   * The cache key has to carry the filter, because the filter is half the
   * question the server answered. Keyed on a constant, a movies-only page was
   * stored as though it were the whole history — and the next mount, which
   * starts at "All", read it back and painted it as complete.
   *
   * Asserted against the cache rather than the screen: what goes wrong is what
   * gets written, and the page it poisons is the next mount, not this one.
   */
  it("stores a filtered page under a key of its own", async () => {
    const user = userEvent.setup();
    getWatchHistory.mockImplementation(byFilter as never);
    renderPage();
    await findEpisode();

    await user.click(screen.getByRole("button", { name: "Movies" }));
    await waitFor(() => expect(episodeRow()).not.toBeInTheDocument());

    expect(readCache("history:events:movie")).toEqual([ALIEN]);
    // Untouched: still the whole history, which is what the next mount opens on.
    expect(readCache("history:events:all")).toEqual([ALIEN, EPISODE]);
  });

  /*
   * Infinite scroll fires near the bottom; the user taps a filter before the
   * page lands. Appending it would put a page of the old result set under the
   * new one's heading — and leave the next page starting from the wrong row.
   */
  it("drops an in-flight page when the filter changes under it", async () => {
    const user = userEvent.setup();
    const intersect = observeScrollSentinel();

    // A full page, so the list reports more to come and the sentinel is live.
    const firstPage = Array.from({ length: 25 }, (_, i) => watched(`Feature ${i}`));
    let releaseSecondPage: () => void = () => {};
    const secondPage = new Promise<WatchEvent[]>((r) => {
      releaseSecondPage = () => r([watched("Late Arrival")]);
    });
    getWatchHistory.mockImplementation((async (
      _limit: number,
      offset: number,
      opts?: { type?: "movie" | "episode" }
    ) => {
      if (opts?.type === "episode") return [EPISODE];
      return offset === 0 ? firstPage : secondPage;
    }) as never);
    renderPage();
    // The last row of the first page, not the first: the sentinel is only
    // observed once the whole page has committed and reported more to come, so
    // waiting for row 0 would leave the fire below racing the commit that makes
    // it land somewhere.
    await screen.findByText("Feature 24");

    await intersect();
    // `toHaveBeenCalledWith`, not `toHaveBeenLastCalledWith`: what this test is
    // about is a filter change dropping the page that was in flight, and the
    // precondition for that is only ever "the offset-25 page was asked for".
    // Pinning it as the *last* call asks a question the test does not mean —
    // and one whose answer another render can change — so it failed
    // intermittently under full-suite load while the behaviour was fine.
    await waitFor(() => expect(getWatchHistory).toHaveBeenCalledWith(25, 25, expect.anything()));
    await user.click(screen.getByRole("button", { name: "Episodes" }));
    await findEpisode();

    await act(async () => {
      releaseSecondPage();
    });

    expect(screen.queryByText("Late Arrival")).not.toBeInTheDocument();
    expect(screen.queryByText("Feature 0")).not.toBeInTheDocument();
  });

  /*
   * A delete removes the row here and on the server at once, so every offset
   * past it moves — including the one a page already in flight was asking for.
   * Tracking the offset separately and nudging it by ±1 compensated for the
   * shift *next* time and left the page in flight reading the old numbering, so
   * the row that moved into its place was never fetched by anyone.
   *
   * The history the server holds is modelled here, because that skipped row is
   * the whole defect and a fixed mock response cannot show it.
   */
  it("skips no row when a delete lands while a page is in flight", async () => {
    const user = userEvent.setup();
    const intersect = observeScrollSentinel();

    const server = Array.from({ length: 40 }, (_, i) => watched(`Feature ${i}`));
    let releasePage: () => void = () => {};
    let pending: Promise<WatchEvent[]> | null = null;
    getWatchHistory.mockImplementation((async (limit: number, offset: number) => {
      if (offset === 0) return server.slice(0, limit);
      // Held open, and answered from the list as it stands when it is released —
      // a read the server runs after the delete has already gone through.
      pending = new Promise<WatchEvent[]>((r) => {
        releasePage = () => r(server.slice(offset, offset + limit));
      });
      return pending;
    }) as never);
    deleteWatchEvent.mockImplementation((async (id: string) => {
      server.splice(server.findIndex((e) => e.id === id), 1);
    }) as never);
    renderPage();
    // Row 24, for the same reason as the test above: the sentinel is not
    // observed until the whole first page has committed.
    await screen.findByText("Feature 24");

    await intersect();
    await user.click(first(screen.getAllByRole("button", { name: /delete watch of feature 0/i }), "delete button"));
    await waitFor(() => expect(screen.queryByText("Feature 0")).not.toBeInTheDocument());
    await act(async () => {
      releasePage();
    });
    // The scroll sentinel is still at the bottom of a list that is one row
    // shorter than it was, so the page it asks for next is the one that counts.
    await intersect();
    await act(async () => {
      releasePage();
    });
    await screen.findByText("Feature 25");

    // Feature 24 was the last row of the first page; 25 is the one the stale
    // offset used to jump over, since deleting Feature 0 moved it into 24's place.
    for (const name of ["Feature 24", "Feature 25", "Feature 26"]) {
      expect(screen.getAllByText(name)).toHaveLength(1);
    }
  });
});

describe("HistoryPage notes", () => {
  it("shows a note that came with the event", async () => {
    renderPage();

    expect(await screen.findByText("Rewatch — still holds up")).toBeInTheDocument();
  });

  it("adds a note to a watch that had none", async () => {
    const user = userEvent.setup();
    updateWatchEventNote.mockResolvedValue({ watchEvent: watched("Alien", { note: "Saw it at the BFI" }) });
    renderPage();
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: /add note to alien/i }));
    await user.type(screen.getByLabelText(/^note on alien$/i), "Saw it at the BFI");
    await user.click(screen.getByRole("button", { name: /save note/i }));

    expect(await screen.findByText("Saw it at the BFI")).toBeInTheDocument();
    expect(updateWatchEventNote).toHaveBeenCalledWith(ALIEN.id, "Saw it at the BFI");
    expect(screen.queryByRole("button", { name: /save note/i })).not.toBeInTheDocument();
  });

  it("clears a note when the field is emptied, rather than saving a blank one", async () => {
    const user = userEvent.setup();
    updateWatchEventNote.mockResolvedValue({ watchEvent: watched("Solaris", { note: null }) });
    renderPage();
    await screen.findByText("Rewatch — still holds up");

    await user.click(screen.getByRole("button", { name: /edit note on solaris/i }));
    await user.clear(screen.getByLabelText(/^note on solaris$/i));
    await user.click(screen.getByRole("button", { name: /save note/i }));

    expect(updateWatchEventNote).toHaveBeenCalledWith(SOLARIS.id, null);
    await waitFor(() =>
      expect(screen.queryByText("Rewatch — still holds up")).not.toBeInTheDocument()
    );
  });

  // A failed save that closed the editor would cost the user everything they
  // typed, and the row would go on showing the old note as if nothing happened.
  it("keeps the draft open when the save fails", async () => {
    const user = userEvent.setup();
    updateWatchEventNote.mockRejectedValue(new Error("Save failed"));
    renderPage();
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: /add note to alien/i }));
    await user.type(screen.getByLabelText(/^note on alien$/i), "Half-written thought");
    await user.click(screen.getByRole("button", { name: /save note/i }));

    expect(await screen.findByText("Save failed")).toBeInTheDocument();
    expect(screen.getByLabelText(/^note on alien$/i)).toHaveValue("Half-written thought");
  });

  it("doesn't call the server when nothing changed", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Rewatch — still holds up");

    await user.click(screen.getByRole("button", { name: /edit note on solaris/i }));
    await user.click(screen.getByRole("button", { name: /save note/i }));

    expect(updateWatchEventNote).not.toHaveBeenCalled();
    expect(screen.getByText("Rewatch — still holds up")).toBeInTheDocument();
  });

  it("discards the draft on Escape", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: /add note to alien/i }));
    await user.type(screen.getByLabelText(/^note on alien$/i), "Never mind{Escape}");

    expect(screen.queryByLabelText(/^note on alien$/i)).not.toBeInTheDocument();
    expect(updateWatchEventNote).not.toHaveBeenCalled();
  });
});

describe("HistoryPage binges, days and finding things", () => {
  const svu = (episode: number, hour: number, over: Partial<WatchEvent> = {}) =>
    watched("Law & Order: SVU", {
      id: `ev-svu-${episode}`,
      imdbId: `tt-svu-2-${episode}`,
      seriesImdbId: "tt-svu",
      type: "episode",
      season: 2,
      episode,
      runtime: 42,
      watchedAt: new Date(2026, 4, 12, hour, 5).toISOString(),
      ...over,
    });
  const BINGE = [18, 17, 16, 15, 14, 13].map((episode, i) => svu(episode, 23 - i));

  // The issue's first "done when".
  it("puts six consecutive episodes of one show on one row", async () => {
    getWatchHistory.mockResolvedValue(BINGE);
    renderPage();

    expect(await screen.findByText("S2 E13–E18 · 6 episodes")).toBeInTheDocument();
    expect(screen.getAllByText("Law & Order: SVU")).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: /view details for/i })).toHaveLength(1);
    // The per-watch controls are a click away, not gone.
    expect(screen.queryByRole("button", { name: /delete watch of/i })).toBeNull();
  });

  it("opens the run to its episodes, each with its own note and delete", async () => {
    const user = userEvent.setup();
    getWatchHistory.mockResolvedValue(BINGE);
    renderPage();

    const toggle = await screen.findByRole("button", { name: "Show the 6 episodes of Law & Order: SVU" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);

    expect(screen.getByRole("button", { name: "Hide the 6 episodes of Law & Order: SVU" })).toHaveAttribute(
      "aria-expanded",
      "true"
    );
    expect(screen.getAllByRole("button", { name: /delete watch of law & order: svu s2e\d+/i })).toHaveLength(6);
    expect(screen.getByRole("button", { name: "Add note to Law & Order: SVU S2E13" })).toBeInTheDocument();
  });

  it("opens the show from the collapsed row", async () => {
    const user = userEvent.setup();
    getWatchHistory.mockResolvedValue(BINGE);
    renderPage();

    await user.click(await screen.findByRole("button", { name: "View details for Law & Order: SVU" }));

    expect(openTitle).toHaveBeenCalledWith(expect.objectContaining({ imdbId: "tt-svu", type: "series" }));
  });

  it("shrinks the run when one of its episodes is deleted", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockResolvedValue(undefined as never);
    getWatchHistory.mockResolvedValue(BINGE);
    renderPage();

    await user.click(await screen.findByRole("button", { name: /show the 6 episodes/i }));
    await user.click(screen.getByRole("button", { name: "Delete watch of Law & Order: SVU S2E18" }));

    expect(await screen.findByText("S2 E13–E17 · 5 episodes")).toBeInTheDocument();
  });

  it("heads each day with what it added up to", async () => {
    getWatchHistory.mockResolvedValue([
      ...BINGE.slice(0, 3),
      watched("Alien", { runtime: 117, watchedAt: new Date(2026, 4, 12, 14, 0).toISOString() }),
    ]);
    renderPage();

    // 3 × 42 + 117 minutes.
    expect(await screen.findByText("3 episodes, 1 film · 4h 3m")).toBeInTheDocument();
  });

  it("shows the profile's own rating on a row", async () => {
    getWatchHistory.mockResolvedValue([watched("Alien", { rating: 9 })]);
    renderPage();

    expect(await screen.findByRole("img", { name: "Your rating: 4.5 out of 5" })).toBeInTheDocument();
  });

  it("sends a title filter to the server once typing pauses, and keeps it in the URL", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Alien");

    await user.type(screen.getByRole("searchbox", { name: "Filter history by title" }), "law");

    await waitFor(() =>
      expect(getWatchHistory).toHaveBeenLastCalledWith(25, 0, expect.objectContaining({ q: "law" }))
    );
    // One request for the finished word, not one per letter.
    expect(getWatchHistory.mock.calls.filter(([, , opts]) => opts?.q).map(([, , opts]) => opts?.q)).toEqual(["law"]);
  });

  it("says so when the filter matches nothing, and offers the way back", async () => {
    const user = userEvent.setup();
    getWatchHistory.mockImplementation(async (_limit, _offset, opts) => (opts?.q ? [] : [ALIEN]));
    renderPage("/history?q=zzz");

    expect(await screen.findByText("Nothing in your history matches “zzz”.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show all history" }));

    expect(await screen.findByText("Alien")).toBeInTheDocument();
  });

  // The issue's second "done when": a month, without scrolling to it.
  it("jumps to a month by asking for everything before its end", async () => {
    const user = userEvent.setup();
    getWatchHistoryMonths.mockResolvedValue([
      { month: "2026-05", events: 12 },
      { month: "2025-03", events: 142 },
    ]);
    renderPage();
    await screen.findByText("Alien");

    await user.selectOptions(await screen.findByRole("combobox", { name: "Jump to a month" }), "2025-03");

    await waitFor(() =>
      expect(getWatchHistory).toHaveBeenLastCalledWith(
        25,
        0,
        expect.objectContaining({ before: new Date(2025, 3, 1).toISOString() })
      )
    );
    expect(screen.getByText(/From the end of March 2025/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Back to latest" }));
    await waitFor(() =>
      expect(getWatchHistory).toHaveBeenLastCalledWith(25, 0, expect.objectContaining({ before: undefined }))
    );
  });

  it("opens at the month a link names — which is how Stats gets here", async () => {
    renderPage("/history?month=2025-03");

    await waitFor(() =>
      expect(getWatchHistory).toHaveBeenCalledWith(
        25,
        0,
        expect.objectContaining({ before: new Date(2025, 3, 1).toISOString() })
      )
    );
  });

  it("does not file a filtered list in the cache as the history", async () => {
    getWatchHistory.mockResolvedValue([ALIEN]);
    renderPage("/history?q=alien");
    await screen.findByText("Alien");

    expect(readCache("history:events:all")).toBeUndefined();
  });
});
