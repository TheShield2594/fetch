import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import type { CatalogList, SearchResult } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { SearchPage } from "./SearchPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getLists: vi.fn(),
      browseSearch: vi.fn(),
      getTrending: vi.fn(),
      addToList: vi.fn(),
      createList: vi.fn(),
    },
  };
});

// The panel fetches metadata and history of its own the moment a card opens;
// none of that is what this file is about.
vi.mock("../components/MediaDetailPanel", () => ({
  DetailPanel: () => null,
  useDetailPanel: () => ({
    selectedItem: null,
    setSelectedItem: vi.fn(),
    panelHistory: [],
    setPanelHistory: vi.fn(),
    panelHistoryLoading: false,
  }),
}));

const { api } = await import("../api");
const getLists = vi.mocked(api.getLists);
const browseSearch = vi.mocked(api.browseSearch);
const getTrending = vi.mocked(api.getTrending);
/**
 * Most cases only care what a query returns per media type, so they script
 * this, and `browseSearch` answers one page of it with nothing after.
 */
const search = vi.fn<(type: "movie" | "series", query: string, signal?: AbortSignal) => Promise<SearchResult[]>>();
const addToList = vi.mocked(api.addToList);
const createList = vi.mocked(api.createList);

const WATCHLIST: CatalogList = { id: "l-watch", name: "Watchlist", kind: "watchlist", itemCount: 3 };
const SCIFI: CatalogList = { id: "l-scifi", name: "Sci-Fi Night", kind: "custom", itemCount: 1 };

function result(name: string, over: Partial<SearchResult> = {}): SearchResult {
  return {
    imdbId: `tt-${name.toLowerCase().replace(/\s+/g, "-")}`,
    type: "movie",
    name,
    year: 2001,
    poster: null,
    description: null,
    genres: [],
    rating: null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
    ...over,
  };
}

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="search-params">{location.search}</p>;
}

function renderPage(initialEntry = "/search") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ToastProvider>
        <Routes>
          <Route
            path="/search"
            element={
              <>
                <SearchPage />
                <LocationProbe />
              </>
            }
          />
          <Route path="/settings" element={<p>settings page</p>} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const queryField = () => screen.getByLabelText("Search films and shows");

beforeEach(() => {
  window.localStorage.clear();
  getLists.mockResolvedValue({ lists: [WATCHLIST, SCIFI] });
  search.mockReset();
  search.mockResolvedValue([]);
  browseSearch.mockImplementation(async (type, params, signal) => ({
    results: await search(type, params.query ?? "", signal),
    page: params.page ?? 1,
    nextPage: null,
  }));
  getTrending.mockResolvedValue({ metas: [] });
  addToList.mockResolvedValue(undefined as never);
});

describe("SearchPage query handling", () => {
  it("searches once for a query typed a letter at a time", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(queryField(), "alien");

    await waitFor(() => expect(search).toHaveBeenCalledWith("movie", "alien", expect.any(AbortSignal)));
    // Both types, because the filter starts on All — and nothing for the four
    // prefixes typed on the way there.
    expect(search).toHaveBeenCalledWith("series", "alien", expect.any(AbortSignal));
    expect(search).toHaveBeenCalledTimes(2);
  });

  it("searches only the chosen type once the filter narrows", async () => {
    const user = userEvent.setup();
    renderPage("/search?q=alien");
    await waitFor(() => expect(search).toHaveBeenCalledTimes(2));
    search.mockClear();

    await user.click(screen.getByRole("button", { name: "Films" }));

    await waitFor(() => expect(search).toHaveBeenCalledWith("movie", "alien", expect.any(AbortSignal)));
    expect(search).not.toHaveBeenCalledWith("series", "alien", expect.any(AbortSignal));
  });

  it("keeps the query in the URL, so a search survives a reload or a share", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(queryField(), "solaris");

    await waitFor(() => expect(screen.getByTestId("search-params").textContent).toBe("?q=solaris"));
  });

  it("offers somewhere to start before anything has been searched", () => {
    renderPage();

    expect(screen.getByRole("heading", { name: "Browse by genre" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Browse by decade" })).toBeInTheDocument();
    expect(screen.queryByText(/no results found/i)).not.toBeInTheDocument();
  });

  it("returns to browsing when the query is cleared", async () => {
    const user = userEvent.setup();
    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
    renderPage("/search?q=solaris");
    expect(await screen.findByText("Solaris")).toBeInTheDocument();

    await user.clear(queryField());

    await waitFor(() => expect(screen.queryByText("Solaris")).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Browse by genre" })).toBeInTheDocument();
  });

  it("says no results rather than leaving the grid empty", async () => {
    renderPage("/search?q=nothing at all");

    expect(await screen.findByText(/no results found/i)).toBeInTheDocument();
  });

  // The results swap under you as you type, and nothing moves focus when they
  // do. Without a live region the outcome of a search is only discoverable by
  // going to look for it.
  it("announces the outcome of a search, not just the grid", async () => {
    const user = userEvent.setup();
    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
    renderPage("/search?q=solaris");
    expect(await screen.findByText("Solaris")).toBeInTheDocument();

    const live = screen.getByRole("status", { name: /search status/i });
    expect(live).toHaveAttribute("aria-live", "polite");
    await waitFor(() => expect(live).toHaveTextContent(/showing 1 result/i));

    // Still the same region afterwards — one that unmounts announces nothing.
    search.mockResolvedValue([]);
    await user.type(queryField(), "zzz");
    await waitFor(() =>
      expect(screen.getByRole("status", { name: /search status/i })).toHaveTextContent(/nothing matched/i)
    );
  });

  it("names the missing TMDB key instead of blaming the search term", async () => {
    search.mockRejectedValue(new Error("TMDB API key is not configured"));
    renderPage("/search?q=alien");

    expect(await screen.findByText(/search needs a tmdb api key/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /set up tmdb in settings/i })).toHaveAttribute(
      "href",
      "/settings?section=tmdb"
    );
  });

  it("reports any other failure as a failure, not as an empty result set", async () => {
    search.mockRejectedValue(new Error("Network down"));
    renderPage("/search?q=alien");

    // The toast fades; the page underneath has to keep saying what happened,
    // rather than settling into "no results" for a search that never ran.
    expect(await screen.findByText(/search failed/i)).toBeInTheDocument();
    expect(screen.getAllByText("Network down").length).toBeGreaterThan(0);
    expect(screen.queryByText(/no results found/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/tmdb api key/i)).not.toBeInTheDocument();
  });

  it("goes back to offering the filters once a later search succeeds", async () => {
    const user = userEvent.setup();
    search.mockRejectedValue(new Error("Network down"));
    renderPage("/search?q=alien");
    await screen.findByText(/search failed/i);

    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
    await user.type(queryField(), "s");

    expect(await screen.findByText("Solaris")).toBeInTheDocument();
    expect(screen.queryByText(/search failed/i)).not.toBeInTheDocument();
  });

  it("ignores a slow search that lands after a newer one", async () => {
    const user = userEvent.setup();
    let releaseStale: () => void = () => {};
    const stalePending = new Promise<void>((resolve) => { releaseStale = resolve; });
    search
      .mockImplementationOnce(async () => { await stalePending; return [result("Stale Movie")]; })
      .mockImplementationOnce(async () => { await stalePending; return []; });

    renderPage("/search?q=alien");
    await waitFor(() => expect(search).toHaveBeenCalledTimes(2));

    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
    await user.type(queryField(), "s");
    expect(await screen.findByText("Solaris")).toBeInTheDocument();

    releaseStale();
    await stalePending;
    await waitFor(() => expect(screen.getByText("Solaris")).toBeInTheDocument());
    expect(screen.queryByText("Stale Movie")).not.toBeInTheDocument();
  });
});

describe("SearchPage loading state", () => {
  // One deferred per media type: the "All" filter fires both, and `Promise.all`
  // only settles when the slower of the two does.
  function deferSearch() {
    const resolvers: Record<string, (hits: SearchResult[]) => void> = {};
    search.mockImplementation(
      (type) => new Promise<SearchResult[]>((resolve) => { resolvers[type] = resolve; })
    );
    return (movies: SearchResult[], series: SearchResult[]) => {
      resolvers.movie?.(movies);
      resolvers.series?.(series);
    };
  }

  it("fills the results region with a skeleton while the first search runs", async () => {
    const user = userEvent.setup();
    const release = deferSearch();
    renderPage();

    await user.type(queryField(), "solaris");

    // The frame this covers used to be blank: the intro state is gone the
    // moment a query is typed, the grid can't render until the response lands,
    // and the only thing left was a 16px spinner up in the filter row.
    expect(await screen.findByTestId("search-skeleton")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Browse by genre" })).not.toBeInTheDocument();

    release([result("Solaris")], []);

    expect(await screen.findByText("Solaris")).toBeInTheDocument();
    expect(screen.queryByTestId("search-skeleton")).not.toBeInTheDocument();
  });

  it("marks the previous results stale during a re-search instead of passing them off as current", async () => {
    const user = userEvent.setup();
    search.mockResolvedValueOnce([result("Solaris")]).mockResolvedValueOnce([]);
    renderPage();

    await user.type(queryField(), "solaris");
    await screen.findByText("Solaris");

    deferSearch();
    await user.click(screen.getByRole("button", { name: "Films" }));

    const region = await screen.findByTestId("search-results");
    await waitFor(() => expect(region).toHaveAttribute("aria-busy", "true"));
    // Still on screen — blanking the page mid-search is what the skeleton is
    // for, and there is nothing to blank on the first search only.
    expect(within(region).getByText("Solaris")).toBeInTheDocument();
    expect(region.className).toContain("opacity-40");
    // No second skeleton stacked under a grid that is already the right shape.
    expect(screen.queryByTestId("search-skeleton")).not.toBeInTheDocument();
  });
});

describe("SearchPage filters", () => {
  const HITS = [
    result("Solaris", { genres: ["Sci-Fi"], year: 1972, rating: 8.1 }),
    result("Sunshine", { genres: ["Sci-Fi", "Thriller"], year: 2007, rating: 7.2 }),
    result("Alien", { genres: ["Horror"], year: 1979, rating: 8.5 }),
  ];

  beforeEach(() => {
    search.mockImplementation(async (type) => (type === "movie" ? HITS : []));
  });

  /** The params of the most recent `browseSearch` for one media type. */
  const lastAsk = (type: "movie" | "series") =>
    browseSearch.mock.calls.filter(([t]) => t === type).at(-1)?.[1];

  // #515: the filters used to narrow the one page already fetched, so
  // "Horror, 1980–1989" filtered twenty results instead of searching.
  it("asks the server again with the genre, rather than filtering what it has", async () => {
    const user = userEvent.setup();
    renderPage("/search?q=night");
    expect(await screen.findByText("Alien")).toBeInTheDocument();
    browseSearch.mockClear();

    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.selectOptions(screen.getByLabelText("Genre"), "Horror");

    await waitFor(() => expect(lastAsk("movie")).toMatchObject({ query: "night", genre: "Horror", page: 1 }));
    expect(lastAsk("series")).toMatchObject({ query: "night", genre: "Horror" });
  });

  it("sends the year range and minimum rating as part of the question", async () => {
    const user = userEvent.setup();
    renderPage("/search?q=night");
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Earliest year"), "1980");
    await user.type(screen.getByLabelText("Latest year"), "1989");
    await user.selectOptions(screen.getByLabelText("TMDB rating"), "7");

    await waitFor(() =>
      expect(lastAsk("movie")).toMatchObject({ yearMin: "1980", yearMax: "1989", ratingMin: "7" })
    );
  });

  it("browses on filters alone, with nothing typed", async () => {
    renderPage("/search?genre=Horror&yearMin=1980&yearMax=1989");

    expect(await screen.findByText("Alien")).toBeInTheDocument();
    expect(lastAsk("movie")).toMatchObject({ query: "", genre: "Horror", yearMin: "1980", yearMax: "1989" });
  });

  it("starts a real browse from a genre on the empty page", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: "Horror" }));

    expect(await screen.findByText("Alien")).toBeInTheDocument();
    expect(lastAsk("movie")).toMatchObject({ genre: "Horror" });
    expect(screen.getByTestId("search-params").textContent).toBe("?genre=Horror");
  });

  it("starts a decade browse the same way", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: "1980s" }));

    await waitFor(() => expect(lastAsk("movie")).toMatchObject({ yearMin: "1980", yearMax: "1989" }));
  });

  it("has one sort control, and sorting drops nothing", async () => {
    const user = userEvent.setup();
    renderPage("/search?q=sci");
    expect(await screen.findByText("Alien")).toBeInTheDocument();

    expect(screen.getAllByRole("combobox", { name: /sort/i })).toHaveLength(1);
    await user.selectOptions(screen.getByRole("combobox", { name: /sort/i }), "title");

    await waitFor(() => {
      const titles = screen.getAllByRole("button", { name: /^View details for/ }).map((el) =>
        el.getAttribute("aria-label")?.replace("View details for ", "")
      );
      expect(titles).toEqual(["Alien", "Solaris", "Sunshine"]);
    });
  });

  it("clears the filters without clearing the search", async () => {
    const user = userEvent.setup();
    renderPage("/search?q=sci&genre=Horror");
    await screen.findByText("Alien");

    await user.click(screen.getByRole("button", { name: /^clear filters$/i }));

    await waitFor(() => expect(screen.getByTestId("search-params").textContent).toBe("?q=sci"));
    await waitFor(() => expect(lastAsk("movie")).toMatchObject({ query: "sci", genre: "" }));
  });

  it("offers a way out when the filters, not the query, emptied the page", async () => {
    const user = userEvent.setup();
    search.mockImplementation(async () => []);
    renderPage("/search?q=sci&genre=Western");

    expect(await screen.findByText(/no results found/i)).toBeInTheDocument();
    search.mockImplementation(async (type) => (type === "movie" ? HITS : []));
    await user.click(screen.getByRole("button", { name: /clear all filters/i }));

    expect(await screen.findByText("Alien")).toBeInTheDocument();
  });
});

describe("SearchPage paging", () => {
  it("loads the next page from where each media type left off, and appends it", async () => {
    const user = userEvent.setup();
    browseSearch.mockImplementation(async (type, params) => {
      const page = params.page ?? 1;
      if (type === "series") return { results: [], page, nextPage: null };
      return page === 1
        ? { results: [result("Alien")], page: 1, nextPage: 4 }
        : { results: [result("Aliens")], page, nextPage: null };
    });
    renderPage("/search?q=alien&filter=movie");
    expect(await screen.findByText("Alien")).toBeInTheDocument();
    expect(screen.getByText(/1 result so far/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Show more" }));

    expect(await screen.findByText("Aliens")).toBeInTheDocument();
    expect(screen.getByText("Alien")).toBeInTheDocument();
    expect(browseSearch).toHaveBeenLastCalledWith("movie", expect.objectContaining({ page: 4 }), expect.any(AbortSignal));
    // TMDB said there is nothing after that.
    expect(screen.queryByRole("button", { name: "Show more" })).not.toBeInTheDocument();
  });
});

describe("SearchPage browse state", () => {
  it("remembers a search that found something, and offers it again", async () => {
    const user = userEvent.setup();
    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
    renderPage("/search?q=solaris");
    await screen.findByText("Solaris");

    await user.clear(queryField());
    const recent = await screen.findByRole("button", { name: "solaris" });
    await user.click(recent);

    await waitFor(() => expect(screen.getByTestId("search-params").textContent).toBe("?q=solaris"));
  });

  it("shows what is trending, as the same cards a search draws", async () => {
    getTrending.mockImplementation(async (type) => ({
      metas: type === "movie" ? [{ id: "tt1", type: "movie", name: "Dune", year: 2021, rating: 8 }] : [],
    }));
    renderPage();

    expect(await screen.findByRole("heading", { name: "Trending this week" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "View details for Dune" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add Dune to a list" })).toBeInTheDocument();
  });
});

describe("SearchPage list membership", () => {
  beforeEach(() => {
    search.mockImplementation(async (type) => (type === "movie" ? [result("Solaris")] : []));
  });

  const openAddMenu = async (user: ReturnType<typeof userEvent.setup>) => {
    renderPage("/search?q=solaris");
    await screen.findByText("Solaris");
    await user.click(screen.getByRole("button", { name: "Add Solaris to a list" }));
    return within(screen.getByRole("group", { name: "Add Solaris to a list" }));
  };

  it("adds to the chosen list and shows the membership on the card", async () => {
    const user = userEvent.setup();
    const menu = await openAddMenu(user);

    await user.click(menu.getByRole("button", { name: "Sci-Fi Night" }));

    expect(addToList).toHaveBeenCalledWith(SCIFI.id, {
      type: "movie",
      imdbId: "tt-solaris",
      title: "Solaris",
    });
    expect(await screen.findByText(/Added "Solaris" to Sci-Fi Night/)).toBeInTheDocument();
    expect(await screen.findByText("In Sci-Fi Night")).toBeInTheDocument();
  });

  it("leaves the card unchanged when the add fails", async () => {
    const user = userEvent.setup();
    addToList.mockRejectedValue(new Error("Unable to add item"));
    const menu = await openAddMenu(user);

    await user.click(menu.getByRole("button", { name: "Sci-Fi Night" }));

    expect(await screen.findByText("Unable to add item")).toBeInTheDocument();
    expect(screen.queryByText("In Sci-Fi Night")).not.toBeInTheDocument();
  });

  it("won't offer a list the title is already in twice", async () => {
    const user = userEvent.setup();
    search.mockImplementation(async (type) =>
      type === "movie" ? [result("Solaris", { lists: [SCIFI.id] })] : []
    );
    const menu = await openAddMenu(user);

    const already = menu.getByRole("button", { name: /Sci-Fi Night/ });
    expect(already).toBeDisabled();
    expect(already).toHaveTextContent("Added");

    await user.click(already);
    expect(addToList).not.toHaveBeenCalled();
  });

  it("creates a list and adds the title to it in one go", async () => {
    const user = userEvent.setup();
    createList.mockResolvedValue({
      list: { id: "l-new", name: "Tarkovsky", kind: "custom", itemCount: 0 },
    });
    const menu = await openAddMenu(user);

    await user.click(menu.getByRole("button", { name: /create new list/i }));
    await user.type(menu.getByLabelText("New list name"), "Tarkovsky");
    await user.click(menu.getByRole("button", { name: "Add" }));

    expect(createList).toHaveBeenCalledWith("Tarkovsky");
    expect(addToList).toHaveBeenCalledWith("l-new", {
      type: "movie",
      imdbId: "tt-solaris",
      title: "Solaris",
    });
    expect(await screen.findByText(/Created "Tarkovsky" and added "Solaris"/)).toBeInTheDocument();
  });

  // The create and the add are two calls: a list that was made must stay in the
  // menu even when the add behind it fails, or retrying makes a second list.
  it("keeps a created list when the add into it fails", async () => {
    const user = userEvent.setup();
    createList.mockResolvedValue({
      list: { id: "l-new", name: "Tarkovsky", kind: "custom", itemCount: 0 },
    });
    addToList.mockRejectedValue(new Error("list is full"));
    const menu = await openAddMenu(user);

    await user.click(menu.getByRole("button", { name: /create new list/i }));
    await user.type(menu.getByLabelText("New list name"), "Tarkovsky");
    await user.click(menu.getByRole("button", { name: "Add" }));

    expect(
      await screen.findByText(/Created "Tarkovsky", but couldn't add "Solaris" \(list is full\)/)
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add Solaris to a list" }));
    expect(
      within(screen.getByRole("group", { name: "Add Solaris to a list" })).getByRole("button", {
        name: "Tarkovsky",
      })
    ).toBeInTheDocument();
    expect(createList).toHaveBeenCalledTimes(1);
  });

  it("says so when the list itself couldn't be created", async () => {
    const user = userEvent.setup();
    createList.mockRejectedValue(new Error("Name already taken"));
    const menu = await openAddMenu(user);

    await user.click(menu.getByRole("button", { name: /create new list/i }));
    await user.type(menu.getByLabelText("New list name"), "Watchlist");
    await user.click(menu.getByRole("button", { name: "Add" }));

    expect(await screen.findByText("Name already taken")).toBeInTheDocument();
    expect(addToList).not.toHaveBeenCalled();
  });

  it("reports lists it couldn't load, instead of showing an empty menu as the answer", async () => {
    const user = userEvent.setup();
    getLists.mockRejectedValue(new Error("Lists unavailable"));
    const menu = await openAddMenu(user);

    expect(await screen.findByText("Lists unavailable")).toBeInTheDocument();
    expect(menu.getByText(/no lists yet/i)).toBeInTheDocument();
  });

  it("closes the add menu on Escape", async () => {
    const user = userEvent.setup();
    await openAddMenu(user);

    await user.keyboard("{Escape}");

    await waitFor(() =>
      expect(screen.queryByRole("group", { name: "Add Solaris to a list" })).not.toBeInTheDocument()
    );
    expect(screen.getByRole("button", { name: "Add Solaris to a list" })).toHaveFocus();
  });
});
