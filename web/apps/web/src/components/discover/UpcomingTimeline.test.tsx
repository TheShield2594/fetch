import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { CalendarEntry } from "../../api";
import { UpcomingTimeline } from "./UpcomingTimeline";

function episode(name: string, episodeNumber: number, overrides: Partial<CalendarEntry> = {}): CalendarEntry {
  return {
    kind: episodeNumber === 1 ? "premiere" : "episode",
    seriesImdbId: `tt-${name.toLowerCase()}`,
    seriesName: name,
    poster: null,
    still: null,
    backdrop: null,
    season: 2,
    episode: episodeNumber,
    episodeName: null,
    airDate: "2026-12-01",
    airsAt: null,
    watched: false,
    overview: null,
    ...overrides,
  };
}

describe("UpcomingTimeline", () => {
  it("badges a finale and says so in the card's name", () => {
    render(<UpcomingTimeline entries={[episode("Andor", 12, { finale: "season" })]} onOpen={vi.fn()} />);

    expect(screen.getByText("Season finale")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Andor, Season finale, S2 E12. Open details" })).toBeInTheDocument();
  });

  it("badges a premiere without saying it twice", () => {
    render(<UpcomingTimeline entries={[episode("Severance", 1)]} onOpen={vi.fn()} />);

    expect(screen.getByText("Premiere")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Severance, Season 2 premiere. Open details" })).toBeInTheDocument();
  });

  it("leaves an ordinary episode unbadged", () => {
    render(<UpcomingTimeline entries={[episode("Silo", 4)]} onOpen={vi.fn()} />);

    expect(screen.queryByText(/finale|^Premiere$/)).not.toBeInTheDocument();
  });
});
