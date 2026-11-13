import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { isoFromDateTimeLocal, toDateTimeLocalValue, WatchDateModal } from "./WatchDateModal";

describe("the watch dialog's options", () => {
  it("logs at the date and time picked under Other date", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    render(
      <WatchDateModal target={{ kind: "movie", imdbId: "tt1", releaseDate: "2010-07-16" }} onLog={onLog} onClose={vi.fn()} />
    );

    await user.click(screen.getByRole("button", { name: "Other date" }));
    const field = screen.getByLabelText("Watch date and time");
    await user.clear(field);
    await user.type(field, "2023-05-06T21:15");
    await user.click(screen.getByRole("button", { name: "Log Watch" }));

    expect(onLog).toHaveBeenCalledWith({ watchedAt: new Date("2023-05-06T21:15").toISOString() }, undefined);
  });

  it("logs a film on its release date, and with no date at all", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    const { unmount } = render(
      <WatchDateModal target={{ kind: "movie", imdbId: "tt1", releaseDate: "2010-07-16" }} onLog={onLog} onClose={vi.fn()} />
    );
    await user.click(screen.getByRole("button", { name: "Release date" }));
    expect(onLog).toHaveBeenLastCalledWith({ watchedAt: "2010-07-16T12:00:00.000Z" }, undefined);
    unmount();

    render(<WatchDateModal target={{ kind: "movie", imdbId: "tt1", releaseDate: null }} onLog={onLog} onClose={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Release date" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /Unknown date/ }));
    expect(onLog).toHaveBeenLastCalledWith({ watchedAt: expect.any(String), dateUnknown: true }, undefined);
  });

  it("uses a fixed episode's air date and offers to check in to it", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    const onWatchNow = vi.fn().mockResolvedValue(undefined);
    render(
      <WatchDateModal
        target={{ kind: "episode", seriesImdbId: "tt1", season: 2, episode: 4, fixed: true, airDate: "2019-03-01", name: "Pilot" }}
        onLog={onLog}
        onWatchNow={onWatchNow}
        onClose={vi.fn()}
      />
    );

    // Named, not picked.
    expect(screen.queryByLabelText("Season")).toBeNull();
    expect(screen.getByRole("heading", { name: "When did you watch S2 E4?" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Now watching/ }));
    expect(onWatchNow).toHaveBeenCalledWith({ season: 2, episode: 4 });
    expect(onLog).not.toHaveBeenCalled();
  });

  it("looks up the air date of the episode in the pickers", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    const loadAirDate = vi.fn().mockResolvedValue("2020-02-02");
    render(
      <WatchDateModal
        target={{ kind: "episode", seriesImdbId: "tt1", season: 1, episode: 3 }}
        onLog={onLog}
        loadAirDate={loadAirDate}
        onClose={vi.fn()}
      />
    );

    const release = screen.getByRole("button", { name: "Release date" });
    await waitFor(() => expect(release).toBeEnabled());
    expect(loadAirDate).toHaveBeenCalledWith(1, 3, expect.any(AbortSignal));
    await user.click(release);
    expect(onLog).toHaveBeenCalledWith({ watchedAt: "2020-02-02T12:00:00.000Z" }, { season: 1, episode: 3 });
  });

  it("offers each episode's air date for a whole season", async () => {
    const user = userEvent.setup();
    const onLog = vi.fn().mockResolvedValue(undefined);
    render(
      <WatchDateModal target={{ kind: "season", seriesImdbId: "tt1", season: 1, name: "Season 1" }} onLog={onLog} onClose={vi.fn()} />
    );

    expect(screen.queryByRole("button", { name: /Now watching/ })).toBeNull();
    await user.click(screen.getByRole("button", { name: /Release dates/ }));
    expect(onLog).toHaveBeenCalledWith({ watchedAt: expect.any(String), releaseDates: true }, undefined);
  });
});

describe("datetime-local values", () => {
  it("round-trips local wall-clock time", () => {
    const d = new Date(2024, 0, 2, 3, 4);
    expect(toDateTimeLocalValue(d)).toBe("2024-01-02T03:04");
    expect(isoFromDateTimeLocal("2024-01-02T03:04")).toBe(d.toISOString());
    expect(isoFromDateTimeLocal("")).toBeNull();
  });
});
