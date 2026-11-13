import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import type { SeriesProgress, TrendingMeta } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { resetDataCacheForTests } from "../utils/dataCache";
import { DiscoverPage } from "./DiscoverPage";

/*
 * How Discover fails (#514): a failed refresh over cached sections keeps them,
 * and the trending rail tells "failed", "empty" and "needs a key" apart.
 */

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getSeriesProgress: vi.fn(),
      getWatchHistory: vi.fn(),
      getWatchedTitles: vi.fn(async () => ({ movies: [], series: [] })),
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
}));

const { api, ApiError, runtimeConfig } = await import("../api");
const getSeriesProgress = vi.mocked(api.getSeriesProgress);
const getTrending = vi.mocked(api.getTrending);

const severance: SeriesProgress = {
  imdbId: "tt11280740",
  name: "Severance",
  lastSeason: 1,
  lastEpisode: 3,
  nextSeason: 1,
  nextEpisode: 4,
};

const meta = (name: string): TrendingMeta => ({ id: `tt-${name.toLowerCase()}`, name, type: "movie" });

const discover = () => (
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
  getTrending.mockResolvedValue({ metas: [] } as never);
  vi.mocked(api.getCalendar).mockResolvedValue({ calendar: [] } as never);
  vi.mocked(api.getAiConfig).mockResolvedValue({ configured: false, lastGeneratedAt: null } as never);
  vi.mocked(api.getAiRecommendations).mockResolvedValue({ metas: [] } as never);
});

describe("Discover when the core load fails", () => {
  it("keeps cached sections on screen and shows a banner instead of replacing the page", async () => {
    // A first visit fills the cache.
    const first = render(discover());
    expect(await screen.findAllByText("Severance")).not.toHaveLength(0);
    first.unmount();

    getSeriesProgress.mockRejectedValue(new ApiError("Too Many Requests", 429));
    render(discover());

    expect(await screen.findByText("Couldn't load Discover. Try again in a moment.")).toBeInTheDocument();
    expect(screen.getAllByText("Severance")).not.toHaveLength(0);
    expect(screen.queryByText("Unable to connect to the API")).toBeNull();
  });

  it("shows a page error with a working Retry when there is nothing cached", async () => {
    const user = userEvent.setup();
    getSeriesProgress.mockRejectedValueOnce(new Error("Network error"));
    render(discover());

    expect(await screen.findByText("Couldn't load Discover. Try again in a moment.")).toBeInTheDocument();
    expect(screen.queryByText("Continue Watching")).toBeNull();

    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findAllByText("Severance")).not.toHaveLength(0);
    expect(screen.queryByText("Couldn't load Discover. Try again in a moment.")).toBeNull();
  });
});

describe("Discover's lead", () => {
  it("leads with a trending title the viewer hasn't watched", async () => {
    getTrending.mockImplementation(async (type) =>
      ({ metas: type === "movie" ? [meta("Severance"), meta("Arrival")] : [] }) as never
    );
    getSeriesProgress.mockResolvedValue([{ ...severance, imdbId: "tt-severance" }]);
    render(discover());

    expect(await screen.findByRole("heading", { level: 2, name: "Arrival" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open details for Arrival" })).toBeInTheDocument();
  });
});

describe("Discover's trending rail", () => {
  it("says an empty week is empty, not that it failed", async () => {
    render(discover());

    expect(await screen.findByText("Nothing trending this week")).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load trending films.")).toBeNull();
  });

  it("offers a Retry when the request fails", async () => {
    const user = userEvent.setup();
    getTrending.mockRejectedValueOnce(new ApiError("Bad gateway", 502));
    render(discover());

    expect(await screen.findByText("Couldn't load trending films.")).toBeInTheDocument();
    getTrending.mockResolvedValue({ metas: [meta("Arrival"), meta("Solaris")] } as never);
    await user.click(screen.getByRole("button", { name: "Retry" }));

    // The first comes back as the lead, the rest as the rail.
    expect(await screen.findByRole("button", { name: "Open details for Arrival" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "View details for Solaris" })).toBeInTheDocument();
  });

  it("drops the needs-a-key note once a load succeeds", async () => {
    getTrending.mockRejectedValueOnce(new ApiError("TMDB is not configured", 503, "tmdb_not_configured"));
    const { rerender } = render(discover());

    expect(await screen.findByText("Trending needs a TMDB API key to fetch content.")).toBeInTheDocument();

    // A key added in Settings; the next load of the rail succeeds.
    getTrending.mockResolvedValue({ metas: [meta("Arrival")] } as never);
    act(() => runtimeConfig.setProfileId("second-profile"));
    rerender(discover());

    expect(await screen.findByRole("button", { name: "Open details for Arrival" })).toBeInTheDocument();
    expect(screen.queryByText("Trending needs a TMDB API key to fetch content.")).toBeNull();
  });
});
