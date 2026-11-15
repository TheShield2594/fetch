import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DuplicateWatchGroup } from "../../api";
import { duplicateGroupTitle, DuplicateWatchesSection } from "./DuplicateWatchesSection";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return { ...actual, api: { findDuplicateWatches: vi.fn(), mergeDuplicateWatches: vi.fn() } };
});

const { api } = await import("../../api");

const film: DuplicateWatchGroup = {
  type: "movie",
  imdbId: "tt0111161",
  season: null,
  episode: null,
  title: "The Shawshank Redemption",
  keepId: "a1",
  events: [
    { id: "a1", watchedAt: "2026-02-28T00:00:00Z", dateUnknown: false, plays: 1, note: null, fromTrakt: false },
    { id: "a2", watchedAt: "2026-03-01T05:00:00Z", dateUnknown: false, plays: 1, note: null, fromTrakt: true },
  ],
};

const episode: DuplicateWatchGroup = {
  type: "episode",
  imdbId: "tt0141842",
  season: 1,
  episode: 3,
  title: "The Sopranos",
  keepId: "b1",
  events: [
    { id: "b1", watchedAt: "2026-01-01T20:00:00Z", dateUnknown: false, plays: 1, note: null, fromTrakt: false },
    { id: "b2", watchedAt: "2026-04-01T20:00:00Z", dateUnknown: true, plays: 1, note: null, fromTrakt: false },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.findDuplicateWatches).mockResolvedValue({ groups: [film, episode], truncated: false });
  vi.mocked(api.mergeDuplicateWatches).mockResolvedValue({ merged: 1, removed: 1 });
});

describe("DuplicateWatchesSection", () => {
  it("names an episode with its season and number", () => {
    expect(duplicateGroupTitle(episode)).toBe("The Sopranos · S1 E3");
    expect(duplicateGroupTitle(film)).toBe("The Shawshank Redemption");
  });

  it("merges only the groups left ticked, after a confirmation", async () => {
    const user = userEvent.setup();
    render(<DuplicateWatchesSection profileName="Alex" />);

    await user.click(screen.getByRole("button", { name: /find duplicates/i }));
    await screen.findByText("The Shawshank Redemption");
    expect(screen.getByText("Date unknown")).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "Merge The Sopranos · S1 E3" }));
    await user.click(screen.getByRole("button", { name: /merge 1 selected/i }));
    expect(api.mergeDuplicateWatches).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Merge" }));
    await waitFor(() => expect(api.mergeDuplicateWatches).toHaveBeenCalledWith([["a1", "a2"]]));
    expect(await screen.findByText(/merged 1 duplicate, removing 1 extra watch/i)).toBeInTheDocument();
    expect(screen.queryByText("The Shawshank Redemption")).not.toBeInTheDocument();
    expect(screen.getByText("The Sopranos · S1 E3")).toBeInTheDocument();
  });

  it("says so when there is nothing to merge", async () => {
    vi.mocked(api.findDuplicateWatches).mockResolvedValue({ groups: [], truncated: false });
    const user = userEvent.setup();
    render(<DuplicateWatchesSection profileName="Alex" />);

    await user.click(screen.getByRole("button", { name: /find duplicates/i }));
    expect(await screen.findByText(/no duplicate watches found/i)).toBeInTheDocument();
  });

  it("closes an open review when a new scan starts", async () => {
    const user = userEvent.setup();
    render(<DuplicateWatchesSection profileName="Alex" />);
    await user.click(screen.getByRole("button", { name: /find duplicates/i }));
    await screen.findByText("The Shawshank Redemption");
    await user.click(screen.getByRole("button", { name: /merge 2 selected/i }));
    expect(screen.getByRole("button", { name: "Merge" })).toBeInTheDocument();

    let finish: (value: { groups: DuplicateWatchGroup[]; truncated: boolean }) => void = () => {};
    vi.mocked(api.findDuplicateWatches).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    await user.click(screen.getByRole("button", { name: /look again/i }));
    expect(screen.queryByRole("button", { name: "Merge" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /merge 2 selected/i })).toBeDisabled();

    finish({ groups: [film, episode], truncated: false });
    await waitFor(() => expect(screen.getByRole("button", { name: /merge 2 selected/i })).toBeEnabled());
    expect(api.mergeDuplicateWatches).not.toHaveBeenCalled();
  });
});
