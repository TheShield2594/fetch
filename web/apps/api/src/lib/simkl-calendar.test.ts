import { describe, expect, it, vi } from "vitest";

vi.mock("./connections.js", () => ({ getConnection: async () => null }));
vi.mock("./simkl.js", () => ({ isSimklConfigured: () => false }));

const { monthsCovering, simklTimesFromFile } = await import("./simkl-calendar.js");

describe("simklTimesFromFile", () => {
  // The shape of data.simkl.in/calendar/v2/{year}/{month}/tv.json, trimmed.
  const file = {
    calendar: [
      { simkl_id: 1, date: "2026-11-28T02:00:00Z", episode: { season: 3, episode: 4 } },
      { simkl_id: 2, date: "2026-11-28T05:00:00Z", episode: { season: 1, episode: 7 } },
      { simkl_id: 3, date: "2026-07-20T04:00:00Z", episode: { season: 2, episode: 1 } },
      { simkl_id: 4, date: "2026-11-28T23:00:00Z" },
      { simkl_id: 5, date: "not a date", episode: { season: 1, episode: 1 } },
      { simkl_id: 6, date: "2026-11-28T23:00:00Z", episode: { season: 1, episode: 1 } },
    ],
    metadata: {
      "1": { ids: { imdb: "tt1" } },
      "2": { ids: { imdb: "tt2" } },
      "3": { ids: { imdb: "tt3" } },
      "4": { ids: { imdb: "tt4" } },
      "5": { ids: { imdb: "tt5" } },
      "6": { ids: { imdb: null } },
    },
  };

  it("keys each episode's time by IMDb id, season and episode", () => {
    expect(simklTimesFromFile(file).get("tt1:3:4")).toBe("2026-11-28T02:00:00Z");
  });

  it("leaves out midnight US Eastern, films, unreadable dates and shows without an IMDb id", () => {
    const times = simklTimesFromFile(file);
    // 05:00Z in November and 04:00Z in July are both midnight in New York: no time known.
    expect(times.has("tt2:1:7")).toBe(false);
    expect(times.has("tt3:2:1")).toBe(false);
    expect([...times.keys()]).toEqual(["tt1:3:4"]);
  });
});

describe("monthsCovering", () => {
  const day = (iso: string) => new Date(`${iso}T00:00:00Z`);

  it("lists every month a window touches, across a year end", () => {
    expect(monthsCovering(day("2026-11-27"), day("2027-01-03"))).toEqual([
      { year: 2026, month: 11 },
      { year: 2026, month: 12 },
      { year: 2027, month: 1 },
    ]);
  });

  it("starts no earlier than Simkl's first file, July 2024", () => {
    expect(monthsCovering(day("2024-05-20"), day("2024-07-02"))).toEqual([{ year: 2024, month: 7 }]);
  });
});
