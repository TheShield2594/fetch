import { describe, expect, it } from "vitest";
import type { WatchEvent } from "../api";
import {
  dayLabel,
  dayTotals,
  episodeRangeLabel,
  groupHistory,
  monthEndIso,
  recentTitles,
} from "./history-groups";

let n = 0;
const at = (day: number, hour: number) => new Date(2026, 10, day, hour, 0).toISOString();

const film = (name: string, watchedAt: string, over: Partial<WatchEvent> = {}): WatchEvent => ({
  id: `ev-${n++}`,
  imdbId: `tt-${name}`,
  type: "movie",
  name,
  watchedAt,
  dateUnknown: false,
  runtime: 120,
  ...over,
});

const episode = (
  series: string,
  season: number,
  ep: number,
  watchedAt: string,
  over: Partial<WatchEvent> = {}
): WatchEvent => ({
  id: `ev-${n++}`,
  imdbId: `tt-${series}-${season}-${ep}`,
  seriesImdbId: `tt-${series}`,
  type: "episode",
  name: series,
  season,
  episode: ep,
  watchedAt,
  dateUnknown: false,
  runtime: 42,
  ...over,
});

describe("groupHistory", () => {
  // The issue's "done when".
  it("folds six consecutive episodes of one show into one row", () => {
    const events = [18, 17, 16, 15, 14, 13].map((ep, i) => episode("svu", 2, ep, at(10, 23 - i)));
    const days = groupHistory(events);

    expect(days).toHaveLength(1);
    expect(days[0]!.rows).toHaveLength(1);
    const row = days[0]!.rows[0]!;
    expect(row.kind).toBe("run");
    expect(row.kind === "run" && row.events).toHaveLength(6);
    expect(row.kind === "run" && episodeRangeLabel(row.events)).toBe("S2 E13–E18");
  });

  it("leaves a lone episode as an ordinary row", () => {
    const days = groupHistory([episode("svu", 2, 13, at(10, 20))]);
    expect(days[0]!.rows).toEqual([expect.objectContaining({ kind: "single" })]);
  });

  it("does not merge two sittings of a show across something else watched between them", () => {
    const days = groupHistory([
      episode("svu", 2, 16, at(10, 23)),
      episode("svu", 2, 15, at(10, 22)),
      film("Alien", at(10, 20)),
      episode("svu", 2, 14, at(10, 18)),
    ]);
    expect(days[0]!.rows.map((r) => r.kind)).toEqual(["run", "single", "single"]);
  });

  it("keeps a run inside its day", () => {
    const days = groupHistory([
      episode("svu", 2, 15, at(11, 1)),
      episode("svu", 2, 14, at(10, 23)),
      episode("svu", 2, 13, at(10, 22)),
    ]);
    expect(days.map((d) => d.key)).toEqual(["2026-11-11", "2026-11-10"]);
    expect(days[0]!.rows.map((r) => r.kind)).toEqual(["single"]);
    expect(days[1]!.rows.map((r) => r.kind)).toEqual(["run"]);
  });

  it("does not group episodes that have no series to be grouped by", () => {
    const orphan = (ep: number) => episode("x", 1, ep, at(10, 20 - ep), { seriesImdbId: undefined });
    expect(groupHistory([orphan(2), orphan(1)])[0]!.rows.map((r) => r.kind)).toEqual(["single", "single"]);
  });

  it("totals each day, and says when the time is only a floor", () => {
    const [day] = groupHistory([
      episode("svu", 2, 15, at(10, 23)),
      episode("svu", 2, 14, at(10, 22)),
      episode("svu", 2, 13, at(10, 21)),
      film("Alien", at(10, 18), { runtime: 117 }),
    ]);
    expect(day).toMatchObject({ episodes: 3, films: 1, minutes: 243, minutesPartial: false });
    expect(dayTotals(day!)).toBe("3 episodes, 1 film · 4h 3m");

    const [partial] = groupHistory([film("Alien", at(10, 18)), film("Solaris", at(10, 12), { runtime: null })]);
    expect(dayTotals(partial!)).toBe("2 films · 2h+");

    const [unknown] = groupHistory([film("Solaris", at(10, 12), { runtime: null })]);
    expect(dayTotals(unknown!)).toBe("1 film");
  });

  it("gathers undated watches under one heading of their own", () => {
    const days = groupHistory([
      film("Alien", at(10, 18), { dateUnknown: true }),
      film("Solaris", at(3, 18), { dateUnknown: true }),
    ]);
    expect(days).toHaveLength(1);
    expect(days[0]).toMatchObject({ key: "unknown", date: null, films: 2 });
    expect(dayLabel(days[0]!.date, new Date())).toBe("Date unknown");
  });
});

describe("groupHistory — undated watches among dated ones", () => {
  it("makes one undated group, last, however they were interleaved", () => {
    const days = groupHistory([
      film("Alien", at(10, 18), { dateUnknown: true }),
      film("Heat", at(9, 18)),
      film("Solaris", at(3, 18), { dateUnknown: true }),
    ]);
    expect(days.map((d) => d.key)).toEqual(["2026-11-09", "unknown"]);
    expect(days[1]).toMatchObject({ films: 2 });
  });
});

describe("groupHistory — runs stay consecutive across the undated split", () => {
  it("does not join two undated episodes that had a dated watch between them", () => {
    const days = groupHistory([
      episode("svu", 2, 15, at(10, 22), { dateUnknown: true }),
      film("Heat", at(10, 20)),
      episode("svu", 2, 14, at(10, 18), { dateUnknown: true }),
    ]);
    expect(days.map((d) => d.key)).toEqual(["2026-11-10", "unknown"]);
    expect(days[1]!.rows.map((r) => r.kind)).toEqual(["single", "single"]);
  });

  it("does not join two dated episodes that had an undated watch between them", () => {
    const days = groupHistory([
      episode("svu", 2, 15, at(10, 22)),
      film("Alien", at(10, 20), { dateUnknown: true }),
      episode("svu", 2, 14, at(10, 18)),
    ]);
    expect(days[0]!.rows.map((r) => r.kind)).toEqual(["single", "single"]);
    expect(days[1]).toMatchObject({ key: "unknown", films: 1 });
  });
});

describe("episodeRangeLabel", () => {
  it("names both ends in full across a season boundary", () => {
    expect(episodeRangeLabel([episode("svu", 3, 2, at(10, 23)), episode("svu", 2, 21, at(10, 21))])).toBe(
      "S2 E21 – S3 E2"
    );
  });

  it("reads in episode order whatever order they were watched in", () => {
    expect(episodeRangeLabel([episode("svu", 2, 3, at(10, 23)), episode("svu", 2, 9, at(10, 21))])).toBe("S2 E3–E9");
  });

  it("is one code when the same episode was watched twice", () => {
    expect(episodeRangeLabel([episode("svu", 2, 3, at(10, 23)), episode("svu", 2, 3, at(10, 21))])).toBe("S2 E3");
  });
});

describe("dayLabel", () => {
  const now = new Date(2026, 10, 12, 9, 0);

  it("names today and yesterday, then the weekday and date", () => {
    expect(dayLabel(new Date(2026, 10, 12), now)).toBe("Today");
    expect(dayLabel(new Date(2026, 10, 11), now)).toBe("Yesterday");
    const earlier = dayLabel(new Date(2026, 10, 3), now);
    expect(earlier).toMatch(/Tuesday/);
    expect(earlier).not.toMatch(/2026/);
  });

  it("adds the year once the day is in another one", () => {
    expect(dayLabel(new Date(2025, 2, 3), now)).toMatch(/2025/);
  });
});

describe("monthEndIso", () => {
  it("is the first instant of the next month, in local time", () => {
    expect(monthEndIso("2026-03")).toBe(new Date(2026, 3, 1).toISOString());
    expect(monthEndIso("2026-12")).toBe(new Date(2027, 0, 1).toISOString());
  });

  it("refuses anything that is not a month", () => {
    expect(monthEndIso("2026-13")).toBeNull();
    expect(monthEndIso("march")).toBeNull();
  });
});

describe("recentTitles", () => {
  it("shows a title once, where its latest watch was, with the count", () => {
    const titles = recentTitles([
      episode("svu", 2, 18, at(10, 23)),
      episode("svu", 2, 17, at(10, 22)),
      film("Alien", at(10, 20)),
      episode("svu", 2, 16, at(10, 18)),
    ]);
    expect(titles.map((t) => [t.latest.name, t.events.length])).toEqual([
      ["svu", 3],
      ["Alien", 1],
    ]);
    expect(titles[0]!.latest.episode).toBe(18);
  });
});
