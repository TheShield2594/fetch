/**
 * An expanded season as episodes rather than a checklist: a still per episode,
 * and a synopsis that is kept back until the episode has been watched.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SeasonsSection } from "./SeasonsSection";

const getWatchedEpisodes = vi.fn();
const getSeasonEpisodes = vi.fn();

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getWatchedEpisodes: (...args: unknown[]) => getWatchedEpisodes(...args),
      getSeasonEpisodes: (...args: unknown[]) => getSeasonEpisodes(...args),
      getTitleRatings: async () => ({ ratings: [] }),
    },
  };
});

const SEASONS = [{ seasonNumber: 1, name: "Season 1", episodeCount: 3, airYear: 2022, poster: null }];

const EPISODES = [
  {
    episodeNumber: 1,
    name: "Good News About Hell",
    runtime: 57,
    airDate: "2022-02-18",
    still: "https://image.tmdb.org/t/p/w500/one.jpg",
    overview: "Mark is promoted to lead a team whose memories are divided.",
  },
  {
    episodeNumber: 2,
    name: "Half Loop",
    runtime: 53,
    airDate: "2022-02-18",
    still: "https://image.tmdb.org/t/p/w500/two.jpg",
    overview: "The team trains a new hire who wants to leave.",
  },
  { episodeNumber: 3, name: "In Perpetuity", runtime: 56, airDate: "2022-02-25", still: null, overview: null },
];

beforeEach(() => {
  getWatchedEpisodes.mockResolvedValue({ episodes: [{ season: 1, episode: 1 }] });
  getSeasonEpisodes.mockResolvedValue({ episodes: EPISODES });
});

describe("episode rows", () => {
  it("shows a still for each episode that has one, and keeps the column for one that doesn't", async () => {
    const { container } = render(<SeasonsSection imdbId="tt1" seasons={SEASONS} loading={false} />);

    await screen.findByText("Half Loop");
    const stills = [...container.querySelectorAll("img")];
    expect(stills.map((img) => img.getAttribute("src"))).toEqual([
      "https://image.tmdb.org/t/p/w500/one.jpg",
      "https://image.tmdb.org/t/p/w500/two.jpg",
    ]);
    // Offered at the widths TMDB publishes for stills, not the poster ladder.
    expect(stills[0]?.getAttribute("srcset")).toBe(
      "https://image.tmdb.org/t/p/w185/one.jpg 185w, https://image.tmdb.org/t/p/w300/one.jpg 300w"
    );
    // Three frames for three rows: the one without a picture holds its place.
    expect(container.querySelectorAll(".aspect-video")).toHaveLength(3);
  });

  it("leaves the still column out of a season that has no stills", async () => {
    getSeasonEpisodes.mockResolvedValue({
      episodes: EPISODES.map((ep) => ({ ...ep, still: null })),
    });
    const { container } = render(<SeasonsSection imdbId="tt1" seasons={SEASONS} loading={false} />);

    await screen.findByText("Half Loop");
    expect(container.querySelectorAll(".aspect-video")).toHaveLength(0);
  });

  it("shows the synopsis of a watched episode and keeps an unwatched one back until asked", async () => {
    const user = userEvent.setup();
    render(<SeasonsSection imdbId="tt1" seasons={SEASONS} loading={false} />);

    // Watched: there to read, and pressing it opens the rest.
    const watched = await screen.findByText(/Mark is promoted to lead a team/);
    expect(watched.className).toContain("line-clamp-2");
    await user.click(watched);
    expect(screen.getByText(/Mark is promoted to lead a team/).className).not.toContain("line-clamp-2");

    // Not watched: not in the document at all, rather than blurred in it.
    expect(screen.queryByText(/wants to leave/)).not.toBeInTheDocument();
    const reveal = screen.getByRole("button", { name: "Show synopsis of Half Loop" });
    expect(reveal).toHaveAttribute("aria-expanded", "false");
    await user.click(reveal);
    expect(screen.getByText(/wants to leave/)).toBeInTheDocument();

    // And nothing is offered for an episode TMDB has no synopsis for.
    expect(screen.queryByRole("button", { name: "Show synopsis of In Perpetuity" })).not.toBeInTheDocument();
  });
});
