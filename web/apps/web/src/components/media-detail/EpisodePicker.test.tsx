import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { clampEpisode } from "./EpisodePicker";
import { WatchDateModal } from "./WatchDateModal";

const SEASONS = [
  { seasonNumber: 1, name: "Season 1", episodeCount: 9 },
  { seasonNumber: 2, name: "Season 2", episodeCount: 10 },
  // Announced, nothing in it yet: not a season an episode can be logged from.
  { seasonNumber: 3, name: "Season 3", episodeCount: 0 },
];

describe("clampEpisode", () => {
  it("leaves an episode that exists alone", () => {
    expect(clampEpisode(SEASONS, { season: 2, episode: 4 })).toEqual({ season: 2, episode: 4 });
  });

  it("holds the episode inside its season", () => {
    expect(clampEpisode(SEASONS, { season: 1, episode: 12 })).toEqual({ season: 1, episode: 9 });
    expect(clampEpisode(SEASONS, { season: 1, episode: 0 })).toEqual({ season: 1, episode: 1 });
  });

  it("falls back to the last real season before one that does not exist", () => {
    expect(clampEpisode(SEASONS, { season: 3, episode: 1 })).toEqual({ season: 2, episode: 1 });
    expect(clampEpisode(SEASONS, { season: 0, episode: 2 })).toEqual({ season: 1, episode: 2 });
  });

  it("has nothing to check against without season data", () => {
    expect(clampEpisode([], { season: 4, episode: 22 })).toEqual({ season: 4, episode: 22 });
  });
});

describe("the watch dialog's episode pickers", () => {
  it("cannot log an episode the show does not have", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    // History said "episode 10 of season 1 is next"; season 1 has nine.
    render(
      <WatchDateModal
        target={{ kind: "episode", seriesImdbId: "tt1", season: 1, episode: 10 }}
        seasons={SEASONS}
        onLog={onLog}
        onClose={vi.fn()}
      />
    );

    const season = screen.getByLabelText("Season");
    const episode = screen.getByLabelText("Episode");
    expect(season.tagName).toBe("SELECT");
    expect(within(season).getAllByRole("option").map((o) => o.textContent)).toEqual(["Season 1", "Season 2"]);
    expect(within(episode).getAllByRole("option")).toHaveLength(9);
    expect((episode as HTMLSelectElement).value).toBe("9");

    // Changing season starts at its first episode rather than carrying a
    // number across.
    await user.selectOptions(season, "2");
    expect((episode as HTMLSelectElement).value).toBe("1");
    expect(within(episode).getAllByRole("option")).toHaveLength(10);

    await user.click(screen.getByRole("button", { name: "Just finished" }));
    expect(onLog).toHaveBeenCalledWith(expect.any(String), { season: 2, episode: 1 }, undefined);
  });

  it("keeps typed numbers for a show with no season data", () => {
    render(
      <WatchDateModal
        target={{ kind: "episode", seriesImdbId: "tt1", season: 2, episode: 3 }}
        onLog={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByLabelText("Season")).toHaveValue(2);
    expect(screen.getByLabelText("Episode")).toHaveValue(3);
  });
});
