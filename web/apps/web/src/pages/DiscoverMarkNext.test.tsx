import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import type { SeriesProgress } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { resetDataCacheForTests } from "../utils/dataCache";
import { DiscoverPage } from "./DiscoverPage";

/*
 * "Mark the next episode" on Discover's pick-up strip (#511).
 *
 * It is the Shelf's action, with the Shelf's feedback: a toast naming the
 * episode, with an Undo that takes it back off — not a button that holds
 * "Marked" for 1.2 seconds and reloads behind a timer of its own. And it still
 * must not refetch for a page that has gone, whose answer would land in the
 * cache under whichever profile is current by then.
 */

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getSeriesProgress: vi.fn(),
      getWatchHistory: vi.fn(),
      getCheckin: vi.fn(),
      getNowPlaying: vi.fn(),
      getTrending: vi.fn(),
      getLists: vi.fn(async () => ({ lists: [] })),
      getListItems: vi.fn(async () => ({ items: [] })),
      getRecommendationsFor: vi.fn(async () => ({ metas: [] })),
      unmarkEpisodeWatched: vi.fn(async () => undefined),
      getAiConfig: vi.fn(),
      getAiRecommendations: vi.fn(),
      getCalendar: vi.fn(),
      endCheckin: vi.fn(),
      markNextEpisodeWatched: vi.fn(),
    },
  };
});

vi.mock("../components/MediaDetailPanel", () => ({
  DetailPanel: () => null,
  useDetailPanel: () => ({
    selectedItem: null,
    setSelectedItem: vi.fn(),
    panelHistory: [],
    setPanelHistory: vi.fn(),
    panelHistoryLoading: false,
    detail: null,
    detailLoading: false,
  }),
}));

const { api, runtimeConfig } = await import("../api");
const getSeriesProgress = vi.mocked(api.getSeriesProgress);
const markNextEpisodeWatched = vi.mocked(api.markNextEpisodeWatched);

const severance: SeriesProgress = {
  imdbId: "tt11280740",
  name: "Severance",
  lastSeason: 1,
  lastEpisode: 3,
  nextSeason: 1,
  nextEpisode: 4,
};

const renderDiscover = () =>
  render(
    <MemoryRouter>
      <ToastProvider>
        <DiscoverPage />
      </ToastProvider>
    </MemoryRouter>
  );

beforeEach(() => {
  resetDataCacheForTests();
  runtimeConfig.setProfileId("first-profile");
  getSeriesProgress.mockResolvedValue([severance]);
  vi.mocked(api.getWatchHistory).mockResolvedValue([]);
  vi.mocked(api.getCheckin).mockResolvedValue({ checkin: null } as never);
  vi.mocked(api.getNowPlaying).mockResolvedValue({ sessions: [] } as never);
  vi.mocked(api.getTrending).mockResolvedValue({ metas: [] } as never);
  vi.mocked(api.getCalendar).mockResolvedValue({ calendar: [] } as never);
  vi.mocked(api.getAiConfig).mockResolvedValue({ configured: false, lastGeneratedAt: null } as never);
  markNextEpisodeWatched.mockResolvedValue({ season: 1, episode: 4 } as never);
});

const markButton = () => screen.findByRole("button", { name: "Mark S1:E4 of Severance watched" });

describe("Discover's pick-up strip", () => {
  it("draws an in-progress show with the Shelf's ruler: a tick per episode of the season", async () => {
    getSeriesProgress.mockResolvedValue([
      { ...severance, seasonWatchedEpisodes: 3, seasonTotalEpisodes: 9 },
    ]);
    renderDiscover();

    expect(await screen.findByText("Pick up where you left off")).toBeInTheDocument();
    expect(
      await screen.findByRole("progressbar", { name: "Season 1: 3 of 9 episodes watched" })
    ).toBeInTheDocument();
    // No section on Discover reuses the Shelf's in-progress names.
    expect(screen.queryByText("Continue Watching")).toBeNull();
    expect(screen.queryByText("Up next")).toBeNull();
  });

  it("marks the next episode with a toast naming it, and refetches", async () => {
    const user = userEvent.setup();
    renderDiscover();
    const before = getSeriesProgress.mock.calls.length;

    await user.click(await markButton());

    expect(markNextEpisodeWatched).toHaveBeenCalledWith("tt11280740");
    expect(await screen.findByText("Marked S1:E4 of Severance")).toBeInTheDocument();
    expect(getSeriesProgress.mock.calls.length).toBeGreaterThan(before);
  });

  it("takes exactly that episode back off when Undo is pressed", async () => {
    const user = userEvent.setup();
    renderDiscover();
    await user.click(await markButton());

    await user.click(await screen.findByRole("button", { name: "Undo" }));

    expect(api.unmarkEpisodeWatched).toHaveBeenCalledWith("tt11280740", 1, 4);
  });

  it("names and undoes the episode the server marked, not the card's guess", async () => {
    // Another device moved the show on: the card still says E4, the server marked E5.
    markNextEpisodeWatched.mockResolvedValue({ season: 1, episode: 5 } as never);
    const user = userEvent.setup();
    renderDiscover();
    await user.click(await markButton());

    expect(await screen.findByText("Marked S1:E5 of Severance")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(api.unmarkEpisodeWatched).toHaveBeenCalledWith("tt11280740", 1, 5);
  });

  it("offers no Undo when an older API doesn't say which episode it marked", async () => {
    markNextEpisodeWatched.mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderDiscover();
    await user.click(await markButton());

    expect(await screen.findByText("Marked the next episode of Severance")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
  });

  it("refuses an Undo once a different profile is active", async () => {
    const user = userEvent.setup();
    renderDiscover();
    await user.click(await markButton());
    await screen.findByText("Marked S1:E4 of Severance");
    vi.mocked(api.unmarkEpisodeWatched).mockClear();

    act(() => runtimeConfig.setProfileId("second-profile"));
    await user.click(screen.getByRole("button", { name: "Undo" }));

    expect(api.unmarkEpisodeWatched).not.toHaveBeenCalled();
    expect(await screen.findByText(/was marked on another profile/)).toBeInTheDocument();
  });

  it("does not refetch when the mark lands after the page is gone", async () => {
    let finishMark: () => void = () => {};
    markNextEpisodeWatched.mockImplementation(
      () => new Promise((resolve) => { finishMark = () => resolve(undefined as never); })
    );

    const { unmount } = renderDiscover();
    const user = userEvent.setup();
    await user.click(await markButton());

    const beforeUnmount = getSeriesProgress.mock.calls.length;
    unmount();
    await act(async () => { finishMark(); });

    expect(getSeriesProgress).toHaveBeenCalledTimes(beforeUnmount);
  });

  it("does not refetch after the page is gone when the mark itself fails", async () => {
    let failMark: (error: Error) => void = () => {};
    markNextEpisodeWatched.mockImplementation(
      () => new Promise((_, reject) => { failMark = reject; })
    );

    const { unmount } = renderDiscover();
    const user = userEvent.setup();
    await user.click(await markButton());

    const beforeUnmount = getSeriesProgress.mock.calls.length;
    unmount();
    await act(async () => {
      failMark(new Error("already fully watched"));
    });

    expect(getSeriesProgress).toHaveBeenCalledTimes(beforeUnmount);
  });
});
