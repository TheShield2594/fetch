import type { Prisma } from "@prisma/client";

/**
 * A kids profile's rules, carried with the profile so every paired TV applies
 * the same ones. Mirrors the TV's `KidsRules` (its rule fields, not the ids of
 * the TV's own lists, which mean nothing elsewhere) plus the profile's
 * always-allowed titles, which "approved titles only" shows.
 *
 * Only the rules travel. The screen time used today is counted on each TV, so
 * a TV keeps enforcing its limit offline.
 *
 * Two writers (a TV and the website, or two TVs) settle by when the rules
 * changed: a write stamped older than what is stored is ignored, and the
 * answer carries what is stored, so the stale writer takes it.
 */
export type ApprovedTitle = {
  /** Every id the title is known by. Any addon's content ids, not only IMDb's. */
  ids: string[];
  type: string;
  name: string;
  poster: string | null;
  background: string | null;
};

export type KidsRules = {
  /** Episodes that autoplay in a row; null for no cap. */
  autoplayEpisodeCap: number | null;
  approvedTitlesOnly: boolean;
  /** Minutes of playback per day; null for no limit. */
  dailyMinutes: number | null;
  /** Minutes after midnight; both null for no bedtime. */
  bedtimeStartMinute: number | null;
  bedtimeEndMinute: number | null;
  /** At the limit, stop right away instead of letting the title finish. */
  hardStop: boolean;
  approvedTitles: ApprovedTitle[];
};

/** What the TV starts a kids profile with (`KidsRules.Default`). */
export const DEFAULT_KIDS_RULES: KidsRules = {
  autoplayEpisodeCap: 3,
  approvedTitlesOnly: false,
  dailyMinutes: null,
  bedtimeStartMinute: null,
  bedtimeEndMinute: null,
  hardStop: false,
  approvedTitles: [],
};

export const MAX_AUTOPLAY_EPISODE_CAP = 50;
export const MINUTES_PER_DAY = 24 * 60;
export const MAX_APPROVED_TITLES = 1000;
const MAX_IDS_PER_TITLE = 20;
const MAX_ID_LENGTH = 200;
const MAX_TYPE_LENGTH = 32;
const MAX_NAME_LENGTH = 500;
const MAX_URL_LENGTH = 2048;

/** The rule fields a write may name; omitted ones keep what is stored. */
export type KidsRulesWrite = {
  patch: Partial<KidsRules>;
  /** When the writer changed them; null for "now" (the website's own editor). */
  changedAt: Date | null;
};

type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const nullableInt = (value: unknown, min: number, max: number, key: string): Parsed<number | null> => {
  if (value === null) return { ok: true, value: null };
  if (typeof value === "number" && Number.isInteger(value) && value >= min && value <= max) {
    return { ok: true, value };
  }
  return { ok: false, error: `${key} must be null or a whole number from ${min} to ${max}` };
};

const optionalUrl = (value: unknown, key: string): Parsed<string | null> => {
  if (value === undefined || value === null || value === "") return { ok: true, value: null };
  if (typeof value !== "string" || value.length > MAX_URL_LENGTH || !/^https?:\/\//i.test(value)) {
    return { ok: false, error: `${key} must be an http(s) URL` };
  }
  return { ok: true, value };
};

const hasControlCharacter = (value: string): boolean =>
  [...value].some((char) => {
    const code = char.charCodeAt(0);
    return code < 0x20 || code === 0x7f;
  });

const parseApprovedTitle =(raw: unknown, index: number): Parsed<ApprovedTitle> => {
  const at = `approvedTitles[${index}]`;
  if (!isRecord(raw)) return { ok: false, error: `${at} must be an object` };
  const ids = raw.ids;
  if (!Array.isArray(ids) || ids.length === 0 || ids.length > MAX_IDS_PER_TITLE) {
    return { ok: false, error: `${at}.ids must list 1 to ${MAX_IDS_PER_TITLE} ids` };
  }
  const cleanIds: string[] = [];
  for (const id of ids) {
    // Content ids from any addon: "tt0111161", "tmdb:603", "kitsu:1". Nothing
    // here assumes IMDb's shape, only that an id is short, printable text.
    if (typeof id !== "string" || !id.trim() || id.length > MAX_ID_LENGTH || hasControlCharacter(id)) {
      return { ok: false, error: `${at}.ids must be non-empty strings of at most ${MAX_ID_LENGTH} characters` };
    }
    if (!cleanIds.includes(id.trim())) cleanIds.push(id.trim());
  }
  if (typeof raw.type !== "string" || !raw.type.trim() || raw.type.length > MAX_TYPE_LENGTH) {
    return { ok: false, error: `${at}.type must be a short non-empty string` };
  }
  if (typeof raw.name !== "string" || raw.name.length > MAX_NAME_LENGTH) {
    return { ok: false, error: `${at}.name must be a string of at most ${MAX_NAME_LENGTH} characters` };
  }
  const poster = optionalUrl(raw.poster, `${at}.poster`);
  if (!poster.ok) return poster;
  const background = optionalUrl(raw.background, `${at}.background`);
  if (!background.ok) return background;
  return {
    ok: true,
    value: { ids: cleanIds, type: raw.type.trim(), name: raw.name, poster: poster.value, background: background.value },
  };
};

/**
 * Reads `parental.kidsRules` from a create or update body. `undefined` when
 * the body doesn't carry them (leave as-is). Each rule field is optional and
 * replaces the stored one; `approvedTitles`, when present, replaces the whole
 * list. `updatedAt` is when the writer changed them.
 */
export const parseKidsRulesWrite = (raw: unknown): Parsed<KidsRulesWrite | undefined> => {
  if (raw === undefined) return { ok: true, value: undefined };
  if (!isRecord(raw)) return { ok: false, error: "kidsRules must be an object" };
  const patch: Partial<KidsRules> = {};

  if (raw.autoplayEpisodeCap !== undefined) {
    const cap = nullableInt(raw.autoplayEpisodeCap, 1, MAX_AUTOPLAY_EPISODE_CAP, "autoplayEpisodeCap");
    if (!cap.ok) return cap;
    patch.autoplayEpisodeCap = cap.value;
  }
  if (raw.dailyMinutes !== undefined) {
    const daily = nullableInt(raw.dailyMinutes, 1, MINUTES_PER_DAY, "dailyMinutes");
    if (!daily.ok) return daily;
    patch.dailyMinutes = daily.value;
  }
  for (const key of ["bedtimeStartMinute", "bedtimeEndMinute"] as const) {
    if (raw[key] === undefined) continue;
    const minute = nullableInt(raw[key], 0, MINUTES_PER_DAY - 1, key);
    if (!minute.ok) return minute;
    patch[key] = minute.value;
  }
  for (const key of ["approvedTitlesOnly", "hardStop"] as const) {
    if (raw[key] === undefined) continue;
    if (typeof raw[key] !== "boolean") return { ok: false, error: `${key} must be a boolean` };
    patch[key] = raw[key];
  }
  if (raw.approvedTitles !== undefined) {
    if (!Array.isArray(raw.approvedTitles) || raw.approvedTitles.length > MAX_APPROVED_TITLES) {
      return { ok: false, error: `approvedTitles must be a list of at most ${MAX_APPROVED_TITLES} titles` };
    }
    const titles: ApprovedTitle[] = [];
    for (const [index, title] of raw.approvedTitles.entries()) {
      const parsed = parseApprovedTitle(title, index);
      if (!parsed.ok) return parsed;
      titles.push(parsed.value);
    }
    patch.approvedTitles = titles;
  }

  let changedAt: Date | null = null;
  if (raw.updatedAt !== undefined && raw.updatedAt !== null) {
    const parsed = typeof raw.updatedAt === "string" ? new Date(raw.updatedAt) : null;
    if (!parsed || Number.isNaN(parsed.getTime())) {
      return { ok: false, error: "kidsRules.updatedAt must be an ISO 8601 date-time" };
    }
    changedAt = parsed;
  }
  return { ok: true, value: { patch, changedAt } };
};

/** The stored rules, read defensively: a field that isn't valid falls back to the default. */
export const readStoredKidsRules = (stored: Prisma.JsonValue | null | undefined): KidsRules | null => {
  if (!isRecord(stored)) return null;
  const parsed = parseKidsRulesWrite(stored);
  if (parsed.ok && parsed.value) return { ...DEFAULT_KIDS_RULES, ...parsed.value.patch };
  // Something unreadable was stored; take field by field what still reads.
  const rules: KidsRules = { ...DEFAULT_KIDS_RULES };
  for (const [key, value] of Object.entries(stored)) {
    const one = parseKidsRulesWrite({ [key]: value });
    if (one.ok && one.value) Object.assign(rules, one.value.patch);
  }
  return rules;
};

/**
 * What a write does to the stored rules: the columns to save, `null` when the
 * write is older than what is stored (it loses, nothing changes), or an error
 * when the result is not a valid set of rules.
 */
export const resolveKidsRulesWrite = (
  stored: { kidsRules: Prisma.JsonValue | null; kidsRulesUpdatedAt: Date | null } | null,
  write: KidsRulesWrite,
  now: Date = new Date()
): { ok: true; data: { kidsRules: KidsRules; kidsRulesUpdatedAt: Date } | null } | { ok: false; error: string } => {
  // Never later than now: a TV whose clock runs fast can't stamp its rules
  // into the future and so outlive every later change.
  const changedAt = write.changedAt == null || write.changedAt > now ? now : write.changedAt;
  const storedAt = stored?.kidsRulesUpdatedAt ?? null;
  if (storedAt && changedAt.getTime() <= storedAt.getTime()) return { ok: true, data: null };

  const rules: KidsRules = { ...(readStoredKidsRules(stored?.kidsRules) ?? DEFAULT_KIDS_RULES), ...write.patch };
  if ((rules.bedtimeStartMinute === null) !== (rules.bedtimeEndMinute === null)) {
    return { ok: false, error: "bedtimeStartMinute and bedtimeEndMinute must both be set or both be null" };
  }
  return { ok: true, data: { kidsRules: rules, kidsRulesUpdatedAt: changedAt } };
};

/** The rules as the API answers with them, or null when none were ever set. */
export const toKidsRulesView = (stored: Prisma.JsonValue | null, updatedAt: Date | null) => {
  const rules = readStoredKidsRules(stored);
  if (!rules) return null;
  return { ...rules, updatedAt: updatedAt?.toISOString() ?? null };
};

export type KidsRulesView = NonNullable<ReturnType<typeof toKidsRulesView>>;
