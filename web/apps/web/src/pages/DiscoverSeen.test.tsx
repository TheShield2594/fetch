import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import type { TrendingMeta, WatchEvent } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { resetDataCacheForTests } from "../utils/dataCache";
import { DiscoverPage } from "./DiscoverPage";

/*
 * What Discover counts as already seen.
 *
 * It used to be series progress plus the newest 20 watch events, so a film
 * watched before those 20 came back as the lead ("something new to you") or
 * in a trending rail. `/watch/titles` is every title the profile has watched,
 * and these cases pin that Discover filters by it.
 */

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getSeriesProgress: vi.fn(),
      getWatchHistory: vi.fn(),
      getWatchedTitles: vi.fn(),
      getCheckin: vi.fn(),
      getNowPlaying: vi.fn(),
      getTrending: vi.fn(),
      getLists: vi.fn(async () => ({ lists: [] })),
      getListItems: vi.fn(async () => ({ items: [] })),
      getRecommendationsFor: vi.fn(async () => ({ metas: [] })),
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

const film = (name: string): TrendingMeta => ({
  id: `tt-${name.toLowerCase().replace(/\s+/g, "-")}`,
  name,
  type: "movie",
  background: `https://img.example/${name}.jpg`,
});
const show = (name: string): TrendingMeta => ({ ...film(name), type: "series" });

/** Twenty recent watches of other films: a full first page of history. */
const recentHistory: WatchEvent[] = Array.from({ length: 20 }, (_, i) => ({
  id: `ev-${i}`,
  imdbId: `tt-recent-${i}`,
  type: "movie",
  name: `Recent ${i}`,
  watchedAt: new Date(Date.UTC(2026, 10, 1, i)).toISOString(),
})) as unknown as WatchEvent[];

const OLD_FILM = film("Old Favourite");
const NEW_FILM = film("Something New");
const OLD_SHOW = show("Long Finished");
const NEW_SHOW = show("Fresh Series");

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
  runtimeConfig.setProfileId("profile-1");
  vi.mocked(api.getSeriesProgress).mockResolvedValue([]);
  vi.mocked(api.getWatchHistory).mockResolvedValue(recentHistory);
  // Watched long before the newest 20 events, and a series no longer in progress.
  vi.mocked(api.getWatchedTitles).mockResolvedValue({
    movies: [OLD_FILM.id, ...recentHistory.map((e) => e.imdbId)],
    series: [OLD_SHOW.id],
  });
  vi.mocked(api.getCheckin).mockResolvedValue({ checkin: null } as never);
  vi.mocked(api.getNowPlaying).mockResolvedValue({ sessions: [] } as never);
  vi.mocked(api.getCalendar).mockResolvedValue({ calendar: [] } as never);
  vi.mocked(api.getAiConfig).mockResolvedValue({ configured: false, lastGeneratedAt: null } as never);
  // The watched titles trend first, so only the filter keeps them out of the lead.
  vi.mocked(api.getTrending).mockImplementation(async (type) => ({
    metas: type === "movie" ? [OLD_FILM, NEW_FILM] : [OLD_SHOW, NEW_SHOW],
  }) as never);
});

const anyCardFor = (name: string) => [
  ...screen.queryAllByRole("button", { name: `View details for ${name}` }),
  ...screen.queryAllByRole("button", { name: `Open details for ${name}` }),
];

describe("Discover's seen filter", () => {
  it("never leads with a film watched outside the newest 20 events", async () => {
    renderDiscover();

    expect(await screen.findByRole("button", { name: `Open details for ${NEW_FILM.name}` })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: `Open details for ${OLD_FILM.name}` })).not.toBeInTheDocument();
  });

  it("keeps long-watched films and finished shows out of the trending rails", async () => {
    renderDiscover();

    expect(await screen.findByRole("button", { name: `View details for ${NEW_SHOW.name}` })).toBeInTheDocument();
    await waitFor(() => expect(api.getWatchedTitles).toHaveBeenCalled());
    expect(anyCardFor(OLD_FILM.name)).toHaveLength(0);
    expect(anyCardFor(OLD_SHOW.name)).toHaveLength(0);
  });

  it("falls back to recent history when the API has no /watch/titles", async () => {
    vi.mocked(api.getWatchedTitles).mockRejectedValue(new Error("Not Found"));
    renderDiscover();

    // Nothing filters the old film any more, but the page still loads.
    expect(await screen.findByRole("button", { name: `Open details for ${OLD_FILM.name}` })).toBeInTheDocument();
  });
});
