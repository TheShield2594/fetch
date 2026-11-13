import { describe, expect, it } from "vitest";
import {
  ARGB_WHITE,
  MDBLIST_RATING_SOURCES,
  TV_SETTING_FIELDS,
  TV_SETTING_GROUPS,
  argbAlpha,
  argbToHex,
  encodePalette,
  hexToArgb,
  isFieldVisible,
  readField,
  readMdbListRatingSettings,
  readPalette,
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

describe("the wider settings catalogue", () => {
  const settingsField = (feature: string, key: string): TvSettingField => {
    const found = TV_SETTING_FIELDS.find((f) => f.feature === feature && f.key === key);
    if (!found) throw new Error(`no field ${feature}.${key}`);
    return found;
  };

  it("never offers a key twice, or one the TV keeps on the device", () => {
    const ids = TV_SETTING_FIELDS.map((f) => `${f.feature}.${f.key}`);
    expect(new Set(ids).size).toBe(ids.length);
    // `localOnlyPlayerProfileSettingsKeys` and friends in the TV's ProfileSettingsSyncService.
    for (const deviceOnly of ["player_settings.frame_rate_matching", "player_settings.player_preference", "trailer_settings.trailer_allow_4k"]) {
      expect(ids).not.toContain(deviceOnly);
    }
  });

  it("points every visibleWhen at a field of the same feature", () => {
    for (const f of TV_SETTING_FIELDS.filter((f) => f.visibleWhen)) {
      expect(TV_SETTING_FIELDS.some((p) => p.feature === f.feature && p.key === f.visibleWhen?.key)).toBe(true);
    }
  });

  it("shows a dependent field only while its parent allows it", () => {
    const surfaces = settingsField("theme_settings", "amoled_surfaces_mode");
    expect(isFieldVisible({}, surfaces)).toBe(false);
    const amoled = withField({}, settingsField("theme_settings", "amoled_mode"), true);
    expect(isFieldVisible(amoled, surfaces)).toBe(true);
    // A parent at its default counts, so the regex field waits for the regex mode.
    expect(isFieldVisible({}, settingsField("player_settings", "stream_auto_play_regex"))).toBe(false);
  });

  it("turns pure-black surfaces off with AMOLED, as the TV does", () => {
    let doc = withField({}, settingsField("theme_settings", "amoled_mode"), true);
    doc = withField(doc, settingsField("theme_settings", "amoled_surfaces_mode"), true);
    doc = withField(doc, settingsField("theme_settings", "amoled_mode"), false);
    expect(doc.features?.theme_settings?.amoled_surfaces_mode).toEqual({ type: "boolean", value: false });
  });

  it("keeps the poster height at 3:2 with the width", () => {
    const doc = withField({}, settingsField("layout_settings", "poster_card_width_dp"), 140);
    expect(doc.features?.layout_settings?.poster_card_width_dp).toEqual({ type: "int", value: 140 });
    expect(doc.features?.layout_settings?.poster_card_height_dp).toEqual({ type: "int", value: 210 });
  });

  it("stores thresholds as floats and segment sets as sorted string sets", () => {
    const minutes = withField({}, settingsField("player_settings", "next_episode_threshold_minutes_before_end_v2"), 1.5);
    expect(minutes.features?.player_settings?.next_episode_threshold_minutes_before_end_v2).toEqual({ type: "float", value: 1.5 });
    const skip = settingsField("player_settings", "auto_skip_segment_types");
    const doc = withField({}, skip, ["recap", "intro", "recap"]);
    expect(doc.features?.player_settings?.auto_skip_segment_types).toEqual({ type: "string_set", value: ["intro", "recap"] });
    expect(readField(doc, skip)).toEqual(["intro", "recap"]);
  });

  it("reads the TV's older int threshold keys when the v2 ones are absent, and writes v2", () => {
    const minutes = settingsField("player_settings", "next_episode_threshold_minutes_before_end_v2");
    const percent = settingsField("player_settings", "next_episode_threshold_percent_v2");
    const legacy: TvSettingsDocument = {
      features: {
        player_settings: {
          next_episode_threshold_minutes_before_end: { type: "int", value: 1 },
          next_episode_threshold_percent: { type: "int", value: 98 },
        },
      },
    };
    expect(readField(legacy, minutes)).toBe(1);
    expect(readField(legacy, percent)).toBe(98);
    expect(readField({}, minutes)).toBe(2);
    const edited = withField(legacy, minutes, 3);
    expect(edited.features?.player_settings?.next_episode_threshold_minutes_before_end_v2).toEqual({ type: "float", value: 3 });
    expect(readField(edited, minutes)).toBe(3);
  });

  it("removes a nullable language for None rather than storing an empty one", () => {
    const secondary = settingsField("player_settings", "subtitle_secondary_language");
    const set = withField(TV_DOCUMENT, secondary, "fr");
    expect(readField(set, secondary)).toBe("fr");
    const cleared = withField(set, secondary, "");
    expect(cleared.features?.player_settings && "subtitle_secondary_language" in cleared.features.player_settings).toBe(false);
    expect(cleared.features?.player_settings?.some_future_setting).toEqual({ type: "boolean", value: true });
  });

  it("reads and writes the custom theme in the TV's encoding", () => {
    expect(readPalette("#112233,#445566,#778899")).toEqual(["#112233", "#445566", "#778899"]);
    expect(readPalette("nonsense")).toEqual(["#b75aff", "#ec70a9", "#ffb37a"]);
    expect(encodePalette(["#aabbcc", "ddeeff", "#010203"])).toBe("#AABBCC,#DDEEFF,#010203");
  });
});
