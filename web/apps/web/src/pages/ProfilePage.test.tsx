import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Profile } from "../api";
import { ProfileContext } from "../hooks/useProfile";
import { ToastProvider } from "../hooks/useToast";
import { ProfilePage } from "./ProfilePage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getWatchStats: vi.fn(),
      getDetailedStats: vi.fn(),
      getYearInReview: vi.fn(),
      getProfileSummary: vi.fn(),
      getLists: vi.fn(),
      getProfiles: vi.fn(),
      exportData: vi.fn(),
    },
  };
});

const { api } = await import("../api");

const ALEX: Profile = { id: "p-alex", name: "Alex", hasPin: false };

const renderPage = (profile: Profile = ALEX) =>
  render(
    <MemoryRouter>
      <ToastProvider>
        <ProfileContext.Provider
          value={{
            profile,
            setProfile: vi.fn(),
            patchProfile: vi.fn(),
            switcherOpen: false,
            openSwitcher: vi.fn(),
            closeSwitcher: vi.fn(),
          }}
        >
          <ProfilePage />
        </ProfileContext.Provider>
      </ToastProvider>
    </MemoryRouter>
  );

beforeEach(() => {
  vi.mocked(api.getWatchStats).mockResolvedValue({ totalMovies: 1204, totalEpisodes: 3310, totalPlays: 4600, playsThisWeek: 9 });
  vi.mocked(api.getDetailedStats).mockResolvedValue({
    monthly: [],
    genreDistribution: [],
    currentStreak: 0,
    longestStreak: 12,
    topRated: [],
  });
  vi.mocked(api.getYearInReview).mockResolvedValue({
    year: 2026,
    totalMovies: 80,
    totalEpisodes: 400,
    totalRuntimeMinutes: 25_230,
    topGenres: [],
    topRated: [],
    busiestMonth: null,
    busiestMonthCount: 0,
  });
  vi.mocked(api.getProfileSummary).mockResolvedValue({ watchEvents: 4600, lists: 2, listItems: 30, playbackProgress: 3, ratings: 57, collections: 0 });
  vi.mocked(api.getLists).mockResolvedValue({
    lists: [{ id: "l1", name: "Watchlist", kind: "watchlist", itemCount: 28 }],
  });
  vi.mocked(api.getProfiles).mockResolvedValue({ profiles: [ALEX] });
});

describe("ProfilePage", () => {
  it("puts the profile's totals somewhere other than its delete dialog", async () => {
    renderPage();

    expect(screen.getByRole("heading", { level: 1, name: "Alex" })).toBeInTheDocument();
    const totals = await screen.findByRole("region", { name: "Watched" });
    expect(await within(totals).findByText("1,204")).toBeInTheDocument();
    expect(within(totals).getByText("3,310")).toBeInTheDocument();
    // 25,230 minutes this year.
    expect(within(totals).getByText("421")).toBeInTheDocument();
    expect(within(totals).getByText("57")).toBeInTheDocument();
  });

  it("lists the profile's lists and the services it is signed in to, and offers its export", async () => {
    renderPage();

    expect(await screen.findByText("Watchlist")).toBeInTheDocument();
    expect(screen.getByText("28 titles")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Trakt/ })).toHaveAttribute("href", "/settings?section=trakt");
    expect(screen.getByRole("button", { name: "Export Alex's data" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Notifications for Alex" })).toHaveAttribute("href", "/settings?section=notifications");
  });

  it("gives a kids profile its totals and lists, but no settings, services or data", async () => {
    renderPage({ ...ALEX, parental: { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false } });

    expect(await screen.findByText("Watchlist")).toBeInTheDocument();
    expect(screen.getByText("Kids")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit profile" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Trakt/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Export/ })).not.toBeInTheDocument();
  });
});
