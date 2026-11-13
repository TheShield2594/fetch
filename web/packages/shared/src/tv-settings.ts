/**
 * The TV app's per-profile settings, as the website edits them.
 *
 * The API stores this document without reading it (`PUT /profiles/:id/
 * settings/tv`); the TV app owns its schema. This module is the website's half
 * of that contract: the shape the TV's `ProfileSettingsSyncService` writes, and
 * the settings the website offers forms for, grouped into the pages of TV
 * settings (appearance, layout, playback, subtitles, streams, content). Every
 * key here is one the TV syncs and applies live; its defaults are the TV's.
 *
 * The document is
 *
 *     { "version": 1, "features": { "<feature>": { "<key>": { "type": "string", "value": "…" } } } }
 *
 * where a feature is one of the TV's DataStores (`player_settings`,
 * `experience_settings`, …) and each key is one of its preference keys, typed
 * the way DataStore stores it. Settings that only make sense on one device —
 * refresh-rate matching, passthrough, decoders, buffers — are never in it: the
 * TV leaves them out when it pushes (`localOnlyPlayerProfileSettingsKeys`), and
 * nothing here offers them.
 *
 * Every write goes through `withTvValue` / `withRankingRules`, which copy the
 * document and change one key, so features and keys the website doesn't know
 * about — added by a newer TV — survive a save from the website untouched.
 */

export type TvPreferenceType = "string" | "boolean" | "int" | "long" | "float" | "double" | "string_set";

export type TvPreference =
  | { type: "string"; value: string }
  | { type: "boolean"; value: boolean }
  | { type: "int" | "long" | "float" | "double"; value: number }
  | { type: "string_set"; value: string[] };

export type TvSettingsDocument = {
  version?: number;
  features?: Record<string, Record<string, TvPreference | unknown>>;
  [key: string]: unknown;
};

/** The document version the TV writes today. */
export const TV_SETTINGS_DOCUMENT_VERSION = 1;

export const PLAYER_FEATURE = "player_settings";
export const EXPERIENCE_FEATURE = "experience_settings";
export const RANKING_RULES_KEY = "stream_ranking_rules";

export const THEME_FEATURE = "theme_settings";
export const LAYOUT_FEATURE = "layout_settings";
export const TRAILER_FEATURE = "trailer_settings";
export const STREAM_BADGE_FEATURE = "stream_badge_settings";
export const TMDB_FEATURE = "tmdb_settings";
export const TRAKT_FEATURE = "trakt_settings";

type Option = { value: string; label: string };
type NumberOption = { value: number; label: string };

type FieldBase = {
  feature: string;
  key: string;
  label: string;
  help?: string;
  /**
   * Shown only while "Show advanced settings" is on, as the TV only shows it
   * in Advanced mode. Advanced fields are always saved through; this only hides
   * the control.
   */
  advanced?: boolean;
  /**
   * Shown only while another key of the same feature holds one of these values
   * (its stored value, or its field's default). That key must be a field here.
   */
  visibleWhen?: { key: string; oneOf: readonly (string | boolean | number)[] };
};

export type TvSettingField =
  | (FieldBase & {
      kind: "select";
      options: Option[];
      defaultValue: string;
      /** The option that removes the key instead of storing a value: the TV's "none" for a nullable setting. */
      unsetValue?: string;
    })
  /** A fixed list of numbers, stored as an int. */
  | (FieldBase & {
      kind: "choice";
      options: NumberOption[];
      defaultValue: number;
      /** Another int key in the same feature the TV keeps in step with this one (poster height from width). */
      linked?: { key: string; from: (value: number) => number };
    })
  | (FieldBase & {
      kind: "toggle";
      defaultValue: boolean;
      /** Keys in the same feature the TV turns off with this one (AMOLED takes pure-black surfaces with it). */
      offAlsoClears?: string[];
    })
  | (FieldBase & {
      kind: "number";
      min: number;
      max: number;
      step: number;
      unit?: string;
      /** How the TV stores it; int unless said otherwise. */
      numberType?: "int" | "float";
      defaultValue: number;
    })
  /** An ARGB colour stored as a signed 32-bit int, as Compose's `Color.toArgb()` gives it. */
  | (FieldBase & { kind: "color"; defaultValue: number })
  /** A set of strings, stored as a `string_set`. */
  | (FieldBase & { kind: "multi"; options: Option[]; defaultValue: string[] })
  | (FieldBase & { kind: "text"; placeholder?: string; defaultValue: string })
  /** Comma-joined `#RRGGBB` colours, as the TV's `CustomThemeColors.encode()` writes them. */
  | (FieldBase & { kind: "palette"; count: number; defaultValue: string });

/** Which page of the website's TV settings a group sits on. */
export type TvSettingSectionId = "appearance" | "layout" | "playback" | "subtitles" | "streams" | "content";

export type TvSettingGroup = {
  id: string;
  section: TvSettingSectionId;
  title: string;
  help?: string;
  fields: TvSettingField[];
};

/** Every language the TV's subtitle picker offers, `AVAILABLE_SUBTITLE_LANGUAGES` in the app. */
export const TV_LANGUAGES: readonly (readonly [code: string, label: string])[] = [
  ["af", "Afrikaans"],
  ["sq", "Albanian"],
  ["am", "Amharic"],
  ["ar", "Arabic"],
  ["hy", "Armenian"],
  ["az", "Azerbaijani"],
  ["eu", "Basque"],
  ["be", "Belarusian"],
  ["bn", "Bengali"],
  ["bs", "Bosnian"],
  ["bg", "Bulgarian"],
  ["my", "Burmese"],
  ["ca", "Catalan"],
  ["zh", "Chinese"],
  ["zh-CN", "Chinese (Simplified)"],
  ["zh-TW", "Chinese (Traditional)"],
  ["hr", "Croatian"],
  ["cs", "Czech"],
  ["da", "Danish"],
  ["nl", "Dutch"],
  ["en", "English"],
  ["et", "Estonian"],
  ["tl", "Filipino"],
  ["fi", "Finnish"],
  ["fr", "French"],
  ["gl", "Galician"],
  ["ka", "Georgian"],
  ["de", "German"],
  ["el", "Greek"],
  ["gu", "Gujarati"],
  ["he", "Hebrew"],
  ["hi", "Hindi"],
  ["hu", "Hungarian"],
  ["is", "Icelandic"],
  ["id", "Indonesian"],
  ["ga", "Irish"],
  ["it", "Italian"],
  ["ja", "Japanese"],
  ["kn", "Kannada"],
  ["kk", "Kazakh"],
  ["km", "Khmer"],
  ["ko", "Korean"],
  ["lo", "Lao"],
  ["lv", "Latvian"],
  ["lt", "Lithuanian"],
  ["mk", "Macedonian"],
  ["ms", "Malay"],
  ["ml", "Malayalam"],
  ["mt", "Maltese"],
  ["mr", "Marathi"],
  ["mn", "Mongolian"],
  ["ne", "Nepali"],
  ["no", "Norwegian"],
  ["pa", "Punjabi"],
  ["fa", "Persian"],
  ["pl", "Polish"],
  ["pt", "Portuguese (Portugal)"],
  ["pt-br", "Portuguese (Brazil)"],
  ["ro", "Romanian"],
  ["ru", "Russian"],
  ["sr", "Serbian"],
  ["si", "Sinhala"],
  ["sk", "Slovak"],
  ["sl", "Slovenian"],
  ["es", "Spanish"],
  ["es-419", "Spanish (Latin America)"],
  ["sw", "Swahili"],
  ["sv", "Swedish"],
  ["ta", "Tamil"],
  ["te", "Telugu"],
  ["th", "Thai"],
  ["tr", "Turkish"],
  ["uk", "Ukrainian"],
  ["ur", "Urdu"],
  ["uz", "Uzbek"],
  ["vi", "Vietnamese"],
  ["cy", "Welsh"],
  ["zu", "Zulu"],
];

const languageOptions: Option[] = TV_LANGUAGES.map(([value, label]) => ({ value, label }));

/** ARGB white and fully transparent, the TV's subtitle defaults. */
export const ARGB_WHITE = -1;
export const ARGB_TRANSPARENT = 0;

const tmdbLanguageOptions: Option[] = [
  ...languageOptions,
  { value: "en-AU", label: "English (Australia)" },
  { value: "en-CA", label: "English (Canada)" },
  { value: "en-GB", label: "English (United Kingdom)" },
].sort((a, b) => a.label.localeCompare(b.label));

const seconds = (values: number[]): NumberOption[] => values.map((value) => ({ value, label: `${value} s` }));

/** `Int.MAX_VALUE`: the TV's "wait for every addon". */
export const AUTOPLAY_TIMEOUT_UNLIMITED = 2147483647;

/** The TV's `CustomThemeColors.Default`. */
export const DEFAULT_CUSTOM_THEME_COLORS = "#B75AFF,#EC70A9,#FFB37A";

/** `THEME_CARD_CORNER_RADIUS`: corners follow the theme. */
const THEME_CORNER_RADIUS = -1;

const ARGB_BLACK = -16777216;

export const TV_SETTING_GROUPS: TvSettingGroup[] = [
  // ─── Appearance ───
  {
    id: "experience",
    section: "appearance",
    title: "Settings mode",
    fields: [
      {
        kind: "select",
        feature: EXPERIENCE_FEATURE,
        key: "mode",
        label: "Settings shown on the TV",
        help: "Essential keeps about 25 settings; Advanced shows all of them. The other TV pages here open with advanced settings showing when this is Advanced.",
        options: [
          { value: "ESSENTIAL", label: "Essential" },
          { value: "ADVANCED", label: "Advanced" },
        ],
        defaultValue: "ESSENTIAL",
      },
    ],
  },
  {
    id: "theme",
    section: "appearance",
    title: "Colour theme",
    fields: [
      {
        kind: "select",
        feature: THEME_FEATURE,
        key: "selected_theme",
        label: "Theme",
        options: [
          { value: "FETCH", label: "Fetch" },
          { value: "OCEAN", label: "Ocean" },
          { value: "EMERALD", label: "Emerald" },
          { value: "AMBER", label: "Amber" },
          { value: "WHITE", label: "White" },
          { value: "CUSTOM", label: "Custom" },
        ],
        defaultValue: "FETCH",
      },
      {
        kind: "palette",
        feature: THEME_FEATURE,
        key: "custom_theme_colors",
        label: "Custom colours",
        help: "Pick the same colour three times for a solid accent, or three for a gradient.",
        count: 3,
        defaultValue: DEFAULT_CUSTOM_THEME_COLORS,
        visibleWhen: { key: "selected_theme", oneOf: ["CUSTOM"] },
      },
      {
        kind: "select",
        feature: THEME_FEATURE,
        key: "selected_font",
        label: "App font",
        options: [
          { value: "SCHIBSTED_GROTESK", label: "Fetch (Schibsted Grotesk)" },
          { value: "INTER", label: "Inter" },
          { value: "DM_SANS", label: "DM Sans" },
          { value: "OPEN_SANS", label: "Open Sans" },
        ],
        defaultValue: "SCHIBSTED_GROTESK",
      },
      {
        kind: "toggle",
        feature: THEME_FEATURE,
        key: "amoled_mode",
        label: "AMOLED mode",
        help: "Pure black app backgrounds.",
        defaultValue: false,
        offAlsoClears: ["amoled_surfaces_mode"],
      },
      {
        kind: "toggle",
        feature: THEME_FEATURE,
        key: "amoled_surfaces_mode",
        label: "Pure black surfaces",
        help: "Cards, panels and containers go pure black too.",
        defaultValue: false,
        visibleWhen: { key: "amoled_mode", oneOf: [true] },
      },
    ],
  },
  {
    id: "accessibility",
    section: "appearance",
    title: "Accessibility",
    fields: [
      {
        kind: "toggle",
        feature: THEME_FEATURE,
        key: "high_contrast",
        label: "Increase contrast",
        help: "Brighter secondary text and borders, and a heavier focus outline.",
        defaultValue: false,
      },
      {
        kind: "toggle",
        feature: THEME_FEATURE,
        key: "reduce_motion",
        label: "Reduce motion",
        help: "Holds shimmers, slides and the rotating hero still.",
        defaultValue: false,
      },
    ],
  },

  // ─── Layout ───
  {
    id: "home",
    section: "layout",
    title: "Home",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "hero_section_enabled", label: "Show the hero", help: "The large featured title at the top of Home.", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "modern_hero_full_screen_backdrop", label: "Full-screen hero backdrop", defaultValue: true, visibleWhen: { key: "hero_section_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "sidebar_collapsed_by_default", label: "Collapse the sidebar", help: "Hidden until it has focus.", defaultValue: false },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "startup_splash_enabled", label: "Startup splash", help: "The logo and a loading indicator after picking a profile.", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "hide_unreleased_content", label: "Hide unreleased titles", defaultValue: false },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "home_imdb_ratings_visibility",
        label: "IMDb ratings on Home",
        options: [
          { value: "SHOW_ALL", label: "Show" },
          { value: "HIDE_ALL", label: "Hide" },
        ],
        defaultValue: "SHOW_ALL",
      },
    ],
  },
  {
    id: "posters",
    section: "layout",
    title: "Posters",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "modern_landscape_posters_enabled", label: "Landscape posters", help: "Wide cards everywhere.", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "always_show_landscape_clearlogo", label: "Always use a backdrop with the logo", help: "Ignore an addon's own landscape poster.", defaultValue: false, advanced: true, visibleWhen: { key: "modern_landscape_posters_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "poster_labels_enabled", label: "Titles under posters", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "catalog_addon_name_enabled", label: "Addon name under row titles", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "catalog_type_suffix_enabled", label: "Type after row titles", help: "Popular – Movies, rather than Popular.", defaultValue: true, advanced: true },
      {
        kind: "choice",
        feature: LAYOUT_FEATURE,
        key: "poster_card_width_dp",
        label: "Card size",
        options: [
          { value: 104, label: "Compact" },
          { value: 112, label: "Dense" },
          { value: 120, label: "Standard" },
          { value: 126, label: "Balanced" },
          { value: 134, label: "Comfort" },
          { value: 140, label: "Large" },
        ],
        defaultValue: 126,
        linked: { key: "poster_card_height_dp", from: (width) => Math.round((width * 3) / 2) },
      },
      {
        kind: "choice",
        feature: LAYOUT_FEATURE,
        key: "poster_card_corner_radius_dp",
        label: "Card corners",
        options: [
          { value: THEME_CORNER_RADIUS, label: "Follow the theme" },
          { value: 0, label: "Sharp" },
          { value: 4, label: "Subtle" },
          { value: 8, label: "Classic" },
          { value: 12, label: "Rounded" },
          { value: 16, label: "Pill" },
        ],
        defaultValue: THEME_CORNER_RADIUS,
      },
    ],
  },
  {
    id: "focused-card",
    section: "layout",
    title: "Focused card",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "focused_poster_backdrop_expand_enabled", label: "Expand the focused card to its backdrop", defaultValue: true },
      { kind: "number", feature: LAYOUT_FEATURE, key: "focused_poster_backdrop_expand_delay_seconds", label: "Expand after", unit: "s", min: 0, max: 10, step: 1, defaultValue: 3, advanced: true, visibleWhen: { key: "focused_poster_backdrop_expand_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "focused_poster_backdrop_trailer_enabled", label: "Play a trailer preview", help: "For the focused title, when it has one.", defaultValue: false },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "focused_poster_backdrop_trailer_muted", label: "Mute trailer previews", defaultValue: true, visibleWhen: { key: "focused_poster_backdrop_trailer_enabled", oneOf: [true] } },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "focused_poster_backdrop_trailer_playback_target",
        label: "Where the preview plays",
        options: [
          { value: "HERO_MEDIA", label: "In the hero" },
          { value: "EXPANDED_CARD", label: "In the expanded card" },
        ],
        defaultValue: "HERO_MEDIA",
        advanced: true,
        visibleWhen: { key: "focused_poster_backdrop_trailer_enabled", oneOf: [true] },
      },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "trailer_crop_to_fill", label: "Crop trailers to fill", help: "Off shows the whole picture with black bars.", defaultValue: true, advanced: true },
    ],
  },
  {
    id: "continue-watching",
    section: "layout",
    title: "Continue Watching",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "continue_watching_enabled", label: "Show Continue Watching", defaultValue: true },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "continue_watching_card_style",
        label: "Card style",
        options: [
          { value: "CARD", label: "Card" },
          { value: "WIDE", label: "Wide" },
          { value: "POSTER", label: "Poster" },
        ],
        defaultValue: "CARD",
        visibleWhen: { key: "continue_watching_enabled", oneOf: [true] },
      },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "continue_watching_sort_mode",
        label: "Order",
        options: [
          { value: "DEFAULT", label: "Most recent first" },
          { value: "STREAMING_STYLE", label: "Streaming-service style" },
          { value: "SPLIT_UPCOMING", label: "Upcoming episodes in their own row" },
        ],
        defaultValue: "DEFAULT",
        visibleWhen: { key: "continue_watching_enabled", oneOf: [true] },
      },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "use_episode_thumbnails_in_cw", label: "Episode thumbnails", help: "Off uses the show's backdrop.", defaultValue: true, visibleWhen: { key: "continue_watching_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "show_unaired_next_up", label: "Include episodes that haven't aired", defaultValue: true, visibleWhen: { key: "continue_watching_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "next_up_from_furthest_episode", label: "Next up from the furthest episode", help: "Turn off for rewatches, to follow the episode watched most recently.", defaultValue: true, advanced: true, visibleWhen: { key: "continue_watching_enabled", oneOf: [true] } },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "blur_continue_watching_next_up", label: "Blur next-up episodes", help: "Avoids spoilers from episode stills.", defaultValue: false, advanced: true, visibleWhen: { key: "continue_watching_enabled", oneOf: [true] } },
    ],
  },
  {
    id: "detail",
    section: "layout",
    title: "Detail pages",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "blur_unwatched_episodes", label: "Blur unwatched episodes", help: "Hides stills, titles and overviews until watched.", defaultValue: false },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "detail_imdb_ratings_visibility",
        label: "IMDb ratings on detail pages",
        options: [
          { value: "SHOW_ALL", label: "Show all" },
          { value: "HIDE_UNWATCHED_EPISODES", label: "Hide for unwatched episodes" },
          { value: "HIDE_EPISODES", label: "Hide episode ratings" },
        ],
        defaultValue: "SHOW_ALL",
      },
      {
        kind: "select",
        feature: LAYOUT_FEATURE,
        key: "episode_options_overlay_style",
        label: "Episode menu background",
        options: [
          { value: "BLUR", label: "Blur" },
          { value: "ARTWORK", label: "Artwork" },
          { value: "NONE", label: "None" },
        ],
        defaultValue: "BLUR",
        advanced: true,
      },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "show_full_release_date", label: "Full release date for movies", help: "Rather than the year only.", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "detail_page_trailer_button_enabled", label: "Trailer button", defaultValue: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "random_episode_enabled", label: "Shuffle button for shows", defaultValue: false, advanced: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "prefer_external_meta_addon_detail", label: "Prefer details from a metadata addon", help: "Over the addon the row came from.", defaultValue: true, advanced: true },
    ],
  },
  {
    id: "trailers",
    section: "layout",
    title: "Trailers on detail pages",
    fields: [
      { kind: "toggle", feature: TRAILER_FEATURE, key: "trailer_enabled", label: "Auto-play trailers", help: "On detail and post-play screens.", defaultValue: true },
      { kind: "number", feature: TRAILER_FEATURE, key: "trailer_delay_seconds", label: "Start after", unit: "s", min: 3, max: 15, step: 1, defaultValue: 7, visibleWhen: { key: "trailer_enabled", oneOf: [true] } },
      { kind: "toggle", feature: TRAILER_FEATURE, key: "trailer_play_in_background", label: "Play in place of the backdrop", defaultValue: false, visibleWhen: { key: "trailer_enabled", oneOf: [true] } },
      { kind: "toggle", feature: TRAILER_FEATURE, key: "trailer_pause_on_scroll", label: "Pause when scrolling down", defaultValue: true, visibleWhen: { key: "trailer_enabled", oneOf: [true] } },
    ],
  },
  {
    id: "navigation",
    section: "layout",
    title: "Navigation",
    fields: [
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "search_system_keyboard", label: "Use the TV's keyboard in Search", help: "It may cover the results while you type.", defaultValue: false },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "fast_horizontal_navigation_enabled", label: "Fast horizontal navigation", help: "Quicker D-pad repeat along rows.", defaultValue: false, advanced: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "smooth_bring_into_view_enabled", label: "Smooth scrolling to the focused item", defaultValue: true, advanced: true },
      { kind: "toggle", feature: LAYOUT_FEATURE, key: "follow_addons_order", label: "Order Home rows by addon order", defaultValue: false, advanced: true },
    ],
  },

  // ─── Playback ───
  {
    id: "autoplay",
    section: "playback",
    title: "Auto-play",
    fields: [
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_mode",
        label: "When you pick a title",
        options: [
          { value: "BEST_MATCH", label: "Play the best match" },
          { value: "FIRST_STREAM", label: "Play the first stream" },
          { value: "REGEX_MATCH", label: "Play the first stream matching a pattern" },
          { value: "MANUAL", label: "Let me choose" },
        ],
        defaultValue: "BEST_MATCH",
      },
      {
        kind: "text",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_regex",
        label: "Pattern (regular expression)",
        placeholder: "2160p.*REMUX",
        defaultValue: "",
        visibleWhen: { key: "stream_auto_play_mode", oneOf: ["REGEX_MATCH"] },
      },
      {
        kind: "choice",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_timeout_seconds",
        label: "Wait for addons",
        options: [
          { value: 0, label: "Don't wait" },
          ...seconds([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30]),
          { value: AUTOPLAY_TIMEOUT_UNLIMITED, label: "Until every addon answers" },
        ],
        defaultValue: 3,
        advanced: true,
        visibleWhen: { key: "stream_auto_play_mode", oneOf: ["BEST_MATCH", "FIRST_STREAM", "REGEX_MATCH"] },
      },
      {
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_start_early",
        label: "Start without waiting for every addon",
        help: "Plays the best stream so far once it reaches your preferred quality. Peer-to-peer torrents never start early.",
        defaultValue: true,
        visibleWhen: { key: "stream_auto_play_mode", oneOf: ["BEST_MATCH"] },
      },
      {
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_next_source_on_failure",
        label: "Try the next stream when one fails",
        defaultValue: true,
        visibleWhen: { key: "stream_auto_play_mode", oneOf: ["BEST_MATCH", "FIRST_STREAM", "REGEX_MATCH"] },
      },
      {
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "best_match_preview_enabled",
        label: "Show the pick on the Play button",
        help: "Asks your stream addons about a title while you stay on it, so debrid services count those requests.",
        defaultValue: true,
        advanced: true,
      },
      {
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "ready_check_enabled",
        label: "Check what's ready in the background",
        help: "Marks titles on Home that start instantly. Only on Wi-Fi or Ethernet.",
        defaultValue: true,
        advanced: true,
      },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "stream_reuse_last_link_enabled", label: "Reuse the last working link", help: "For the same movie or episode, while it is still fresh.", defaultValue: false, advanced: true },
      {
        kind: "choice",
        feature: PLAYER_FEATURE,
        key: "stream_reuse_last_link_cache_hours",
        label: "Keep a link for",
        options: [1, 2, 3, 6, 12, 24, 48, 72, 168].map((value) => ({
          value,
          label: value === 168 ? "1 week" : value >= 48 ? `${value / 24} days` : `${value} h`,
        })),
        defaultValue: 24,
        advanced: true,
        visibleWhen: { key: "stream_reuse_last_link_enabled", oneOf: [true] },
      },
    ],
  },
  {
    id: "next-episode",
    section: "playback",
    title: "Next episode",
    fields: [
      { kind: "toggle", feature: PLAYER_FEATURE, key: "stream_auto_play_next_episode_enabled", label: "Play the next episode automatically", defaultValue: true },
      { kind: "number", feature: PLAYER_FEATURE, key: "auto_play_countdown_seconds", label: "Countdown", unit: "s", min: 3, max: 15, step: 1, defaultValue: 8, visibleWhen: { key: "stream_auto_play_next_episode_enabled", oneOf: [true] } },
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "next_episode_threshold_mode",
        label: "Offer the next episode",
        options: [
          { value: "MINUTES_BEFORE_END", label: "A set time before the end" },
          { value: "PERCENTAGE", label: "At a percentage watched" },
        ],
        defaultValue: "MINUTES_BEFORE_END",
        advanced: true,
      },
      {
        kind: "number",
        feature: PLAYER_FEATURE,
        key: "next_episode_threshold_minutes_before_end_v2",
        label: "Minutes before the end",
        min: 0,
        max: 3.5,
        step: 0.5,
        numberType: "float",
        defaultValue: 2,
        advanced: true,
        visibleWhen: { key: "next_episode_threshold_mode", oneOf: ["MINUTES_BEFORE_END"] },
      },
      {
        kind: "number",
        feature: PLAYER_FEATURE,
        key: "next_episode_threshold_percent_v2",
        label: "Percentage watched",
        unit: "%",
        min: 97,
        max: 100,
        step: 0.5,
        numberType: "float",
        defaultValue: 99,
        advanced: true,
        visibleWhen: { key: "next_episode_threshold_mode", oneOf: ["PERCENTAGE"] },
      },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "stream_auto_play_prefer_bingegroup_next_episode", label: "Prefer the same release", help: "Try the same addon and quality group as the last episode first.", defaultValue: true, advanced: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "stream_auto_play_next_episode_fallback_enabled", label: "Fall back to the first stream", help: "When the same release isn't found. Off shows the stream picker instead.", defaultValue: true, advanced: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "stream_auto_play_reuse_binge_group", label: "Remember the release between sessions", help: "From Continue Watching, detail pages and so on.", defaultValue: false, advanced: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "preload_next_episode_sources", label: "Find the next episode's streams early", defaultValue: false, advanced: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "still_watching_enabled", label: "Ask \"Are you still watching?\"", defaultValue: true },
      { kind: "number", feature: PLAYER_FEATURE, key: "still_watching_episode_threshold", label: "Ask after this many episodes", min: 2, max: 6, step: 1, defaultValue: 3, visibleWhen: { key: "still_watching_enabled", oneOf: [true] } },
    ],
  },
  {
    id: "player",
    section: "playback",
    title: "Player",
    fields: [
      { kind: "choice", feature: PLAYER_FEATURE, key: "seek_back_seconds", label: "Skip back", options: seconds([5, 10, 15, 20, 30, 45, 60]), defaultValue: 10 },
      { kind: "choice", feature: PLAYER_FEATURE, key: "seek_forward_seconds", label: "Skip forward", options: seconds([5, 10, 15, 20, 30, 45, 60, 90, 120]), defaultValue: 30 },
      { kind: "choice", feature: PLAYER_FEATURE, key: "controls_auto_hide_seconds", label: "Hide controls after", options: seconds([2, 3, 4, 5, 6, 8, 10, 15, 20, 30]), defaultValue: 4 },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "osd_clock_enabled", label: "Clock on the controls", defaultValue: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "loading_overlay_enabled", label: "Loading screen", help: "Until the first frame appears.", defaultValue: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "show_player_loading_status", label: "Show loading steps", defaultValue: true, advanced: true, visibleWhen: { key: "loading_overlay_enabled", oneOf: [true] } },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "pause_overlay_enabled", label: "Details while paused", help: "After 5 seconds paused.", defaultValue: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "parental_guide_enabled", label: "Content warnings", help: "Shown as playback starts. Sends each title's IMDb ID to api.tiffara.com.", defaultValue: false },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "skip_silence", label: "Skip silence", defaultValue: false, advanced: true },
    ],
  },
  {
    id: "skip",
    section: "playback",
    title: "Skipping",
    fields: [
      { kind: "toggle", feature: PLAYER_FEATURE, key: "skip_intro_enabled", label: "Skip intro button", help: "Finds intros and recaps with introdb.app and community markers.", defaultValue: true },
      {
        kind: "multi",
        feature: PLAYER_FEATURE,
        key: "auto_skip_segment_types",
        label: "Skip automatically",
        options: [
          { value: "intro", label: "Intro / opening" },
          { value: "recap", label: "Recap" },
          { value: "outro", label: "Outro / ending" },
          { value: "movie-credits", label: "Movie end credits" },
        ],
        defaultValue: [],
        visibleWhen: { key: "skip_intro_enabled", oneOf: [true] },
      },
    ],
  },
  {
    id: "end-of-title",
    section: "playback",
    title: "When a title ends",
    fields: [
      { kind: "toggle", feature: PLAYER_FEATURE, key: "post_play_recommendations_enabled", label: "Recommendations near the end", defaultValue: true },
      { kind: "number", feature: PLAYER_FEATURE, key: "post_play_movie_threshold_percent", label: "For movies, show them at", unit: "%", min: 80, max: 100, step: 1, defaultValue: 96, advanced: true, visibleWhen: { key: "post_play_recommendations_enabled", oneOf: [true] } },
    ],
  },
  {
    id: "languages",
    section: "playback",
    title: "Audio",
    fields: [
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "preferred_audio_language",
        label: "Audio language",
        options: [
          { value: "device", label: "Same as the TV" },
          { value: "original", label: "The title's original language" },
          { value: "default", label: "Whatever the file defaults to" },
          ...languageOptions,
        ],
        defaultValue: "device",
      },
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "secondary_preferred_audio_language",
        label: "Second choice",
        options: [{ value: "", label: "None" }, ...languageOptions],
        defaultValue: "",
        unsetValue: "",
        advanced: true,
      },
    ],
  },

  // ─── Subtitles ───
  {
    id: "subtitle-languages",
    section: "subtitles",
    title: "Languages",
    fields: [
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "subtitle_preferred_language",
        label: "Subtitle language",
        options: [
          { value: "device", label: "Same as the TV" },
          { value: "forced", label: "Forced subtitles only" },
          ...languageOptions,
        ],
        defaultValue: "device",
      },
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "subtitle_secondary_language",
        label: "Second choice",
        options: [{ value: "", label: "None" }, ...languageOptions],
        defaultValue: "",
        unsetValue: "",
      },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_use_forced_subtitles", label: "Use forced subtitles", help: "For foreign-language parts of a title.", defaultValue: false },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_show_only_preferred_languages", label: "List only these languages", defaultValue: false, advanced: true },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_strip_sdh", label: "Strip SDH", help: "Drops sound descriptions like [door slams].", defaultValue: false, advanced: true },
      {
        kind: "select",
        feature: PLAYER_FEATURE,
        key: "subtitle_organization_mode",
        label: "Group the subtitle list",
        options: [
          { value: "NONE", label: "Don't group" },
          { value: "BY_LANGUAGE", label: "By language" },
          { value: "BY_ADDON", label: "By addon" },
        ],
        defaultValue: "NONE",
        advanced: true,
      },
    ],
  },
  {
    id: "subtitle-style",
    section: "subtitles",
    title: "Style",
    fields: [
      {
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "subtitle_use_system_caption_style",
        label: "Use the TV's caption style",
        help: "Size, colours and font from the TV's caption settings when captions are on there. Position still follows the setting below.",
        defaultValue: false,
        advanced: true,
      },
      { kind: "number", feature: PLAYER_FEATURE, key: "subtitle_size", label: "Size", min: 50, max: 200, step: 5, unit: "%", defaultValue: 100 },
      {
        kind: "number",
        feature: PLAYER_FEATURE,
        key: "subtitle_vertical_offset",
        label: "Distance from the bottom",
        min: -20,
        max: 50,
        step: 1,
        defaultValue: 5,
      },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_bold", label: "Bold", defaultValue: false },
      { kind: "color", feature: PLAYER_FEATURE, key: "subtitle_text_color", label: "Text colour", defaultValue: ARGB_WHITE },
      {
        kind: "color",
        feature: PLAYER_FEATURE,
        key: "subtitle_background_color",
        label: "Background colour",
        defaultValue: ARGB_TRANSPARENT,
      },
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_outline_enabled", label: "Outline", defaultValue: true },
      { kind: "color", feature: PLAYER_FEATURE, key: "subtitle_outline_color", label: "Outline colour", defaultValue: ARGB_BLACK, visibleWhen: { key: "subtitle_outline_enabled", oneOf: [true] } },
      { kind: "number", feature: PLAYER_FEATURE, key: "subtitle_outline_width", label: "Outline width", min: 1, max: 5, step: 1, defaultValue: 2, visibleWhen: { key: "subtitle_outline_enabled", oneOf: [true] } },
    ],
  },
  {
    id: "subtitle-hdr",
    section: "subtitles",
    title: "HDR and Dolby Vision",
    fields: [
      { kind: "toggle", feature: PLAYER_FEATURE, key: "subtitle_hdr_style_enabled", label: "A separate style for HDR video", help: "Bright white text can be glaring on HDR.", defaultValue: false },
      { kind: "color", feature: PLAYER_FEATURE, key: "subtitle_hdr_text_color", label: "HDR text colour", defaultValue: ARGB_WHITE, visibleWhen: { key: "subtitle_hdr_style_enabled", oneOf: [true] } },
      { kind: "number", feature: PLAYER_FEATURE, key: "subtitle_hdr_brightness", label: "HDR brightness", unit: "%", min: 0, max: 100, step: 5, defaultValue: 40, visibleWhen: { key: "subtitle_hdr_style_enabled", oneOf: [true] } },
    ],
  },

  // ─── Streams ───
  {
    id: "stream-list",
    section: "streams",
    title: "Stream list",
    fields: [
      { kind: "toggle", feature: STREAM_BADGE_FEATURE, key: "show_file_size_badges", label: "File size badges", defaultValue: true },
      { kind: "toggle", feature: STREAM_BADGE_FEATURE, key: "show_addon_logo", label: "Addon logo and name", defaultValue: true },
      {
        kind: "select",
        feature: STREAM_BADGE_FEATURE,
        key: "stream_badge_placement",
        label: "Badges",
        options: [
          { value: "BOTTOM", label: "Below the release name" },
          { value: "TOP", label: "Above the release name" },
        ],
        defaultValue: "BOTTOM",
      },
    ],
  },

  // ─── Content and metadata ───
  {
    id: "tmdb",
    section: "content",
    title: "TMDB enrichment",
    help: "Fills in artwork and details from TMDB where an addon's are thin. Uses the TMDB key on the TV.",
    fields: [
      { kind: "toggle", feature: TMDB_FEATURE, key: "tmdb_enabled", label: "Use TMDB", defaultValue: false },
      { kind: "select", feature: TMDB_FEATURE, key: "tmdb_language", label: "Language", options: tmdbLanguageOptions, defaultValue: "en", visibleWhen: { key: "tmdb_enabled", oneOf: [true] } },
      { kind: "toggle", feature: TMDB_FEATURE, key: "tmdb_modern_home_enabled", label: "On Home too", defaultValue: false, visibleWhen: { key: "tmdb_enabled", oneOf: [true] } },
      { kind: "toggle", feature: TMDB_FEATURE, key: "tmdb_enrich_continue_watching", label: "In Continue Watching", defaultValue: true, visibleWhen: { key: "tmdb_enabled", oneOf: [true] } },
      ...(
        [
          ["tmdb_use_artwork", "Artwork"],
          ["tmdb_use_basic_info", "Title and overview"],
          ["tmdb_use_details", "Details"],
          ["tmdb_use_credits", "Cast and crew"],
          ["tmdb_use_productions", "Production companies"],
          ["tmdb_use_networks", "Networks"],
          ["tmdb_use_episodes", "Episodes"],
          ["tmdb_use_trailers", "Trailers"],
          ["tmdb_use_more_like_this", "More like this"],
          ["tmdb_use_collections", "Collections"],
        ] as const
      ).map(
        ([key, label]): TvSettingField => ({
          kind: "toggle",
          feature: TMDB_FEATURE,
          key,
          label,
          defaultValue: true,
          advanced: true,
          visibleWhen: { key: "tmdb_enabled", oneOf: [true] },
        })
      ),
    ],
  },
  {
    id: "discovery",
    section: "content",
    title: "Continue Watching and discovery",
    fields: [
      {
        kind: "choice",
        feature: TRAKT_FEATURE,
        key: "continue_watching_days_cap",
        label: "Continue Watching goes back",
        options: [
          { value: 14, label: "2 weeks" },
          { value: 30, label: "30 days" },
          { value: 60, label: "60 days" },
          { value: 90, label: "90 days" },
          { value: 180, label: "6 months" },
          { value: 365, label: "1 year" },
          { value: 0, label: "Forever" },
        ],
        defaultValue: 60,
      },
      {
        kind: "select",
        feature: TRAKT_FEATURE,
        key: "more_like_this_source",
        label: "\"More like this\" from",
        help: "Trakt and Simkl need that account connected on the TV.",
        options: [
          { value: "TRAKT", label: "Trakt" },
          { value: "TMDB", label: "TMDB" },
          { value: "SIMKL", label: "Simkl" },
        ],
        defaultValue: "TRAKT",
        advanced: true,
      },
      { kind: "toggle", feature: TRAKT_FEATURE, key: "show_meta_comments", label: "Trakt comments on detail pages", defaultValue: true, advanced: true },
    ],
  },
];

/** Every field, in page order. */
export const TV_SETTING_FIELDS: TvSettingField[] = TV_SETTING_GROUPS.flatMap((group) => group.fields);

const PREFERENCE_TYPE: Record<Exclude<TvSettingField["kind"], "number">, TvPreferenceType> = {
  select: "string",
  choice: "int",
  toggle: "boolean",
  color: "int",
  multi: "string_set",
  text: "string",
  palette: "string",
};

const preferenceType = (field: TvSettingField): TvPreferenceType =>
  field.kind === "number" ? (field.numberType ?? "int") : PREFERENCE_TYPE[field.kind];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

/** The stored value of one key, or undefined when the document doesn't set it. */
export const readTvValue = (document: TvSettingsDocument, feature: string, key: string): unknown => {
  const entry = document.features?.[feature]?.[key];
  return isRecord(entry) ? entry.value : undefined;
};

/** A field's value as its form shows it: the stored one when it has the right type, else the default. */
export const readField = <F extends TvSettingField>(document: TvSettingsDocument, field: F): F["defaultValue"] => {
  const value = readTvValue(document, field.feature, field.key);
  if (field.kind === "multi") {
    return (Array.isArray(value) && value.every((entry) => typeof entry === "string") ? value : field.defaultValue) as F["defaultValue"];
  }
  return typeof value === typeof field.defaultValue ? (value as F["defaultValue"]) : field.defaultValue;
};

/**
 * Whether a field's control is shown: its `visibleWhen` key holds one of the
 * values that make it mean something.
 */
export const isFieldVisible = (document: TvSettingsDocument, field: TvSettingField): boolean => {
  if (!field.visibleWhen) return true;
  const { key, oneOf } = field.visibleWhen;
  const parent = TV_SETTING_FIELDS.find((f) => f.feature === field.feature && f.key === key);
  const value = parent ? readField(document, parent) : readTvValue(document, field.feature, key);
  return oneOf.includes(value as string | boolean | number);
};

/**
 * A copy of `document` with one key set, everything else — other keys, other
 * features, top-level fields — carried over as it was.
 */
export const withTvValue = (
  document: TvSettingsDocument,
  feature: string,
  key: string,
  preference: TvPreference
): TvSettingsDocument => {
  const features = isRecord(document.features) ? document.features : {};
  const current = isRecord(features[feature]) ? features[feature] : {};
  return {
    ...document,
    version: typeof document.version === "number" ? document.version : TV_SETTINGS_DOCUMENT_VERSION,
    features: { ...features, [feature]: { ...current, [key]: preference } },
  };
};

/** A copy of `document` without one key, which the TV reads as its default (or "none"). */
export const withoutTvValue = (document: TvSettingsDocument, feature: string, key: string): TvSettingsDocument => {
  const features = isRecord(document.features) ? document.features : {};
  const current = isRecord(features[feature]) ? features[feature] : null;
  if (!current || !(key in current)) return document;
  const rest = { ...current };
  delete rest[key];
  return { ...document, features: { ...features, [feature]: rest } };
};

/**
 * `document` with a field set, and with what the TV changes alongside it on
 * its own screen: a linked key kept in step, dependent toggles turned off, a
 * "none" choice stored as no key at all.
 */
export const withField = (
  document: TvSettingsDocument,
  field: TvSettingField,
  value: string | boolean | number | string[]
): TvSettingsDocument => {
  if (field.kind === "select" && field.unsetValue !== undefined && value === field.unsetValue) {
    return withoutTvValue(document, field.feature, field.key);
  }
  if (field.kind === "multi" && Array.isArray(value)) {
    // The TV writes sets sorted; matching that keeps a save from looking like a change.
    value = [...new Set(value)].sort();
  }
  let next = withTvValue(document, field.feature, field.key, { type: preferenceType(field), value } as TvPreference);
  if (field.kind === "choice" && field.linked && typeof value === "number") {
    next = withTvValue(next, field.feature, field.linked.key, { type: "int", value: field.linked.from(value) });
  }
  if (field.kind === "toggle" && field.offAlsoClears && value === false) {
    for (const key of field.offAlsoClears) {
      next = withTvValue(next, field.feature, key, { type: "boolean", value: false });
    }
  }
  return next;
};

// ─── Custom theme colours ───

const HEX_COLOR = /^#?[0-9a-f]{6}$/i;

/** The custom theme's colours as `#rrggbb`, the TV's default when the stored value doesn't parse, as `decode` does. */
export const readPalette = (encoded: string, count = 3): string[] => {
  const colors = encoded.split(",").map((part) => part.trim());
  if (colors.length !== count || !colors.every((c) => HEX_COLOR.test(c))) {
    return DEFAULT_CUSTOM_THEME_COLORS.split(",").map((c) => c.toLowerCase());
  }
  return colors.map((c) => `#${c.replace(/^#/, "").toLowerCase()}`);
};

/** Colours in the TV's encoding: `#RRGGBB,#RRGGBB,#RRGGBB`. */
export const encodePalette = (colors: string[]): string =>
  colors.map((c) => `#${c.replace(/^#/, "").toUpperCase()}`).join(",");

// ─── Stream ranking rules ───
//
// Stored as a JSON string under `player_settings.stream_ranking_rules`, the
// TV's `StreamRankingRules` serialized with defaults left out. The TV reads it
// with `ignoreUnknownKeys`, so only the fields below are edited here and the
// rest of the object is carried through as it was.

export const STREAM_RESOLUTIONS: Option[] = [
  { value: "UHD_2160", label: "4K" },
  { value: "QHD_1440", label: "1440p" },
  { value: "FHD_1080", label: "1080p" },
  { value: "HD_720", label: "720p" },
  { value: "SD_576", label: "576p" },
  { value: "SD_480", label: "480p" },
];

export const VIDEO_CODECS: Option[] = [
  { value: "AV1", label: "AV1" },
  { value: "HEVC", label: "HEVC" },
  { value: "AVC", label: "H.264" },
  { value: "VP9", label: "VP9" },
];

/** `StreamLanguage` in the app: the languages a stream's name or flags can be matched against. */
export const STREAM_LANGUAGES: Option[] = [
  { value: "ENGLISH", label: "English" },
  { value: "HINDI", label: "Hindi" },
  { value: "ITALIAN", label: "Italian" },
  { value: "SPANISH", label: "Spanish" },
  { value: "LATINO", label: "Latino" },
  { value: "FRENCH", label: "French" },
  { value: "GERMAN", label: "German" },
  { value: "PORTUGUESE", label: "Portuguese" },
  { value: "POLISH", label: "Polish" },
  { value: "CZECH", label: "Czech" },
  { value: "RUSSIAN", label: "Russian" },
  { value: "JAPANESE", label: "Japanese" },
  { value: "KOREAN", label: "Korean" },
  { value: "CHINESE", label: "Chinese" },
  { value: "HEBREW", label: "Hebrew" },
  { value: "MULTI", label: "Multi audio" },
];

/** The ranking fields the website edits, with the TV's defaults. */
export type RankingRules = {
  preferredResolution: string;
  maxResolution: string;
  preferredCodecs: string[];
  preferHdr: boolean;
  excludeDolbyVision: boolean;
  excludeCamAndTelesync: boolean;
  preferCachedDebrid: boolean;
  maxSizeGb: number | null;
  preferredLanguages: string[];
  preferredKeywords: string[];
  excludedKeywords: string[];
  [key: string]: unknown;
};

export const DEFAULT_RANKING_RULES: RankingRules = {
  preferredResolution: "UHD_2160",
  maxResolution: "UHD_2160",
  preferredCodecs: [],
  preferHdr: true,
  excludeDolbyVision: false,
  excludeCamAndTelesync: true,
  preferCachedDebrid: true,
  maxSizeGb: null,
  preferredLanguages: [],
  preferredKeywords: [],
  excludedKeywords: [],
};

/** The rules the document holds, over the TV's defaults; unknown fields are kept. */
export const readRankingRules = (document: TvSettingsDocument): RankingRules => {
  const raw = readTvValue(document, PLAYER_FEATURE, RANKING_RULES_KEY);
  if (typeof raw !== "string") return { ...DEFAULT_RANKING_RULES };
  try {
    const parsed: unknown = JSON.parse(raw);
    return isRecord(parsed) ? { ...DEFAULT_RANKING_RULES, ...parsed } : { ...DEFAULT_RANKING_RULES };
  } catch {
    return { ...DEFAULT_RANKING_RULES };
  }
};

export const withRankingRules = (document: TvSettingsDocument, rules: RankingRules): TvSettingsDocument => {
  // `maxSizeGb: null` is the TV's default and its "no cap"; leaving it out says
  // the same thing in the form the TV writes.
  const { maxSizeGb, ...rest } = rules;
  const stored = maxSizeGb === null ? rest : { ...rest, maxSizeGb };
  return withTvValue(document, PLAYER_FEATURE, RANKING_RULES_KEY, { type: "string", value: JSON.stringify(stored) });
};

// ─── MDBList ratings ───
//
// Which MDBList ratings a detail page shows, and in what order: the TV's
// `MDBListSettingsDataStore`, synced under `mdblist_settings`. The API key in
// that DataStore is never in this document (the TV leaves credentials out), so
// the website keeps its own; these are only the display choices, shared by
// both.

export const MDBLIST_FEATURE = "mdblist_settings";

/** The TV's `MDBListSettings.DEFAULT_RATING_ORDER`. */
export const MDBLIST_RATING_SOURCES = [
  "imdb",
  "trakt",
  "tmdb",
  "tomatoes",
  "audience",
  "letterboxd",
  "metacritic",
  "mal",
] as const;

export type MdbListRatingSource = (typeof MDBLIST_RATING_SOURCES)[number];

export const MDBLIST_RATING_LABELS: Record<MdbListRatingSource, string> = {
  imdb: "IMDb",
  trakt: "Trakt",
  tmdb: "TMDB",
  tomatoes: "Rotten Tomatoes",
  audience: "Audience",
  letterboxd: "Letterboxd",
  metacritic: "Metacritic",
  mal: "MyAnimeList",
};

export type MdbListRatingSettings = {
  /** `mdblist_enabled`: off until the profile turns it on, as on the TV. */
  enabled: boolean;
  shown: Record<MdbListRatingSource, boolean>;
  order: MdbListRatingSource[];
};

const MDBLIST_ENABLED_KEY = "mdblist_enabled";
const MDBLIST_ORDER_KEY = "mdblist_rating_order";
const mdbListShowKey = (source: MdbListRatingSource) => `mdblist_show_${source}`;

const isRatingSource = (value: string): value is MdbListRatingSource =>
  (MDBLIST_RATING_SOURCES as readonly string[]).includes(value);

export const readMdbListRatingSettings = (document: TvSettingsDocument): MdbListRatingSettings => {
  const enabled = readTvValue(document, MDBLIST_FEATURE, MDBLIST_ENABLED_KEY);
  const shown = {} as Record<MdbListRatingSource, boolean>;
  for (const source of MDBLIST_RATING_SOURCES) {
    const value = readTvValue(document, MDBLIST_FEATURE, mdbListShowKey(source));
    shown[source] = typeof value === "boolean" ? value : true;
  }
  const stored = readTvValue(document, MDBLIST_FEATURE, MDBLIST_ORDER_KEY);
  const listed = typeof stored === "string" ? stored.split(",").map((entry) => entry.trim()) : [];
  // A source this build doesn't know (a newer TV's) is dropped, and one the
  // stored order leaves out goes on the end rather than vanishing.
  const order = [...new Set(listed.filter(isRatingSource))];
  for (const source of MDBLIST_RATING_SOURCES) if (!order.includes(source)) order.push(source);
  return { enabled: enabled === true, shown, order };
};

export const withMdbListRatingSettings = (
  document: TvSettingsDocument,
  settings: MdbListRatingSettings
): TvSettingsDocument => {
  let next = withTvValue(document, MDBLIST_FEATURE, MDBLIST_ENABLED_KEY, { type: "boolean", value: settings.enabled });
  for (const source of MDBLIST_RATING_SOURCES) {
    next = withTvValue(next, MDBLIST_FEATURE, mdbListShowKey(source), { type: "boolean", value: settings.shown[source] });
  }
  return withTvValue(next, MDBLIST_FEATURE, MDBLIST_ORDER_KEY, { type: "string", value: settings.order.join(",") });
};

/** The sources a detail page shows, in the order it shows them. */
export const visibleMdbListSources = (settings: MdbListRatingSettings): MdbListRatingSource[] =>
  settings.order.filter((source) => settings.shown[source]);

// ─── Colours ───

/** `#rrggbb` for an ARGB int, ignoring alpha. */
export const argbToHex = (argb: number): string => `#${((argb >>> 0) & 0xffffff).toString(16).padStart(6, "0")}`;

/** The alpha channel of an ARGB int, 0–255. */
export const argbAlpha = (argb: number): number => (argb >>> 24) & 0xff;

/** An ARGB int, signed as the TV stores it, from `#rrggbb` and an alpha 0–255. */
export const hexToArgb = (hex: string, alpha = 0xff): number => {
  const rgb = Number.parseInt(hex.replace(/^#/, ""), 16) & 0xffffff;
  return ((alpha & 0xff) << 24) | rgb;
};
