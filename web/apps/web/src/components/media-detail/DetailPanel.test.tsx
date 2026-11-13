import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SearchResult, WatchEvent } from "../../api";
import type { PanelDetail } from "./useDetailPanel";
import { DetailPanel } from "./DetailPanel";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getCheckin: vi.fn(),
      getLists: vi.fn(),
      getItemLists: vi.fn(),
      getRating: vi.fn(),
      getItemTags: vi.fn(),
      getWatchHistory: vi.fn(),
      deleteWatchEvent: vi.fn(),
      logWatch: vi.fn(),
      dropShow: vi.fn(),
      undropShow: vi.fn(),
      getWatchedEpisodes: vi.fn(),
      getSeasonEpisodes: vi.fn(),
      getTitleRatings: vi.fn(),
      getMdbListRatings: vi.fn(),
    },
  };
});

const { api } = await import("../../api");
const getCheckin = vi.mocked(api.getCheckin);
const getLists = vi.mocked(api.getLists);
const getItemLists = vi.mocked(api.getItemLists);
const getRating = vi.mocked(api.getRating);
const getItemTags = vi.mocked(api.getItemTags);
const deleteWatchEvent = vi.mocked(api.deleteWatchEvent);

const MOVIE: SearchResult = {
  imdbId: "tt-solaris",
  type: "movie",
  name: "Solaris",
  year: 1972,
  poster: null,
  description: "A psychologist is sent to a station orbiting a distant planet.",
  genres: ["Sci-Fi"],
  rating: 8.1,
  inWatchlist: false,
  inCollection: false,
  lists: [],
};

const SERIES: SearchResult = { ...MOVIE, imdbId: "tt-severance", type: "series", name: "Severance" };

const WATCH: WatchEvent = {
  id: "ev-1",
  imdbId: MOVIE.imdbId,
  type: "movie",
  name: "Solaris",
  watchedAt: "2026-01-02T20:00:00.000Z",
  dateUnknown: false,
};

const FULL_DETAIL: PanelDetail = {
  cast: [{ name: "Donatas Banionis", character: "Kris Kelvin", photo: null, order: 0 }],
  director: "Andrei Tarkovsky",
  providers: { flatrate: [{ id: 1, name: "Criterion Channel", logo: null }], free: [], ads: [], link: null },
  recommendations: [],
  seasons: [],
  dropped: false,
};

function renderPanel(over: Partial<Parameters<typeof DetailPanel>[0]> = {}) {
  const props = {
    item: MOVIE,
    history: [WATCH],
    historyLoading: false,
    detail: null as PanelDetail | null,
    detailLoading: false,
    onClose: vi.fn(),
    onShowToast: vi.fn(),
    onHistoryChange: vi.fn(),
    onSelectItem: vi.fn(),
    ...over,
  };
  return { ...render(<DetailPanel {...props} />), props };
}

beforeEach(() => {
  getCheckin.mockResolvedValue({ checkin: null });
  getLists.mockResolvedValue({ lists: [] });
  getItemLists.mockResolvedValue({ lists: [] });
  getRating.mockResolvedValue({ rating: { rating: null } } as never);
  getItemTags.mockResolvedValue({ tags: [] });
  vi.mocked(api.getWatchedEpisodes).mockResolvedValue({ episodes: [] } as never);
  vi.mocked(api.getTitleRatings).mockResolvedValue({ ratings: [] } as never);
  vi.mocked(api.getMdbListRatings).mockResolvedValue({ ratings: [] });
});

// The API composes cast, providers, recommendations, seasons and dropped state
// into one bundle, and its own tests cover a section of that bundle failing
// without taking the response with it. This is the client half: a bundle that
// arrived empty, or not at all, must leave a usable panel rather than a blank one.
describe("DetailPanel section degradation", () => {
  it("still shows the title and what it knows when the bundle never arrived", async () => {
    renderPanel();

    const dialog = await screen.findByRole("dialog", { name: "Solaris" });
    expect(dialog).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Solaris" })).toBeInTheDocument();
    expect(screen.getByText(/psychologist is sent to a station/i)).toBeInTheDocument();
    expect(screen.getByText("Watch history")).toBeInTheDocument();
    // The bundle's own sections are simply absent — not stuck on skeletons.
    expect(screen.queryByText("Cast")).not.toBeInTheDocument();
    expect(screen.queryByText("Where to Watch")).not.toBeInTheDocument();
    expect(screen.queryByText("More like this")).not.toBeInTheDocument();
  });

  // #502: a failed request is said out loud, with a way back, rather than
  // looking the same as a title TMDB knows nothing about.
  it("says the details couldn't load, and retries, when the bundle failed", async () => {
    const onRetryDetail = vi.fn();
    renderPanel({ detailFailed: true, onRetryDetail });

    expect(await screen.findByText("Couldn't load the details for this title.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetryDetail).toHaveBeenCalledTimes(1);
  });

  it("draws the bundle's sections once it does arrive", async () => {
    renderPanel({ detail: FULL_DETAIL });

    expect(await screen.findByText("Donatas Banionis")).toBeInTheDocument();
    expect(screen.getByText("Criterion Channel")).toBeInTheDocument();
    expect(screen.getByText(/Andrei Tarkovsky/)).toBeInTheDocument();
  });

  it("keeps the drop control hidden while the dropped state is unknown", async () => {
    const user = userEvent.setup();
    const { rerender } = renderPanel({ item: SERIES });

    await screen.findByRole("dialog", { name: "Severance" });
    // A failed bundle leaves `dropped` unknown, and defaulting it to false would
    // offer to drop a show that is already dropped. Dropping is the overflow's
    // only item, so there is no overflow either.
    expect(screen.queryByRole("button", { name: "More actions" })).not.toBeInTheDocument();

    rerender(
      <DetailPanel
        item={SERIES}
        history={[]}
        historyLoading={false}
        detail={FULL_DETAIL}
        detailLoading={false}
        onClose={vi.fn()}
        onShowToast={vi.fn()}
        onHistoryChange={vi.fn()}
        onSelectItem={vi.fn()}
      />
    );

    // In the overflow, not on the panel: nothing says "drop" until it is opened.
    const more = await screen.findByRole("button", { name: "More actions" });
    expect(screen.queryByRole("button", { name: /drop/i })).not.toBeInTheDocument();
    await user.click(more);
    expect(screen.getByRole("button", { name: /Drop this show/ })).toBeInTheDocument();

    // Choosing it closes the overflow, and focus goes back to its trigger
    // rather than to nowhere.
    vi.mocked(api.dropShow).mockResolvedValue(undefined as never);
    await user.click(screen.getByRole("button", { name: /Drop this show/ }));
    expect(more).toHaveFocus();
  });

  it("survives the sections that fetch for themselves failing", async () => {
    getLists.mockRejectedValue(new Error("Lists unavailable"));
    getItemTags.mockRejectedValue(new Error("Tags unavailable"));
    getCheckin.mockRejectedValue(new Error("Check-in unavailable"));
    renderPanel({ detail: FULL_DETAIL });

    expect(await screen.findByRole("heading", { name: "Solaris" })).toBeInTheDocument();
    expect(screen.getByText("Watch history")).toBeInTheDocument();
    expect(screen.getByText("Donatas Banionis")).toBeInTheDocument();
  });
});

describe("DetailPanel watch history", () => {
  it("hands the shortened history back to the page and offers the way back", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockResolvedValue(undefined as never);
    const { props } = renderPanel();

    await user.click(await screen.findByRole("button", { name: "Remove watch of Solaris" }));

    await waitFor(() => expect(props.onHistoryChange).toHaveBeenCalledWith([]));
    expect(deleteWatchEvent).toHaveBeenCalledWith(WATCH.id);
    expect(props.onShowToast).toHaveBeenCalledWith(
      "Watch removed",
      "info",
      expect.objectContaining({ action: expect.objectContaining({ label: "Undo" }) })
    );
  });

  it("keeps the row when the delete fails", async () => {
    const user = userEvent.setup();
    deleteWatchEvent.mockRejectedValue(new Error("nope"));
    const { props } = renderPanel();

    await user.click(await screen.findByRole("button", { name: "Remove watch of Solaris" }));

    await waitFor(() =>
      expect(props.onShowToast).toHaveBeenCalledWith("Failed to remove watch event", "error")
    );
    expect(props.onHistoryChange).not.toHaveBeenCalled();
  });
});

describe("DetailPanel dismissal", () => {
  it("closes on the close button", async () => {
    const user = userEvent.setup();
    const { props } = renderPanel();

    await user.click(await screen.findByRole("button", { name: "Close detail panel" }));

    // Closing is animated, and jsdom runs no animations — the hook's fallback
    // timer is what closes it here, as it would in a browser that dropped the
    // animation.
    await waitFor(() => expect(props.onClose).toHaveBeenCalled(), { timeout: 1000 });
  });

  // 36×36, and on a phone the panel is full-screen — so this X is the only way
  // out besides the back gesture, and a miss lands on the poster behind it.
  // `.tap-target` is what takes it to 44 on a touch screen without redrawing it.
  it("gives the close button a full-size touch target", async () => {
    renderPanel();

    const close = await screen.findByRole("button", { name: "Close detail panel" });

    expect(close.className).toContain("tap-target");
  });

  it("closes on Escape", async () => {
    const user = userEvent.setup();
    const { props } = renderPanel();
    await screen.findByRole("dialog", { name: "Solaris" });

    await user.keyboard("{Escape}");

    await waitFor(() => expect(props.onClose).toHaveBeenCalled(), { timeout: 1000 });
  });
});

describe("DetailPanel MDBList ratings", () => {
  it("shows the profile's MDBList ratings in the order it gave them, in place of the title's own scores", async () => {
    vi.mocked(api.getMdbListRatings).mockResolvedValue({
      ratings: [
        { source: "tomatoes", value: 92, certified: true },
        { source: "imdb", value: 8.1, certified: false },
        { source: "letterboxd", value: 4.2, certified: false },
      ],
    });
    renderPanel({ item: { ...MOVIE, imdbRating: 7.9, rtScore: 50 } });

    const row = await screen.findByRole("list", { name: "Ratings from MDBList" });
    const items = within(row).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual(["92%", "IMDb8.1/10", "LB4.2/5"]);
    expect(within(row).getByRole("img", { name: "Rotten Tomatoes, Certified Fresh 92%" })).toBeInTheDocument();
    expect(within(row).getByRole("link", { name: /IMDb 8\.1 out of 10/ })).toHaveAttribute(
      "href",
      "https://www.imdb.com/title/tt-solaris/"
    );
    // The OMDb score isn't drawn beside MDBList's own.
    expect(screen.queryByText("7.9")).not.toBeInTheDocument();
    expect(api.getMdbListRatings).toHaveBeenCalledWith("movie", "tt-solaris");
  });

  it("keeps the title's own scores when the profile has no MDBList ratings", async () => {
    renderPanel({ item: { ...MOVIE, imdbRating: 7.9 } });

    expect(await screen.findByText("7.9")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Ratings from MDBList" })).not.toBeInTheDocument();
  });
});

const SEASONS_DETAIL: PanelDetail = {
  ...FULL_DETAIL,
  seasons: [
    { seasonNumber: 1, name: "Season 1", episodeCount: 9, airYear: 2022, poster: null },
    { seasonNumber: 2, name: "Season 2", episodeCount: 10, airYear: 2025, poster: null },
  ],
};

const EPISODE_WATCH: WatchEvent = {
  id: "ev-2",
  imdbId: SERIES.imdbId,
  type: "episode",
  name: "Severance",
  season: 1,
  episode: 9,
  watchedAt: "2026-01-02T20:00:00.000Z",
  dateUnknown: false,
};

// The panel used to offer "Log a watch" twice and check-in twice, and gave a
// rare destructive action a section of its own above the episode list.
describe("DetailPanel actions", () => {
  beforeEach(() => {
    vi.mocked(api.getSeasonEpisodes).mockResolvedValue({ episodes: [] });
  });

  it("offers logging a watch and checking in once each", async () => {
    renderPanel({ detail: FULL_DETAIL });

    await screen.findByText("Donatas Banionis");
    expect(screen.getAllByRole("button", { name: /log a watch/i })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: /check in/i })).toHaveLength(1);
  });

  it("puts the episode list ahead of the overview on a series", async () => {
    renderPanel({ item: SERIES, history: [], detail: SEASONS_DETAIL });

    const seasons = await screen.findByText("Seasons");
    const overview = screen.getByText("Overview");
    const orderOf = (el: HTMLElement) => {
      const cls = el.closest('[class*="order-"]')?.className ?? "";
      return Number(/(?:^|\s)order-(\d+)/.exec(cls)?.[1]);
    };
    // The sections are one column ordered with CSS, so the order is the class.
    expect(orderOf(seasons)).toBeLessThan(orderOf(overview));
  });

  it("opens the watch dialog on the next episode, bounded by the show's seasons", async () => {
    const user = userEvent.setup();
    vi.mocked(api.logWatch).mockResolvedValue(undefined as never);
    vi.mocked(api.getWatchHistory).mockResolvedValue([]);
    renderPanel({ item: SERIES, history: [EPISODE_WATCH], detail: SEASONS_DETAIL });

    // Season 1 is finished, so the next one up is the start of season 2.
    await user.click(await screen.findByRole("button", { name: "Continue · S2 E1" }));

    const season = screen.getByLabelText("Season") as HTMLSelectElement;
    const episode = screen.getByLabelText("Episode") as HTMLSelectElement;
    expect(season.value).toBe("2");
    expect(within(season).getAllByRole("option").map((o) => o.textContent)).toEqual(["Season 1", "Season 2"]);
    expect(within(episode).getAllByRole("option")).toHaveLength(10);

    await user.selectOptions(season, "1");
    expect(within(episode).getAllByRole("option")).toHaveLength(9);
    await user.selectOptions(episode, "9");
    await user.click(screen.getByRole("button", { name: "Just now" }));

    await waitFor(() =>
      expect(api.logWatch).toHaveBeenCalledWith(
        expect.objectContaining({ type: "episode", seriesImdbId: SERIES.imdbId, season: 1, episode: 9 })
      )
    );
  });

  it("links to TMDB from the ratings row", async () => {
    renderPanel({ item: { ...MOVIE, imdbRating: 7.9, tmdbId: 593 } });

    const link = await screen.findByRole("link", { name: "View on TMDB" });
    expect(link).toHaveAttribute("href", "https://www.themoviedb.org/movie/593");
    // Inside the row the scores are in, not in one of its own.
    expect(link.parentElement).toContainElement(screen.getByText("7.9"));
  });
});

// A title reached by URL, a recommendation hop or a Stats poster arrives with
// `lists: []`, whatever lists it is really on.
describe("DetailPanel list membership", () => {
  it("asks which lists the title is on rather than trusting what the opener knew", async () => {
    getLists.mockResolvedValue({
      lists: [{ id: "wl", name: "Watchlist", kind: "watchlist" }],
    } as Awaited<ReturnType<typeof api.getLists>>);
    getItemLists.mockResolvedValue({
      lists: [{ listId: "wl", listName: "Watchlist", listKind: "watchlist", type: "movie", addedAt: "2026-01-01T00:00:00.000Z" }],
    });
    renderPanel();

    expect(await screen.findByRole("button", { name: "Remove from Watchlist" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add Solaris to Watchlist" })).toBeNull();
    expect(getItemLists).toHaveBeenCalledWith(MOVIE.imdbId);
  });

  it("keeps what it was given when the membership request fails", async () => {
    getLists.mockResolvedValue({
      lists: [{ id: "wl", name: "Watchlist", kind: "watchlist" }],
    } as Awaited<ReturnType<typeof api.getLists>>);
    getItemLists.mockRejectedValue(new Error("offline"));
    renderPanel({ item: { ...MOVIE, lists: ["wl"] } });

    expect(await screen.findByRole("button", { name: "Remove from Watchlist" })).toBeInTheDocument();
  });
});
