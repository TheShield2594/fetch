import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { TitlePanelProvider } from "../components/media-detail/titlePanel";
import { type CatalogList, type ListItemWithMeta, OfflineWriteQueuedError, type PlaybackProgress, type SeriesProgress } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { ShelfPage } from "./ShelfPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getLists: vi.fn(),
      getListItems: vi.fn(),
      getSeriesProgress: vi.fn(),
      getCheckin: vi.fn(),
      getWatchHistory: vi.fn(),
      markNextEpisodeWatched: vi.fn(),
      getPlayback: vi.fn(),
      deletePlayback: vi.fn(),
      savePlayback: vi.fn(),
      logWatch: vi.fn(),
    },
  };
});

// The panel has its own suites' worth of behaviour; this file is about the
// page that opens it.
vi.mock("../components/MediaDetailPanel", async () => ({
  useDetailPanel: (await import("../components/media-detail/useDetailPanel")).useDetailPanel,
  DetailPanel: ({ item }: { item: { name: string } }) => <p>Detail panel: {item.name}</p>,
}));

const { api } = await import("../api");
const getLists = vi.mocked(api.getLists);
const getListItems = vi.mocked(api.getListItems);
const getSeriesProgress = vi.mocked(api.getSeriesProgress);
const getCheckin = vi.mocked(api.getCheckin);
const getWatchHistory = vi.mocked(api.getWatchHistory);
const markNextEpisodeWatched = vi.mocked(api.markNextEpisodeWatched);
const getPlayback = vi.mocked(api.getPlayback);
const deletePlayback = vi.mocked(api.deletePlayback);
const savePlayback = vi.mocked(api.savePlayback);
const logWatch = vi.mocked(api.logWatch);

const paused = (over: Partial<PlaybackProgress> = {}): PlaybackProgress => ({
  id: "p1",
  type: "movie",
  imdbId: "tt1",
  season: null,
  episode: null,
  progress: 40,
  positionSeconds: 2_880,
  durationSeconds: 7_200,
  pausedAt: "2026-10-20T20:15:00.000Z",
  name: "Understudy",
  poster: null,
  ...over,
});

const list = (id: string, name: string): CatalogList => ({ id, name, kind: "custom", itemCount: 1 });

const item = (imdbId: string, name: string, over: Partial<ListItemWithMeta> = {}): ListItemWithMeta => ({
  listId: "l1",
  type: "movie",
  imdbId,
  addedAt: "2026-03-01T00:00:00.000Z",
  metadata: { name, poster: null, year: 2019, genres: [], rating: null },
  ...over,
});

const series = (over: Partial<SeriesProgress> = {}): SeriesProgress => ({
  imdbId: "tt9",
  name: "The Long Shore",
  lastSeason: 3,
  lastEpisode: 3,
  nextSeason: 3,
  nextEpisode: 4,
  seasonWatchedEpisodes: 3,
  seasonTotalEpisodes: 8,
  ...over,
});

function renderShelf(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <TitlePanelProvider pageTitle="Shelf" appName="Fetch">
          <ShelfPage />
        </TitlePanelProvider>
      </ToastProvider>
    </MemoryRouter>
  );
}

beforeEach(() => {
  getLists.mockResolvedValue({ lists: [list("l1", "Watchlist")] });
  getListItems.mockResolvedValue({ items: [] });
  getSeriesProgress.mockResolvedValue([]);
  getCheckin.mockResolvedValue({ checkin: null });
  getWatchHistory.mockResolvedValue([]);
  getPlayback.mockResolvedValue({ playback: [] });
  deletePlayback.mockResolvedValue(undefined);
  logWatch.mockResolvedValue({ watchEvent: { id: "we-1" } });
});

describe("ShelfPage", () => {
  it("puts shows and films in one grid rather than separate destinations", async () => {
    getListItems.mockResolvedValue({
      items: [item("tt1", "Understudy"), item("tt2", "Cold Harbour", { type: "series" })],
    });
    renderShelf();

    for (const title of ["Understudy", "Cold Harbour"]) {
      expect(await screen.findByText(title)).toBeInTheDocument();
    }
  });

  it("collects the union of every list, not just the one that happens to be selected", async () => {
    getLists.mockResolvedValue({ lists: [list("l1", "Watchlist"), list("l2", "Rewatch")] });
    getListItems.mockImplementation(async (listId: string) => ({
      items: listId === "l1" ? [item("tt1", "Understudy")] : [item("tt2", "Nightjar")],
    }));
    renderShelf();

    expect(await screen.findByText("Understudy")).toBeInTheDocument();
    expect(screen.getByText("Nightjar")).toBeInTheDocument();
  });

  it("keeps the grid up when one list fails, rather than emptying the shelf", async () => {
    getLists.mockResolvedValue({ lists: [list("l1", "Watchlist"), list("l2", "Broken")] });
    getListItems.mockImplementation(async (listId: string) => {
      if (listId === "l2") throw new Error("nope");
      return { items: [item("tt1", "Understudy")] };
    });
    renderShelf();

    expect(await screen.findByText("Understudy")).toBeInTheDocument();
  });

  /*
   * The counts live in the announced name rather than in the label. A segmented
   * control is a row of equal-width peers, and labels each carrying a
   * variable number is how that row starts wrapping on a 320px phone — but the
   * count is the reason an empty kind can be disabled rather than hidden, so it
   * has to still be *said*.
   */
  it("counts each kind in what the filter announces, so a disabled one is explained", async () => {
    getListItems.mockResolvedValue({
      items: [item("tt1", "Understudy"), item("tt2", "Cold Harbour", { type: "series" })],
    });
    renderShelf();

    const filters = within(await screen.findByRole("group", { name: "Filter the shelf by kind" })).getAllByRole("button");
    expect(filters.map((button) => button.getAttribute("aria-label"))).toEqual([
      "All, 2 titles",
      "Shows, 1 title",
      "Films, 1 title",
    ]);
    // The visible label leads the announced name, which is what SC 2.5.3 asks.
    expect(filters.map((button) => button.textContent)).toEqual(["All", "Shows", "Films"]);
  });

  /*
   * The filter row is a group of toggle buttons rather than a `tablist`: it has
   * no roving tabindex, no arrow-key navigation and no single panel to control,
   * and the Settings page's tab strip — which has all three — is the standard
   * this repo holds the role to.
   */
  it("says which filter is on with aria-pressed, not with a role it doesn't honour", async () => {
    const user = userEvent.setup();
    getListItems.mockResolvedValue({
      items: [item("tt1", "Understudy"), item("tt2", "Cold Harbour", { type: "series" })],
    });
    renderShelf();

    expect(screen.queryAllByRole("tab")).toHaveLength(0);
    expect(await screen.findByRole("button", { name: /^All/ })).toHaveAttribute("aria-pressed", "true");

    await user.click(screen.getByRole("button", { name: /^Shows/ }));

    expect(screen.getByRole("button", { name: /^Shows/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /^All/ })).toHaveAttribute("aria-pressed", "false");
  });

  /*
   * The in-progress feed is series progress, not list membership, so a show
   * watched through a webhook and never added to a list lives only in the block
   * at the top. Counting the grid alone left the Shows filter reading 0 —
   * and disabled — with shows visible above it.
   */
  it("counts what is on the page, including a show that is in no list", async () => {
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
    getSeriesProgress.mockResolvedValue([series()]);
    renderShelf();

    await screen.findByText("The Long Shore");
    expect(screen.getByRole("button", { name: "Shows, 1 title" })).toBeEnabled();
    expect(screen.getByText("2 titles · 2 kinds")).toBeInTheDocument();
  });

  it("narrows the grid to one kind, and says so in the URL so the view can be linked", async () => {
    const user = userEvent.setup();
    getListItems.mockResolvedValue({
      items: [item("tt1", "Understudy"), item("tt2", "Cold Harbour", { type: "series" })],
    });
    renderShelf();

    await user.click(await screen.findByRole("button", { name: /^Shows/ }));

    expect(screen.queryByText("Understudy")).not.toBeInTheDocument();
    expect(screen.getByText("Cold Harbour")).toBeInTheDocument();
  });

  it("opens on the kind the URL names", async () => {
    getListItems.mockResolvedValue({
      items: [item("tt1", "Understudy"), item("tt2", "Cold Harbour", { type: "series" })],
    });
    renderShelf("/?kind=show");

    expect(await screen.findByText("Cold Harbour")).toBeInTheDocument();
    expect(screen.queryByText("Understudy")).not.toBeInTheDocument();
  });

  it("disables a filter with nothing behind it instead of hiding it and moving the others", async () => {
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
    renderShelf();

    expect(await screen.findByRole("button", { name: /^Shows/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Films/ })).toBeEnabled();
  });

  it("measures a show in episodes — a tick each, with the watched ones filled", async () => {
    getSeriesProgress.mockResolvedValue([series()]);
    const { container } = renderShelf();

    await screen.findByText("The Long Shore");
    const ticks = container.querySelectorAll('[role="progressbar"] > span.flex-1');
    expect(ticks).toHaveLength(8);
    expect([...ticks].filter((tick) => (tick as HTMLElement).style.background.includes("--accent-rgb"))).toHaveLength(3);
    // Nothing on the card writes the count out in words — the ticks are the
    // count — so the ruler is the only thing that can say it.
    expect(screen.getByRole("progressbar")).toHaveAccessibleName("Season 3: 3 of 8 episodes watched");
  });

  it("does not repeat what is in progress down in the grid below it", async () => {
    getSeriesProgress.mockResolvedValue([series()]);
    renderShelf();

    await screen.findByText("Up Next");
    expect(screen.getAllByText("The Long Shore")).toHaveLength(1);
  });

  it("marks the next episode from the row, then refetches — a watch moves the whole block", async () => {
    const user = userEvent.setup();
    getSeriesProgress.mockResolvedValue([series()]);
    markNextEpisodeWatched.mockResolvedValue(undefined);
    renderShelf();

    await user.click(await screen.findByRole("button", { name: /Mark S3:E4 of The Long Shore watched/i }));

    await waitFor(() => expect(markNextEpisodeWatched).toHaveBeenCalledWith("tt9"));
    await waitFor(() => expect(getSeriesProgress).toHaveBeenCalledTimes(2));
  });

  it("opens the media panel for a title", async () => {
    const user = userEvent.setup();
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
    renderShelf();

    await user.click(await screen.findByRole("button", { name: "Open details for Understudy" }));
    expect(await screen.findByText("Detail panel: Understudy")).toBeInTheDocument();
  });

  // Where a genre on the Stats page lands.
  it("narrows the library to a genre named in the URL, and lets it go again", async () => {
    const user = userEvent.setup();
    getListItems.mockResolvedValue({
      items: [
        item("tt1", "Understudy", { metadata: { name: "Understudy", poster: null, year: 2019, genres: ["Drama"], rating: null } }),
        item("tt2", "Nightjar", { metadata: { name: "Nightjar", poster: null, year: 2019, genres: ["Horror"], rating: null } }),
      ],
    });
    renderShelf("/?genre=drama");

    expect(await screen.findByRole("button", { name: "Open details for Understudy" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open details for Nightjar" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "Show every genre" }));
    expect(await screen.findByRole("button", { name: "Open details for Nightjar" })).toBeInTheDocument();
  });

  it("points at Search when the shelf has nothing in the genre", async () => {
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
    renderShelf("/?genre=Western");

    expect(await screen.findByRole("link", { name: "Browse Western in Search" })).toHaveAttribute(
      "href",
      "/search?genre=Western"
    );
  });

  it("summarises the shelf in its own terms, counting only the kinds that are on it", async () => {
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy"), item("tt2", "Nightjar")] });
    renderShelf();

    expect(await screen.findByText("2 titles · 1 kind")).toBeInTheDocument();
  });

  it("offers somewhere to go rather than an empty grid when nothing is tracked yet", async () => {
    renderShelf();

    expect(await screen.findByText("Nothing on the shelf yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Search" })).toHaveAttribute("href", "/search");
    expect(screen.queryByRole("group", { name: "Filter the shelf by kind" })).toBeNull();
  });

  it("offers a retry when the shelf itself fails, rather than a blank page", async () => {
    const user = userEvent.setup();
    getLists.mockRejectedValueOnce(new Error("API down"));
    renderShelf();

    // By text rather than by role: the toast provider keeps two empty live
    // regions mounted, one of which is also role="alert".
    expect(await screen.findByText("API down")).toBeInTheDocument();

    getLists.mockResolvedValue({ lists: [list("l1", "Watchlist")] });
    getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Understudy")).toBeInTheDocument();
  });

  /*
   * Lists and History used to be a row of links in this header, level
   * with the subtitle and on one screen out of nine. They are the rail's
   * "Manage" group now, and the tab bar's "More" sheet on a phone — see
   * SIDEBAR_MANAGE_ITEMS. The page keeps no nav of its own.
   */
  it("carries no navigation of its own — the shell owns every destination", async () => {
    renderShelf();

    await screen.findByRole("heading", { name: "Shelf" });
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  describe("Continue Watching", () => {
    it("shows what was paused part-way through, with the time left", async () => {
      getPlayback.mockResolvedValue({ playback: [paused()] });
      renderShelf();

      const section = await screen.findByRole("region", { name: "Continue Watching" });
      expect(within(section).getByText("Understudy")).toBeInTheDocument();
      expect(within(section).getByText("Film · 72 min left")).toBeInTheDocument();
    });

    it("draws no row at all when nothing is paused", async () => {
      getListItems.mockResolvedValue({ items: [item("tt2", "Nightjar")] });
      renderShelf();

      await screen.findByText("Nightjar");
      expect(screen.queryByText("Continue Watching")).not.toBeInTheDocument();
    });

    it("keeps a paused film out of the Library grid below, as Up Next does", async () => {
      getListItems.mockResolvedValue({ items: [item("tt1", "Understudy")] });
      getPlayback.mockResolvedValue({ playback: [paused()] });
      renderShelf();

      await screen.findByText("Continue Watching");
      expect(screen.getAllByText("Understudy")).toHaveLength(1);
    });

    it("drops a show from Up Next when its next episode is the one paused", async () => {
      getSeriesProgress.mockResolvedValue([series()]);
      getPlayback.mockResolvedValue({
        playback: [paused({ type: "episode", imdbId: "tt9", season: 3, episode: 4, name: "The Long Shore" })],
      });
      renderShelf();

      await screen.findByText("Continue Watching");
      expect(screen.queryByText("Up Next")).not.toBeInTheDocument();
      expect(screen.getAllByText("The Long Shore")).toHaveLength(1);
    });

    it("follows the kind filter like the rows around it", async () => {
      getPlayback.mockResolvedValue({ playback: [paused()] });
      renderShelf("/?kind=show");

      await waitFor(() => expect(getPlayback).toHaveBeenCalled());
      expect(screen.queryByText("Continue Watching")).not.toBeInTheDocument();
    });

    it("marks the paused episode watched, clears the point, then refetches", async () => {
      const user = userEvent.setup();
      getPlayback.mockResolvedValue({
        playback: [paused({ type: "episode", imdbId: "tt9", season: 2, episode: 7, name: "The Long Shore" })],
      });
      renderShelf();

      await user.click(await screen.findByRole("button", { name: "Mark S2:E7 of The Long Shore watched" }));

      await waitFor(() =>
        expect(logWatch).toHaveBeenCalledWith(
          expect.objectContaining({ type: "episode", imdbId: "tt9", seriesImdbId: "tt9", season: 2, episode: 7 })
        )
      );
      await waitFor(() => expect(deletePlayback).toHaveBeenCalledWith("p1"));
      await waitFor(() => expect(getPlayback).toHaveBeenCalledTimes(2));
    });

    it("takes the card off at once when the watch is queued offline", async () => {
      const user = userEvent.setup();
      getPlayback.mockResolvedValue({ playback: [paused()] });
      logWatch.mockRejectedValue(new OfflineWriteQueuedError());
      renderShelf();

      await user.click(await screen.findByRole("button", { name: "Mark Understudy watched" }));

      await waitFor(() => expect(screen.queryByText("Continue Watching")).not.toBeInTheDocument());
      expect(await screen.findByText(/Saved offline/)).toBeInTheDocument();
      expect(deletePlayback).not.toHaveBeenCalled();
    });

    it("removes a title without logging it, and Undo puts the same position back", async () => {
      const user = userEvent.setup();
      const point = paused();
      getPlayback.mockResolvedValue({ playback: [point] });
      // What the API actually answers: the saved point, without the title and
      // poster the list endpoint joins in.
      const saved: Partial<PlaybackProgress> = { ...point, id: "p2" };
      delete saved.name;
      delete saved.poster;
      savePlayback.mockResolvedValue({ playback: saved as PlaybackProgress });
      renderShelf();

      await user.click(await screen.findByRole("button", { name: "Remove Understudy from Continue Watching" }));

      await waitFor(() => expect(deletePlayback).toHaveBeenCalledWith("p1"));
      expect(logWatch).not.toHaveBeenCalled();
      await waitFor(() => expect(screen.queryByText("Continue Watching")).not.toBeInTheDocument());

      await user.click(await screen.findByRole("button", { name: "Undo" }));

      await waitFor(() =>
        expect(savePlayback).toHaveBeenCalledWith(
          expect.objectContaining({ type: "movie", imdbId: "tt1", progress: 40, positionSeconds: 2_880 })
        )
      );
      const section = await screen.findByRole("region", { name: "Continue Watching" });
      expect(within(section).getByText("Understudy")).toBeInTheDocument();
    });

    it("keeps the shelf up when resume points fail to load", async () => {
      getListItems.mockResolvedValue({ items: [item("tt2", "Nightjar")] });
      getPlayback.mockRejectedValue(new Error("nope"));
      renderShelf();

      expect(await screen.findByText("Nightjar")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
    });
  });
});
