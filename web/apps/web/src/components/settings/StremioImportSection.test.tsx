import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { StremioImportSection, stremioSummaryText } from "./StremioImportSection";
import { api } from "../../api";

vi.mock("../../api", () => ({
  api: { startStremioImport: vi.fn(), pollStremioImport: vi.fn(), cancelStremioImport: vi.fn() },
}));

const mocked = api as unknown as Record<"startStremioImport" | "pollStremioImport" | "cancelStremioImport", ReturnType<typeof vi.fn>>;

describe("StremioImportSection", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
  });
  afterEach(() => vi.useRealTimers());

  it("shows the code, polls, and reports what was imported", async () => {
    mocked.startStremioImport.mockResolvedValue({ state: "waiting", code: "ABCD", link: "https://link.stremio.com/ABCD", expiresAt: "" });
    mocked.pollStremioImport
      .mockResolvedValueOnce({ state: "importing" })
      .mockResolvedValueOnce({ state: "done", summary: { movies: 2, episodes: 10, resumePoints: 1, skipped: 0, unreadable: 0 } });

    render(<StremioImportSection profileName="Alex" />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /Import from Stremio/ }));
    });
    expect(screen.getByText("ABCD")).toBeTruthy();
    expect(screen.getByRole("link").getAttribute("href")).toBe("https://link.stremio.com/ABCD");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(screen.getByText(/Importing your Stremio library/)).toBeTruthy();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(screen.getByText(/Imported 2 films, 10 episodes, 1 resume point\./)).toBeTruthy();
    expect(mocked.pollStremioImport).toHaveBeenCalledTimes(2);
  });

  it("offers to start again when the code expires", async () => {
    mocked.startStremioImport.mockResolvedValue({ state: "waiting", code: "ABCD", link: "https://link.stremio.com/ABCD", expiresAt: "" });
    mocked.pollStremioImport.mockResolvedValue({ state: "expired" });

    render(<StremioImportSection profileName="Alex" />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /Import from Stremio/ }));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(screen.getByRole("alert").textContent).toMatch(/wasn.t approved in time/);
    expect(screen.getByRole("button", { name: /Start again/ })).toBeTruthy();
  });
});

describe("stremioSummaryText", () => {
  it("mentions skipped titles and shows read only in part", () => {
    expect(stremioSummaryText({ movies: 1, episodes: 0, resumePoints: 0, skipped: 3, unreadable: 2 })).toBe(
      "Imported 1 film, 0 episodes, 0 resume points. 3 titles without an IMDb id were skipped. " +
        "Episode history for 2 shows couldn't be read in full; their last watched episode was still imported."
    );
  });
});
