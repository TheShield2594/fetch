import { describe, expect, it } from "vitest";
import {
  DEFAULT_KIDS_RULES,
  parseKidsRulesWrite,
  readStoredKidsRules,
  resolveKidsRulesWrite,
  toKidsRulesView,
} from "./kids-rules.js";

const NOW = new Date("2026-11-12T12:00:00.000Z");

describe("parseKidsRulesWrite", () => {
  it("is undefined when the body has no kids rules", () => {
    expect(parseKidsRulesWrite(undefined)).toEqual({ ok: true, value: undefined });
  });

  it("reads every rule and the time they changed", () => {
    expect(
      parseKidsRulesWrite({
        autoplayEpisodeCap: null,
        approvedTitlesOnly: true,
        dailyMinutes: 90,
        bedtimeStartMinute: 20 * 60,
        bedtimeEndMinute: 7 * 60,
        hardStop: true,
        approvedTitles: [{ ids: ["tt0111161"], type: "movie", name: "A film", poster: "https://img.example/p.jpg" }],
        updatedAt: "2026-11-12T10:00:00.000Z",
      })
    ).toEqual({
      ok: true,
      value: {
        patch: {
          autoplayEpisodeCap: null,
          approvedTitlesOnly: true,
          dailyMinutes: 90,
          bedtimeStartMinute: 1200,
          bedtimeEndMinute: 420,
          hardStop: true,
          approvedTitles: [
            { ids: ["tt0111161"], type: "movie", name: "A film", poster: "https://img.example/p.jpg", background: null },
          ],
        },
        changedAt: new Date("2026-11-12T10:00:00.000Z"),
      },
    });
  });

  it("takes any addon's content ids, not only IMDb's", () => {
    const result = parseKidsRulesWrite({
      approvedTitles: [
        { ids: ["tmdb:603", "kitsu:1", " tmdb:603 "], type: "series", name: "Show" },
        { ids: ["yt_channel:abc-123"], type: "channel", name: "" },
      ],
    });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value?.patch.approvedTitles?.map((title) => title.ids)).toEqual([
        ["tmdb:603", "kitsu:1"],
        ["yt_channel:abc-123"],
      ]);
    }
  });

  it.each([
    ["not an object", "rules", /kidsRules must be an object/],
    ["a cap of zero", { autoplayEpisodeCap: 0 }, /autoplayEpisodeCap/],
    ["a fractional cap", { autoplayEpisodeCap: 2.5 }, /autoplayEpisodeCap/],
    ["more than a day", { dailyMinutes: 1441 }, /dailyMinutes/],
    ["a bedtime past midnight's minute", { bedtimeStartMinute: 1440 }, /bedtimeStartMinute/],
    ["a non-boolean switch", { hardStop: "yes" }, /hardStop/],
    ["approved titles that aren't a list", { approvedTitles: {} }, /approvedTitles/],
    ["a title with no ids", { approvedTitles: [{ ids: [], type: "movie", name: "x" }] }, /ids/],
    ["a blank id", { approvedTitles: [{ ids: [" "], type: "movie", name: "x" }] }, /ids/],
    ["an overlong id", { approvedTitles: [{ ids: ["x".repeat(201)], type: "movie", name: "x" }] }, /ids/],
    ["a title with no type", { approvedTitles: [{ ids: ["tt1"], name: "x" }] }, /type/],
    ["a poster that isn't a web address", { approvedTitles: [{ ids: ["tt1"], type: "movie", name: "x", poster: "javascript:alert(1)" }] }, /poster/],
    ["an unreadable time", { updatedAt: "yesterday" }, /updatedAt/],
  ])("rejects %s", (_label, raw, message) => {
    const result = parseKidsRulesWrite(raw);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toMatch(message);
  });

  it("rejects more approved titles than it keeps", () => {
    const titles = Array.from({ length: 1001 }, (_, i) => ({ ids: [`tt${i}`], type: "movie", name: `${i}` }));
    expect(parseKidsRulesWrite({ approvedTitles: titles }).ok).toBe(false);
  });
});

describe("resolveKidsRulesWrite", () => {
  const stored = {
    kidsRules: { ...DEFAULT_KIDS_RULES, dailyMinutes: 60 },
    kidsRulesUpdatedAt: new Date("2026-11-12T09:00:00.000Z"),
  };

  it("starts from the TV's defaults when nothing is stored", () => {
    expect(resolveKidsRulesWrite(null, { patch: { dailyMinutes: 30 }, changedAt: null }, NOW)).toEqual({
      ok: true,
      data: { kidsRules: { ...DEFAULT_KIDS_RULES, dailyMinutes: 30 }, kidsRulesUpdatedAt: NOW },
    });
  });

  it("applies a newer write over what is stored, keeping the fields it leaves out", () => {
    const changedAt = new Date("2026-11-12T10:00:00.000Z");
    expect(resolveKidsRulesWrite(stored, { patch: { hardStop: true }, changedAt }, NOW)).toEqual({
      ok: true,
      data: { kidsRules: { ...DEFAULT_KIDS_RULES, dailyMinutes: 60, hardStop: true }, kidsRulesUpdatedAt: changedAt },
    });
  });

  it("ignores a write older than what is stored (the newer change wins)", () => {
    const changedAt = new Date("2026-11-12T08:00:00.000Z");
    expect(resolveKidsRulesWrite(stored, { patch: { dailyMinutes: null }, changedAt }, NOW)).toEqual({
      ok: true,
      data: null,
    });
  });

  it("ignores a write stamped the same as what is stored", () => {
    expect(
      resolveKidsRulesWrite(stored, { patch: { dailyMinutes: 120 }, changedAt: stored.kidsRulesUpdatedAt }, NOW)
    ).toEqual({ ok: true, data: null });
  });

  it("stamps the website's own edits with the time they arrive", () => {
    const result = resolveKidsRulesWrite(stored, { patch: { dailyMinutes: 120 }, changedAt: null }, NOW);
    expect(result).toMatchObject({ ok: true, data: { kidsRulesUpdatedAt: NOW } });
  });

  it("never stamps rules later than now, so a TV clock running fast can't pin them", () => {
    const future = new Date("2027-01-01T00:00:00.000Z");
    const result = resolveKidsRulesWrite(stored, { patch: { dailyMinutes: 120 }, changedAt: future }, NOW);
    expect(result).toMatchObject({ ok: true, data: { kidsRulesUpdatedAt: NOW } });
  });

  it("refuses half a bedtime", () => {
    const result = resolveKidsRulesWrite(null, { patch: { bedtimeStartMinute: 1200 }, changedAt: null }, NOW);
    expect(result).toMatchObject({ ok: false, error: expect.stringMatching(/bedtime/) });
  });
});

describe("stored rules", () => {
  it("are null in the view when none were ever set", () => {
    expect(toKidsRulesView(null, null)).toBeNull();
  });

  it("are shown with the time they changed", () => {
    expect(toKidsRulesView({ dailyMinutes: 45 }, new Date("2026-11-12T09:00:00.000Z"))).toEqual({
      ...DEFAULT_KIDS_RULES,
      dailyMinutes: 45,
      updatedAt: "2026-11-12T09:00:00.000Z",
    });
  });

  it("keep what still reads when part of a stored document doesn't", () => {
    expect(readStoredKidsRules({ dailyMinutes: 45, hardStop: "maybe" })).toEqual({ ...DEFAULT_KIDS_RULES, dailyMinutes: 45 });
  });
});
