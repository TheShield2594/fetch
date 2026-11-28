import { describe, expect, it } from "vitest";
import type { CalendarEntry } from "../api";
import { calendarEntryBadge } from "./calendarEntry";

function entry(overrides: Partial<CalendarEntry>): CalendarEntry {
  return {
    kind: "episode",
    seriesImdbId: "tt1",
    seriesName: "Show",
    poster: null,
    still: null,
    backdrop: null,
    season: 2,
    episode: 5,
    episodeName: null,
    airDate: "2026-11-28",
    airsAt: null,
    watched: false,
    overview: null,
    ...overrides,
  };
}

describe("calendarEntryBadge", () => {
  it("names a premiere", () => {
    expect(calendarEntryBadge(entry({ kind: "premiere", episode: 1 }))).toBe("Premiere");
  });

  it("names each finale", () => {
    expect(calendarEntryBadge(entry({ finale: "season" }))).toBe("Season finale");
    expect(calendarEntryBadge(entry({ finale: "series" }))).toBe("Series finale");
    expect(calendarEntryBadge(entry({ finale: "mid_season" }))).toBe("Mid-season finale");
  });

  it("puts a finale ahead of a premiere", () => {
    expect(calendarEntryBadge(entry({ kind: "premiere", episode: 1, finale: "series" }))).toBe("Series finale");
  });

  it("gives an ordinary episode, an API without finales and a film no badge", () => {
    expect(calendarEntryBadge(entry({ finale: null }))).toBeNull();
    expect(calendarEntryBadge(entry({}))).toBeNull();
    expect(calendarEntryBadge(entry({ kind: "movie", season: null, episode: null }))).toBeNull();
  });
});
