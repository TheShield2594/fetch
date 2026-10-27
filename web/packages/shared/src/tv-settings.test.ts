import { describe, expect, it } from "vitest";
import {
  ARGB_WHITE,
  MDBLIST_RATING_SOURCES,
  TV_SETTING_GROUPS,
  argbAlpha,
  argbToHex,
  hexToArgb,
  readField,
  readMdbListRatingSettings,
  readRankingRules,
  visibleMdbListSources,
  withField,
  withMdbListRatingSettings,
  withRankingRules,
  type TvSettingField,
  type TvSettingsDocument,
} from "./tv-settings.js";

const field = (key: string): TvSettingField => {
  const found = TV_SETTING_GROUPS.flatMap((group) => group.fields).find((f) => f.key === key);
  if (!found) throw new Error(`no field ${key}`);
  return found;
};

const TV_DOCUMENT: TvSettingsDocument = {
  version: 1,
  features: {
    player_settings: {
      stream_auto_play_mode: { type: "string", value: "MANUAL" },
      some_future_setting: { type: "boolean", value: true },
      stream_ranking_rules: {
        type: "string",
        value: JSON.stringify({ preferredResolution: "FHD_1080", maxPerSource: 3 }),
      },
    },
    trailer_settings: { autoplay: { type: "boolean", value: false } },
  },
  addedByANewerTv: { anything: 1 },
};

describe("reading and writing fields", () => {
  it("reads a stored value, and the default when it is absent or the wrong type", () => {
    expect(readField(TV_DOCUMENT, field("stream_auto_play_mode"))).toBe("MANUAL");
    expect(readField(TV_DOCUMENT, field("subtitle_size"))).toBe(100);
    expect(readField({ features: { player_settings: { subtitle_size: { type: "string", value: "x" } } } }, field("subtitle_size"))).toBe(100);
  });

  it("changes one key and carries every other key, feature and field through", () => {
    const next = withField(TV_DOCUMENT, field("stream_auto_play_mode"), "BEST_MATCH");
    expect(next.features?.player_settings?.stream_auto_play_mode).toEqual({ type: "string", value: "BEST_MATCH" });
    expect(next.features?.player_settings?.some_future_setting).toEqual({ type: "boolean", value: true });
    expect(next.features?.trailer_settings).toEqual(TV_DOCUMENT.features?.trailer_settings);
    expect(next.addedByANewerTv).toEqual({ anything: 1 });
    // The input is not modified.
    expect(TV_DOCUMENT.features?.player_settings?.stream_auto_play_mode).toEqual({ type: "string", value: "MANUAL" });
  });

  it("starts a document from nothing in the TV's shape", () => {
    expect(withField({}, field("mode"), "ADVANCED")).toEqual({
      version: 1,
      features: { experience_settings: { mode: { type: "string", value: "ADVANCED" } } },
    });
    expect(withField({}, field("subtitle_bold"), true).features?.player_settings?.subtitle_bold).toEqual({
      type: "boolean",
      value: true,
    });
  });
});

describe("ranking rules", () => {
  it("reads the stored rules over the TV's defaults", () => {
    const rules = readRankingRules(TV_DOCUMENT);
    expect(rules.preferredResolution).toBe("FHD_1080");
    expect(rules.excludeCamAndTelesync).toBe(true);
    expect(rules.maxPerSource).toBe(3);
  });

  it("keeps rule fields it doesn't edit, and leaves out the no-cap size", () => {
    const next = withRankingRules(TV_DOCUMENT, { ...readRankingRules(TV_DOCUMENT), preferHdr: false });
    const entry = next.features?.player_settings?.stream_ranking_rules as { value: string };
    const stored = JSON.parse(entry.value) as Record<string, unknown>;
    expect(stored.preferHdr).toBe(false);
    expect(stored.maxPerSource).toBe(3);
    expect("maxSizeGb" in stored).toBe(false);
  });

  it("falls back to the defaults when the stored rules aren't JSON", () => {
    const broken = { features: { player_settings: { stream_ranking_rules: { type: "string", value: "{" } } } };
    expect(readRankingRules(broken).preferHdr).toBe(true);
  });
});

describe("colours", () => {
  it("converts between ARGB ints and hex", () => {
    expect(argbToHex(ARGB_WHITE)).toBe("#ffffff");
    expect(argbAlpha(ARGB_WHITE)).toBe(255);
    expect(hexToArgb("#ffffff")).toBe(ARGB_WHITE);
    expect(hexToArgb("#000000", 0)).toBe(0);
    expect(argbToHex(hexToArgb("#12ab34", 128))).toBe("#12ab34");
    expect(argbAlpha(hexToArgb("#12ab34", 128))).toBe(128);
  });
});

describe("MDBList rating settings", () => {
  it("reads the TV's defaults from an empty document: off, everything shown, the TV's order", () => {
    const settings = readMdbListRatingSettings({});
    expect(settings.enabled).toBe(false);
    expect(Object.values(settings.shown).every(Boolean)).toBe(true);
    expect(settings.order).toEqual([...MDBLIST_RATING_SOURCES]);
  });

  it("reads what the TV synced, keeping its order and dropping sources it doesn't know", () => {
    const settings = readMdbListRatingSettings({
      features: {
        mdblist_settings: {
          mdblist_enabled: { type: "boolean", value: true },
          mdblist_show_trakt: { type: "boolean", value: false },
          mdblist_rating_order: { type: "string", value: "tomatoes,imdb,future_source,imdb" },
        },
      },
    });
    expect(settings.enabled).toBe(true);
    expect(settings.shown.trakt).toBe(false);
    expect(settings.order.slice(0, 2)).toEqual(["tomatoes", "imdb"]);
    expect(settings.order).toHaveLength(MDBLIST_RATING_SOURCES.length);
    expect(visibleMdbListSources(settings)).not.toContain("trakt");
  });

  it("writes the TV's keys and types, leaving the rest of the feature alone", () => {
    const document: TvSettingsDocument = {
      features: { mdblist_settings: { mdblist_show_on_hero: { type: "boolean", value: true } } },
    };
    const settings = readMdbListRatingSettings(document);
    const next = withMdbListRatingSettings(document, {
      ...settings,
      enabled: true,
      shown: { ...settings.shown, mal: false },
      order: ["mal", ...settings.order.filter((source) => source !== "mal")],
    });
    const feature = next.features?.mdblist_settings;
    expect(feature?.mdblist_show_on_hero).toEqual({ type: "boolean", value: true });
    expect(feature?.mdblist_enabled).toEqual({ type: "boolean", value: true });
    expect(feature?.mdblist_show_mal).toEqual({ type: "boolean", value: false });
    expect(feature?.mdblist_rating_order).toEqual({
      type: "string",
      value: "mal,imdb,trakt,tmdb,tomatoes,audience,letterboxd,metacritic",
    });
    expect(readMdbListRatingSettings(next).order[0]).toBe("mal");
  });
});
