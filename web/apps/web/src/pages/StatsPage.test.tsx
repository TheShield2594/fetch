import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DetailedWatchStats, WatchStats, YearInReviewStats } from "../api";
import { StatsPage } from "./StatsPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: { getWatchStats: vi.fn(), getDetailedStats: vi.fn(), getYearInReview: vi.fn() },
  };
});

// The panel is the shell's; what matters here is that a poster asks for it.
const openTitle = vi.hoisted(() => vi.fn());
vi.mock("../components/media-detail/titlePanel", () => ({
  useTitlePanel: () => ({ openTitle, current: null }),
}));

const { api } = await import("../api");
const getWatchStats = vi.mocked(api.getWatchStats);
const getDetailedStats = vi.mocked(api.getDetailedStats);
const getYearInReview = vi.mocked(api.getYearInReview);

const CURRENT_YEAR = new Date().getFullYear();

// A profile with history. The zeroed version below is its own case now: with
// nothing watched the page is an empty state and never renders the panels these
// tests are about.
const summary: WatchStats = { totalMovies: 12, totalEpisodes: 40, totalPlays: 52, playsThisWeek: 3 };
const noHistory: WatchStats = { totalMovies: 0, totalEpisodes: 0, totalPlays: 0, playsThisWeek: 0 };
const detailed: DetailedWatchStats = {
  monthly: [],
  genreDistribution: [],
  currentStreak: 0,
  longestStreak: 0,
  topRated: [],
};
const yearReview = (year: number): YearInReviewStats => ({
  year,
  totalMovies: 0,
  totalEpisodes: 0,
  totalRuntimeMinutes: 0,
  topGenres: [],
  // A 9 out of ten, which the panel has to draw as 4.5 out of five.
  topRated: [{ imdbId: "tt1", name: "Heat", type: "movie", rating: 9, poster: null }],
  busiestMonth: null,
  busiestMonthCount: 0,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <StatsPage />
    </MemoryRouter>
  );

describe("StatsPage — Year in Review", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    getWatchStats.mockResolvedValue(summary);
    getDetailedStats.mockResolvedValue(detailed);
    getYearInReview.mockImplementation(async (year: number) => yearReview(year));
  });

  const yearPicker = () => screen.getByLabelText("Year in review: year") as HTMLSelectElement;

  it("opens on the year you are in, not the one that just ended", async () => {
    renderPage();

    await waitFor(() => expect(getYearInReview).toHaveBeenCalled());
    expect(getYearInReview).toHaveBeenCalledWith(CURRENT_YEAR);
    expect(yearPicker().value).toBe(String(CURRENT_YEAR));
  });

  it("lists the current year first and still offers the earlier ones", async () => {
    renderPage();

    await waitFor(() => expect(getYearInReview).toHaveBeenCalled());
    const options = Array.from(yearPicker().options).map((option) => option.value);
    expect(options[0]).toBe(String(CURRENT_YEAR));
    expect(options).toContain(String(CURRENT_YEAR - 1));
  });

  it("shows your own ratings on the five-star scale", async () => {
    // Your rating, unlike the TMDB community score elsewhere on the page.
    renderPage();

    await waitFor(() => expect(getYearInReview).toHaveBeenCalled());
    expect(await screen.findByRole("img", { name: "Your rating: 4.5 out of 5" })).toBeInTheDocument();
  });

  it("loads the year the reader picks", async () => {
    renderPage();
    await waitFor(() => expect(getYearInReview).toHaveBeenCalled());

    await userEvent.selectOptions(yearPicker(), String(CURRENT_YEAR - 1));

    await waitFor(() => expect(getYearInReview).toHaveBeenCalledWith(CURRENT_YEAR - 1));
    expect(yearPicker().value).toBe(String(CURRENT_YEAR - 1));
  });
});

describe("StatsPage — a profile with no history", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    getWatchStats.mockResolvedValue(noHistory);
    getDetailedStats.mockResolvedValue(detailed);
    getYearInReview.mockImplementation(async (year: number) => yearReview(year));
  });


  // Four zero tiles, a flat chart and "No badges earned yet" is a screen that
  // reads as broken. It is the expected state for a new profile, and the page
  // has to say so and point somewhere.
  it("explains the emptiness and offers a way out of it", async () => {
    renderPage();

    expect(await screen.findByText(/nothing watched yet/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /find something to watch/i })).toHaveAttribute("href", "/search");
  });

  it("shows none of the zeroed panels", async () => {
    renderPage();
    await screen.findByText(/nothing watched yet/i);

    expect(screen.queryByLabelText("Year in review: year")).not.toBeInTheDocument();
    expect(screen.queryByText(/no badges earned yet/i)).not.toBeInTheDocument();
  });

  // The empty state is about having watched nothing, not about the request
  // failing — a load error still has to report itself.
  it("reports a failed load instead", async () => {
    getWatchStats.mockRejectedValue(new Error("Stats unavailable"));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent("Stats unavailable");
    expect(screen.queryByText(/nothing watched yet/i)).not.toBeInTheDocument();
  });
});

// #521: the page was a dead end — nothing on it was clickable.
describe("StatsPage — everything leads somewhere", () => {
  const rich: DetailedWatchStats = {
    monthly: [
      { month: "2026-02", movies: 0, episodes: 142 },
      { month: "2026-03", movies: 3, episodes: 0 },
      { month: "2026-04", movies: 1, episodes: 12 },
    ],
    genreDistribution: [{ genre: "Science Fiction", count: 9 }, { genre: "Drama", count: 4 }],
    currentStreak: 2,
    longestStreak: 9,
    topRated: [{ imdbId: "tt-heat", name: "Heat", type: "movie", rating: 8.3, poster: null }],
  };
  const richYear = (year: number): YearInReviewStats => ({
    year,
    totalMovies: 4,
    totalEpisodes: 154,
    totalRuntimeMinutes: 6_000,
    topGenres: [{ genre: "Crime", count: 30 }],
    topRated: [{ imdbId: "tt-ronin", name: "Ronin", type: "movie", rating: 9, poster: null }],
    mostWatched: [{ imdbId: "tt-svu", type: "series", name: "Law & Order: SVU", poster: null, plays: 142 }],
    busiestMonth: 1,
    busiestMonthCount: 142,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    getWatchStats.mockResolvedValue(summary);
    getDetailedStats.mockResolvedValue(rich);
    getYearInReview.mockImplementation(async (year: number) => richYear(year));
  });

  it("is called what the navigation calls it", async () => {
    renderPage();
    await screen.findByRole("heading", { name: `${CURRENT_YEAR} in review` });
    expect(screen.getByRole("heading", { level: 1, name: "Stats" })).toBeInTheDocument();
  });

  it("leads with the year, said as a sentence, before anything else on the page", async () => {
    renderPage();

    const year = await screen.findByRole("heading", { name: `${CURRENT_YEAR} in review` });
    expect(await screen.findByText("4 films and 154 episodes.")).toBeInTheDocument();
    const chart = screen.getByRole("heading", { name: "Last 12 months" });
    expect(year.compareDocumentPosition(chart) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("opens a title from every poster on the page", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "View details for Law & Order: SVU" }));
    expect(openTitle).toHaveBeenLastCalledWith(expect.objectContaining({ imdbId: "tt-svu", type: "series" }));

    await user.click(screen.getByRole("button", { name: "View details for Ronin" }));
    expect(openTitle).toHaveBeenLastCalledWith(expect.objectContaining({ imdbId: "tt-ronin", type: "movie" }));

    await user.click(screen.getByRole("button", { name: "View details for Heat" }));
    expect(openTitle).toHaveBeenLastCalledWith(expect.objectContaining({ imdbId: "tt-heat", type: "movie" }));
  });

  it("opens the library on a genre, from the all-time list and from the year's", async () => {
    renderPage();

    expect(await screen.findByRole("link", { name: /^Science Fiction, 9 watched\./ })).toHaveAttribute(
      "href",
      "/?genre=Science%20Fiction"
    );
    expect(await screen.findByRole("link", { name: /^Crime, 30 watched this year\./ })).toHaveAttribute(
      "href",
      "/?genre=Crime"
    );
  });

  it("opens History at a month, from the chart and from the busiest month", async () => {
    renderPage();

    // A month with only films has a column of its own to read, and a name
    // that says so.
    const march = await screen.findByRole("link", { name: /^March 2026: 0 episodes, 3 films\. Open in History$/ });
    expect(march).toHaveAttribute("href", "/history?month=2026-03");
    expect(screen.getByRole("link", { name: /^February 2026: 142 episodes, 0 films/ })).toHaveAttribute(
      "href",
      "/history?month=2026-02"
    );
    expect(await screen.findByRole("link", { name: "February" })).toHaveAttribute(
      "href",
      `/history?month=${CURRENT_YEAR}-02`
    );
  });

  it("draws your rating as stars and a community score as a labelled number", async () => {
    renderPage();

    expect(await screen.findByRole("img", { name: "Your rating: 4.5 out of 5" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "TMDB: Rated 8.3 out of 10" })).toHaveTextContent("TMDB 8.3");
  });

  it("captions posters in words, not with the API's type", async () => {
    renderPage();
    await screen.findByRole("button", { name: "View details for Heat" });

    expect(screen.queryByText("movie")).toBeNull();
    expect(screen.queryByText("series")).toBeNull();
    expect(screen.getByText("142 episodes")).toBeInTheDocument();
  });
});
