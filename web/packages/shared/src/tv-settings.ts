/**
 * The TV app's per-profile settings, as the website edits them.
 *
 * The API stores this document without reading it (`PUT /profiles/:id/
 * settings/tv`); the TV app owns its schema. This module is the website's half
 * of that contract: the shape the TV's `ProfileSettingsSyncService` writes, and
 * the handful of settings the website offers forms for — the same ones the TV's
 * phone hub (`core/server/PhoneHubServer.kt`) edits, under the same keys.
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

type Option = { value: string; label: string };

type FieldBase = { feature: string; key: string; label: string; help?: string };

export type TvSettingField =
  | (FieldBase & { kind: "select"; options: Option[]; defaultValue: string })
  | (FieldBase & { kind: "toggle"; defaultValue: boolean })
  | (FieldBase & { kind: "number"; min: number; max: number; step: number; unit?: string; defaultValue: number })
  /** An ARGB colour stored as a signed 32-bit int, as Compose's `Color.toArgb()` gives it. */
  | (FieldBase & { kind: "color"; defaultValue: number });

export type TvSettingGroup = { id: string; title: string; fields: TvSettingField[] };

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

export const TV_SETTING_GROUPS: TvSettingGroup[] = [
  {
    id: "experience",
    title: "Settings mode",
    fields: [
      {
        kind: "select",
        feature: EXPERIENCE_FEATURE,
        key: "mode",
        label: "Settings shown on the TV",
        help: "Essential keeps about 25 settings; Advanced shows all of them.",
        options: [
          { value: "ESSENTIAL", label: "Essential" },
          { value: "ADVANCED", label: "Advanced" },
        ],
        defaultValue: "ESSENTIAL",
      },
    ],
  },
  {
    id: "autoplay",
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
        kind: "toggle",
        feature: PLAYER_FEATURE,
        key: "stream_auto_play_next_episode_enabled",
        label: "Play the next episode automatically",
        defaultValue: false,
      },
    ],
  },
  {
    id: "languages",
    title: "Languages",
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
        key: "subtitle_preferred_language",
        label: "Subtitle language",
        options: [
          { value: "device", label: "Same as the TV" },
          { value: "forced", label: "Forced subtitles only" },
          ...languageOptions,
        ],
        defaultValue: "device",
      },
    ],
  },
  {
    id: "subtitle-style",
    title: "Subtitle style",
    fields: [
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
    ],
  },
];

const PREFERENCE_TYPE: Record<TvSettingField["kind"], TvPreferenceType> = {
  select: "string",
  toggle: "boolean",
  number: "int",
  color: "int",
};

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
  return typeof value === typeof field.defaultValue ? (value as F["defaultValue"]) : field.defaultValue;
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

export const withField = (
  document: TvSettingsDocument,
  field: TvSettingField,
  value: string | boolean | number
): TvSettingsDocument =>
  withTvValue(document, field.feature, field.key, { type: PREFERENCE_TYPE[field.kind], value } as TvPreference);

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
