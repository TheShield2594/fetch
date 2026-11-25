import type { CalendarEntry } from "@fetch/shared";
import { describe, expect, it } from "vitest";
import { buildCalendarFeed } from "./ical.js";

const entry = (over: Partial<CalendarEntry> = {}): CalendarEntry => ({
  kind: "episode",
  seriesImdbId: "tt1",
  seriesName: "Severance",
  poster: null,
  still: null,
  backdrop: null,
  season: 2,
  episode: 3,
  episodeName: "Who Is Alive?",
  airDate: "2026-12-31",
  airsAt: null,
  watched: false,
  overview: null,
  ...over,
});

const now = new Date("2026-11-25T03:15:00Z");
const feed = (entries: CalendarEntry[]) => buildCalendarFeed(entries, { name: "Fetch · Alex", now });

describe("buildCalendarFeed", () => {
  it("is a calendar with CRLF line endings, even when empty", () => {
    const text = feed([]);

    expect(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")).toBe(true);
    expect(text.endsWith("END:VCALENDAR\r\n")).toBe(true);
    expect(text).toContain("X-WR-CALNAME:Fetch · Alex\r\n");
    expect(text.replace(/\r\n/g, "")).not.toMatch(/[\r\n]/);
  });

  it("writes an episode with only a date as an all-day event ending the next day", () => {
    const text = feed([entry()]);

    expect(text).toContain("UID:tt1-s02e03@fetch\r\n");
    expect(text).toContain("DTSTAMP:20261125T031500Z\r\n");
    expect(text).toContain("DTSTART;VALUE=DATE:20261231\r\n");
    // Across the year boundary.
    expect(text).toContain("DTEND;VALUE=DATE:20270101\r\n");
    expect(text).toContain("SUMMARY:Severance S02E03 · Who Is Alive?\r\n");
  });

  it("uses the air time when one is known", () => {
    const text = feed([entry({ airsAt: "2026-12-31T21:00:00-05:00" })]);

    expect(text).toContain("DTSTART:20270101T020000Z\r\nDURATION:PT1H\r\n");
    expect(text).not.toContain("VALUE=DATE");
  });

  it("names a film by its title alone, and an episode without a name by its number", () => {
    const text = feed([
      entry({ kind: "movie", seriesImdbId: "tt9", seriesName: "Dune", season: null, episode: null, episodeName: null }),
      entry({ episodeName: null }),
    ]);

    expect(text).toContain("UID:tt9@fetch\r\n");
    expect(text).toContain("SUMMARY:Dune\r\n");
    expect(text).toContain("SUMMARY:Severance S02E03\r\n");
  });

  it("escapes text and folds long lines without splitting a character", () => {
    const overview = `Mark, Helly; and Irving\nreturn. ${"é".repeat(80)}`;
    const text = feed([entry({ overview })]);

    expect(text).toContain("DESCRIPTION:Mark\\, Helly\\; and Irving\\nreturn. ");
    for (const line of text.split("\r\n")) {
      expect(Buffer.byteLength(line, "utf8")).toBeLessThanOrEqual(75);
    }
    // Unfolding gives the text back whole.
    expect(text.replace(/\r\n /g, "")).toContain("é".repeat(80));
  });
});
