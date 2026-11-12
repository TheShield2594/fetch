import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router";
import type { CatalogList, ListItemWithMeta } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { ListsPage } from "./ListsPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getLists: vi.fn(),
      getListItems: vi.fn(),
      createList: vi.fn(),
      deleteList: vi.fn(),
      renameList: vi.fn(),
      updateList: vi.fn(),
      reorderList: vi.fn(),
      removeFromListBulk: vi.fn(),
      addToList: vi.fn(),
      removeFromList: vi.fn(),
      search: vi.fn(),
    },
  };
});

// The panel fetches metadata and history of its own the moment an item opens;
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
const getListItems = vi.mocked(api.getListItems);

const WATCHLIST: CatalogList = { id: "l-watch", name: "Watchlist", kind: "watchlist", itemCount: 1 };
const SCIFI: CatalogList = { id: "l-scifi", name: "Sci-Fi Night", kind: "custom", itemCount: 1 };

function itemIn(list: CatalogList, name: string): ListItemWithMeta {
  return {
    listId: list.id,
    type: "movie",
    imdbId: `tt-${name.toLowerCase()}`,
    addedAt: "2026-01-01T00:00:00.000Z",
    title: name,
    metadata: { name, poster: null, year: 2001, genres: [], rating: null },
  };
}

function LocationProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <p data-testid="search">{location.search}</p>
      <button type="button" onClick={() => navigate(-1)}>history back</button>
    </>
  );
}

function renderPage(initialEntry = "/lists") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ToastProvider>
        <Routes>
          <Route
            path="/lists"
            element={
              <>
                <ListsPage />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const currentSearch = () => screen.getByTestId("search").textContent;

beforeEach(() => {
  getLists.mockResolvedValue({ lists: [WATCHLIST, SCIFI] });
  getListItems.mockImplementation(async (listId: string) => ({
    items: listId === WATCHLIST.id ? [itemIn(WATCHLIST, "Alien")] : [itemIn(SCIFI, "Solaris")],
  }));
});

describe("ListsPage selection in the URL", () => {
  // No list named is the index, not the first list: every list as a cover.
  it("shows every list on the index when the URL names none", async () => {
    renderPage();

    expect(await screen.findByRole("button", { name: /^sci-fi night/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^watchlist/i })).toBeInTheDocument();
    expect(currentSearch()).toBe("");
    expect(getListItems).not.toHaveBeenCalled();
  });

  it("opens the list named in the URL", async () => {
    renderPage(`/lists?list=${SCIFI.id}`);

    expect(await screen.findByText("Solaris")).toBeInTheDocument();
    expect(screen.queryByText("Alien")).not.toBeInTheDocument();
    expect(currentSearch()).toBe(`?list=${SCIFI.id}`);
  });

  it("puts opening a list in history, so Back returns to the index", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: /^sci-fi night/i }));
    await waitFor(() => expect(currentSearch()).toBe(`?list=${SCIFI.id}`));
    expect(await screen.findByText("Solaris")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /history back/i }));

    await waitFor(() => expect(currentSearch()).toBe(""));
    expect(await screen.findByRole("button", { name: /^watchlist/i })).toBeInTheDocument();
  });

  it("goes back to the index after deleting the open list, without fetching it again", async () => {
    const user = userEvent.setup();
    vi.mocked(api.deleteList).mockResolvedValue(undefined as never);
    renderPage(`/lists?list=${SCIFI.id}`);
    expect(await screen.findByText("Solaris")).toBeInTheDocument();

    getLists.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({ lists: [WATCHLIST] }), 20))
    );
    getListItems.mockClear();
    await user.click(screen.getByRole("button", { name: "More list actions" }));
    await user.click(screen.getByRole("button", { name: /delete list/i }));
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(currentSearch()).toBe(""));
    expect(vi.mocked(api.deleteList)).toHaveBeenCalledWith(SCIFI.id);
    await waitFor(() => expect(screen.queryByRole("button", { name: /^sci-fi night/i })).not.toBeInTheDocument());
    expect(screen.queryByText(/no longer exists/i)).not.toBeInTheDocument();
    expect(getListItems).not.toHaveBeenCalled();
  });

  it("doesn't call a list missing when it was the request that failed", async () => {
    getLists.mockRejectedValue(new Error("Network down"));
    renderPage(`/lists?list=${SCIFI.id}`);

    expect(await screen.findByText(/network down/i)).toBeInTheDocument();
    expect(screen.queryByText(/no longer exists/i)).not.toBeInTheDocument();
  });

  it("says so when the link points at a list that is gone", async () => {
    renderPage("/lists?list=l-deleted");

    expect(await screen.findByText(/no longer exists/i)).toBeInTheDocument();
    // Nothing was fetched for an ID the sidebar doesn't know.
    expect(getListItems).not.toHaveBeenCalled();
    expect(currentSearch()).toBe("?list=l-deleted");
  });
});

describe("ListsPage add-item modal", () => {
  const search = vi.mocked(api.search);
  const addToList = vi.mocked(api.addToList);

  const result = (name: string, type: "movie" | "series" = "movie") => ({
    imdbId: `tt-${name.toLowerCase()}`,
    type,
    name,
    year: 2001,
    poster: null,
    description: null,
    genres: [],
    rating: null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
  });

  // Scoped to the dialog: the list behind it renders its own buttons for the
  // same titles, so an unscoped query can match the page instead of the modal.
  const openModal = async (user: ReturnType<typeof userEvent.setup>) => {
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");
    await user.click(screen.getByRole("button", { name: "Add" }));
    const dialog = within(screen.getByRole("dialog"));
    await user.type(dialog.getByLabelText("Search movies and series"), "sol");
    return dialog;
  };

  beforeEach(() => {
    // "Solaris" is already in Sci-Fi Night per the shared fixture, so the same
    // title coming back from search is the already-added case.
    search.mockImplementation(async (type) =>
      type === "movie" ? [result("Solaris"), result("Sunshine")] : []
    );
    addToList.mockResolvedValue(undefined as never);
  });

  it("plays its exit before it leaves, on Escape as on the close button", async () => {
    const user = userEvent.setup();
    await openModal(user);

    await user.keyboard("{Escape}");

    // The dialog marks itself exiting and unmounts on the animationend, which
    // jsdom never raises — so this rides the hook's fallback timer.
    expect(screen.getByRole("dialog")).toHaveClass("overlay-exit");
    // Inert as well as pointer-events:none — a focused result button would
    // otherwise still add its title on an Enter pressed during the exit.
    expect(screen.getByRole("dialog")).toHaveAttribute("inert");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument(), { timeout: 1000 });
  });

  it("searches both types at once, because the filter starts on All", async () => {
    const user = userEvent.setup();
    await openModal(user);

    await waitFor(() => expect(search).toHaveBeenCalledWith("movie", "sol", expect.any(AbortSignal)));
    expect(search).toHaveBeenCalledWith("series", "sol", expect.any(AbortSignal));
  });

  it("marks a title already in the list as added, and won't add it twice", async () => {
    const user = userEvent.setup();
    const dialog = await openModal(user);

    const alreadyAdded = await dialog.findByRole("button", { name: /Solaris/ });
    expect(alreadyAdded).toBeDisabled();
    expect(alreadyAdded).toHaveTextContent("Added");

    await user.click(alreadyAdded);
    expect(addToList).not.toHaveBeenCalled();
  });

  it("confirms an add with a toast and flips the row to Added", async () => {
    const user = userEvent.setup();
    const dialog = await openModal(user);

    const row = await dialog.findByRole("button", { name: /Sunshine/ });
    expect(row).not.toHaveTextContent("Added");

    await user.click(row);

    expect(await screen.findByText(/Added "Sunshine" to Sci-Fi Night/)).toBeInTheDocument();
    expect(addToList).toHaveBeenCalledWith(SCIFI.id, {
      type: "movie",
      imdbId: "tt-sunshine",
      title: "Sunshine",
    });
    await waitFor(() =>
      expect(dialog.getByRole("button", { name: /Sunshine/ })).toHaveTextContent("Added")
    );
  });

  it("says so when the add fails, rather than leaving the row looking untouched", async () => {
    const user = userEvent.setup();
    addToList.mockRejectedValue(new Error("list is full"));
    const dialog = await openModal(user);

    await user.click(await dialog.findByRole("button", { name: /Sunshine/ }));

    expect(await screen.findByText(/Couldn't add "Sunshine" \(list is full\)/)).toBeInTheDocument();
    expect(dialog.getByRole("button", { name: /Sunshine/ })).not.toHaveTextContent("Added");
  });

  it("ignores a slow search that lands after a newer one", async () => {
    const user = userEvent.setup();
    // The first search (two calls, since the filter starts on All) hangs until
    // released; the newer one resolves straight away. Whichever settles last
    // must not be what the user is left looking at.
    let releaseStale: () => void = () => {};
    const stalePending = new Promise<void>((resolve) => { releaseStale = resolve; });
    search
      .mockImplementationOnce(async () => { await stalePending; return [result("Stale Movie")]; })
      .mockImplementationOnce(async () => { await stalePending; return []; });

    const dialog = await openModal(user);
    await waitFor(() => expect(search).toHaveBeenCalledTimes(2));

    search.mockImplementation(async (type) => (type === "movie" ? [result("Sunshine")] : []));
    await user.type(dialog.getByLabelText("Search movies and series"), "ar");
    expect(await dialog.findByRole("button", { name: /Sunshine/ })).toBeInTheDocument();

    releaseStale();
    await stalePending;
    await waitFor(() => expect(dialog.getByRole("button", { name: /Sunshine/ })).toBeInTheDocument());
    expect(dialog.queryByRole("button", { name: /Stale Movie/ })).not.toBeInTheDocument();
  });

  // Every add refetches the whole list behind the modal, and that refetch used
  // to run uncancelled — so it could land after the user had moved on and paint
  // one list's items under another's name. Worse than a display fault: removing
  // a row then sends a DELETE against the list on screen for an item that isn't
  // in it.
  it("drops an add's refetch when the list is switched before it lands", async () => {
    const user = userEvent.setup();
    let releaseRefetch: () => void = () => {};
    const refetchPending = new Promise<void>((resolve) => { releaseRefetch = resolve; });
    const dialog = await openModal(user);

    // The refetch the add triggers hangs; everything after it resolves normally.
    getListItems.mockImplementationOnce(async () => {
      await refetchPending;
      return { items: [itemIn(SCIFI, "Solaris"), itemIn(SCIFI, "Sunshine")] };
    });
    await user.click(await dialog.findByRole("button", { name: /Sunshine/ }));
    await waitFor(() => expect(getListItems).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole("button", { name: "Close dialog" }));
    // The dialog plays its exit before unmounting, so its search results are
    // still in the document for a beat after the click.
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument(), { timeout: 1000 });
    await user.click(screen.getByRole("button", { name: "All lists" }));
    await user.click(await screen.findByRole("button", { name: /^watchlist/i }));
    expect(await screen.findByText("Alien")).toBeInTheDocument();

    releaseRefetch();
    await refetchPending;

    await waitFor(() => expect(screen.getByText("Alien")).toBeInTheDocument());
    expect(screen.queryByText("Solaris")).not.toBeInTheDocument();
    expect(screen.queryByText("Sunshine")).not.toBeInTheDocument();
  });

  it("searches only the chosen type once the filter narrows", async () => {
    const user = userEvent.setup();
    const dialog = await openModal(user);
    await waitFor(() => expect(search).toHaveBeenCalledWith("series", "sol", expect.any(AbortSignal)));

    search.mockClear();
    await user.click(dialog.getByRole("button", { name: "Series" }));

    await waitFor(() => expect(search).toHaveBeenCalledWith("series", "sol", expect.any(AbortSignal)));
    expect(search).not.toHaveBeenCalledWith("movie", "sol");
  });
});

describe("ListsPage index", () => {
  it("gives each list a cover and its description, and leaves out an empty collection", async () => {
    getLists.mockResolvedValue({
      lists: [
        WATCHLIST,
        { ...SCIFI, description: "Slow, strange, Russian", covers: ["https://image.tmdb.org/t/p/w342/a.jpg"] },
        { id: "l-coll", name: "Collection", kind: "collection", itemCount: 0 },
      ],
    });
    renderPage();

    const card = await screen.findByRole("button", { name: /^sci-fi night/i });
    expect(card).toHaveTextContent("Slow, strange, Russian");
    expect(card).toHaveTextContent("1 title");
    expect(screen.queryByRole("button", { name: /^collection/i })).not.toBeInTheDocument();
  });

  it("makes a new list from a dialog, with a description, and opens it", async () => {
    const user = userEvent.setup();
    const created: CatalogList = { id: "l-new", name: "Tarkovsky", kind: "custom", itemCount: 0 };
    vi.mocked(api.createList).mockResolvedValue({ list: created });
    renderPage();

    await user.click(await screen.findByRole("button", { name: "New list" }));
    const dialog = within(screen.getByRole("dialog", { name: "New list" }));
    getLists.mockResolvedValue({ lists: [WATCHLIST, SCIFI, created] });
    getListItems.mockResolvedValue({ items: [] });
    await user.type(dialog.getByLabelText("Name"), "Tarkovsky");
    await user.type(dialog.getByLabelText(/description/i), "All seven");
    await user.click(dialog.getByRole("button", { name: "Create" }));

    expect(api.createList).toHaveBeenCalledWith("Tarkovsky", "All seven");
    await waitFor(() => expect(currentSearch()).toBe("?list=l-new"));
  });
});

describe("ListsPage inside a list", () => {
  const removeFromListBulk = vi.mocked(api.removeFromListBulk);
  const addToList = vi.mocked(api.addToList);
  const reorderList = vi.mocked(api.reorderList);

  const three = () => ({
    items: [
      { ...itemIn(SCIFI, "Solaris"), addedAt: "2026-01-03T00:00:00.000Z" },
      { ...itemIn(SCIFI, "Stalker"), addedAt: "2026-01-02T00:00:00.000Z" },
      { ...itemIn(SCIFI, "Mirror"), addedAt: "2026-01-01T00:00:00.000Z" },
    ],
  });

  beforeEach(() => {
    window.localStorage.clear();
    getListItems.mockImplementation(async () => three());
    removeFromListBulk.mockImplementation(async (_listId, items) => ({ removed: items }));
    addToList.mockResolvedValue(undefined as never);
    reorderList.mockResolvedValue(undefined as never);
  });

  const titlesInOrder = () =>
    screen.getAllByRole("button", { name: /^Open details for/ }).map((el) => el.getAttribute("aria-label"));

  it("removes several titles in one call, and puts them back on Undo", async () => {
    const user = userEvent.setup();
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    await user.click(screen.getByRole("button", { name: "Select" }));
    await user.click(screen.getByRole("checkbox", { name: "Select Solaris" }));
    await user.click(screen.getByRole("checkbox", { name: "Select Mirror" }));
    await user.click(screen.getByRole("button", { name: "Remove" }));

    expect(removeFromListBulk).toHaveBeenCalledWith(SCIFI.id, [
      expect.objectContaining({ imdbId: "tt-solaris" }),
      expect.objectContaining({ imdbId: "tt-mirror" }),
    ]);
    expect(await screen.findByText("Removed 2 titles")).toBeInTheDocument();
    expect(screen.queryByText("Solaris")).not.toBeInTheDocument();
    expect(screen.getByText("Stalker")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Undo" }));
    await waitFor(() => expect(addToList).toHaveBeenCalledTimes(2));
    expect(addToList).toHaveBeenCalledWith(SCIFI.id, { type: "movie", imdbId: "tt-solaris", title: "Solaris" });
    expect(await screen.findByText("Solaris")).toBeInTheDocument();
  });

  it("puts every shown title in the selection with Select all", async () => {
    const user = userEvent.setup();
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    await user.click(screen.getByRole("button", { name: "Select" }));
    await user.click(screen.getByRole("button", { name: "Select all" }));

    expect(screen.getByText("3 selected")).toBeInTheDocument();
    for (const box of screen.getAllByRole("checkbox")) expect(box).toBeChecked();
  });

  it("moves the selection to another list: into it first, then out of this one", async () => {
    const user = userEvent.setup();
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    await user.click(screen.getByRole("button", { name: "Select" }));
    await user.click(screen.getByRole("checkbox", { name: "Select Stalker" }));
    await user.click(screen.getByRole("button", { name: "Move to…" }));
    await user.click(screen.getByRole("button", { name: "Watchlist" }));

    await waitFor(() =>
      expect(removeFromListBulk).toHaveBeenCalledWith(SCIFI.id, [expect.objectContaining({ imdbId: "tt-stalker" })])
    );
    expect(addToList).toHaveBeenCalledWith(WATCHLIST.id, { type: "movie", imdbId: "tt-stalker", title: "Stalker" });
    expect(addToList.mock.invocationCallOrder[0]!).toBeLessThan(removeFromListBulk.mock.invocationCallOrder[0]!);
    expect(await screen.findByText("Moved 1 title to Watchlist")).toBeInTheDocument();
    expect(screen.queryByText("Stalker")).not.toBeInTheDocument();
  });

  it("arranges the list with the move buttons and saves the order", async () => {
    const user = userEvent.setup();
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");
    // Never arranged: "My order" is newest first.
    expect(titlesInOrder()).toEqual(["Open details for Solaris", "Open details for Stalker", "Open details for Mirror"]);

    await user.click(screen.getByRole("button", { name: "Reorder" }));
    await user.click(screen.getByRole("button", { name: "Move Mirror up" }));
    await user.click(screen.getByRole("button", { name: "Save order" }));

    expect(reorderList).toHaveBeenCalledWith(SCIFI.id, [
      expect.objectContaining({ imdbId: "tt-solaris" }),
      expect.objectContaining({ imdbId: "tt-mirror" }),
      expect.objectContaining({ imdbId: "tt-stalker" }),
    ]);
    await waitFor(() =>
      expect(titlesInOrder()).toEqual(["Open details for Solaris", "Open details for Mirror", "Open details for Stalker"])
    );
  });

  it("follows the saved positions in My order", async () => {
    getListItems.mockImplementation(async () => {
      const { items } = three();
      return { items: [{ ...items[0]!, position: 2 }, { ...items[1]!, position: 0 }, { ...items[2]!, position: 1 }] };
    });
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    expect(titlesInOrder()).toEqual(["Open details for Stalker", "Open details for Mirror", "Open details for Solaris"]);
  });

  it("remembers the sort per list", async () => {
    const user = userEvent.setup();
    const first = renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    await user.selectOptions(screen.getByLabelText("Sort by"), "name");
    expect(titlesInOrder()).toEqual(["Open details for Mirror", "Open details for Solaris", "Open details for Stalker"]);
    first.unmount();

    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");
    expect(screen.getByLabelText("Sort by")).toHaveValue("name");
    expect(titlesInOrder()).toEqual(["Open details for Mirror", "Open details for Solaris", "Open details for Stalker"]);
  });

  it("saves a description from the edit dialog", async () => {
    const user = userEvent.setup();
    vi.mocked(api.updateList).mockResolvedValue({ list: SCIFI });
    renderPage(`/lists?list=${SCIFI.id}`);
    await screen.findByText("Solaris");

    await user.click(screen.getByRole("button", { name: "Edit list details" }));
    const dialog = within(screen.getByRole("dialog", { name: "Edit list" }));
    await user.type(dialog.getByLabelText(/description/i), "Three films");
    await user.click(dialog.getByRole("button", { name: "Save" }));

    expect(api.updateList).toHaveBeenCalledWith(SCIFI.id, { name: "Sci-Fi Night", description: "Three films" });
  });
});
