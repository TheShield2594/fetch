import type { Prisma } from "@prisma/client";

/**
 * The profile as every route answers with it. Shared by `/profiles` and the
 * device-facing `/devices/me/profiles`, so the website and a paired TV can't
 * drift apart on what a profile carries.
 */
export const PROFILE_VIEW_SELECT = {
  id: true,
  name: true,
  pinHash: true,
  avatarColorHex: true,
  avatarId: true,
  profileBackgroundId: true,
  usesPrimaryAddons: true,
  usesPrimaryPlugins: true,
  isKidsProfile: true,
  maxRating: true,
  allowUnrated: true,
  createdAt: true,
  updatedAt: true,
} satisfies Prisma.ProfileSelect;

export type ProfileRow = Prisma.ProfileGetPayload<{ select: typeof PROFILE_VIEW_SELECT }>;

export const toProfileView = (profile: ProfileRow) => ({
  id: profile.id,
  name: profile.name,
  hasPin: profile.pinHash != null,
  avatarColorHex: profile.avatarColorHex,
  avatarId: profile.avatarId,
  profileBackgroundId: profile.profileBackgroundId,
  usesPrimaryAddons: profile.usesPrimaryAddons,
  usesPrimaryPlugins: profile.usesPrimaryPlugins,
  parental: {
    isKidsProfile: profile.isKidsProfile,
    maxRating: profile.maxRating,
    allowUnrated: profile.allowUnrated,
  },
  createdAt: profile.createdAt.toISOString(),
  updatedAt: profile.updatedAt.toISOString(),
});

export type ProfileView = ReturnType<typeof toProfileView>;

/**
 * The TV's age ladder (`CertificationLevel`), loosest last. Movie and TV
 * ratings share it, so one limit covers both.
 */
export const RATING_LEVELS = ["ALL_AGES", "OLDER_KIDS", "PARENTAL_GUIDANCE", "TEENS", "MATURE", "ADULTS_ONLY"] as const;

/** What turning on "kids profile" applies, matching the TV's `ProfileRestrictions.KidsPreset`. */
export const KIDS_PRESET = { maxRating: "PARENTAL_GUIDANCE", allowUnrated: false } as const;

const COLOR_PATTERN = /^#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?$/;
// Bundled avatars are `fetch:<key>`; a server catalog's ids are opaque but short.
const ASSET_ID_PATTERN = /^[A-Za-z0-9:_.-]{1,100}$/;

export type ProfileAttributeUpdate = Partial<
  Pick<
    ProfileRow,
    | "avatarColorHex"
    | "avatarId"
    | "profileBackgroundId"
    | "usesPrimaryAddons"
    | "usesPrimaryPlugins"
    | "isKidsProfile"
    | "maxRating"
    | "allowUnrated"
  >
>;

/**
 * Reads the appearance, sharing and parental fields out of a create or update
 * body. Omitted fields are left out of the result (leave as-is); `null` clears
 * the nullable ones. Returns the first problem as a message instead.
 *
 * `parental` may arrive nested (`{ parental: { maxRating } }`, the shape the
 * API answers with) or flat; nested wins.
 */
export const parseProfileAttributes = (
  rawBody: unknown
): { ok: true; data: ProfileAttributeUpdate } | { ok: false; error: string } => {
  const body = (rawBody ?? {}) as Record<string, unknown>;
  const parental =
    body.parental && typeof body.parental === "object" ? (body.parental as Record<string, unknown>) : body;
  const data: ProfileAttributeUpdate = {};

  const nullableString = (
    key: "avatarColorHex" | "avatarId" | "profileBackgroundId",
    value: unknown,
    pattern: RegExp,
    message: string
  ): string | null => {
    if (value === undefined) return null;
    if (value === null || value === "") {
      data[key] = null;
      return null;
    }
    if (typeof value !== "string" || !pattern.test(value)) return message;
    data[key] = value;
    return null;
  };

  const error =
    nullableString("avatarColorHex", body.avatarColorHex, COLOR_PATTERN, "avatarColorHex must be a colour like #1E88E5") ??
    nullableString("avatarId", body.avatarId, ASSET_ID_PATTERN, "avatarId is not a valid avatar id") ??
    nullableString(
      "profileBackgroundId",
      body.profileBackgroundId,
      ASSET_ID_PATTERN,
      "profileBackgroundId is not a valid background id"
    );
  if (error) return { ok: false, error };

  for (const key of ["usesPrimaryAddons", "usesPrimaryPlugins"] as const) {
    const value = body[key];
    if (value === undefined) continue;
    if (typeof value !== "boolean") return { ok: false, error: `${key} must be a boolean` };
    data[key] = value;
  }

  for (const key of ["isKidsProfile", "allowUnrated"] as const) {
    const value = parental[key];
    if (value === undefined) continue;
    if (typeof value !== "boolean") return { ok: false, error: `${key} must be a boolean` };
    data[key] = value;
  }

  if (parental.maxRating !== undefined) {
    if (parental.maxRating === null) {
      data.maxRating = null;
    } else if (typeof parental.maxRating === "string" && (RATING_LEVELS as readonly string[]).includes(parental.maxRating)) {
      data.maxRating = parental.maxRating;
    } else {
      return { ok: false, error: `maxRating must be null or one of: ${RATING_LEVELS.join(", ")}` };
    }
  }

  // Switching kids mode on applies the preset unless the same request says
  // otherwise, as the TV's own toggle does.
  if (data.isKidsProfile === true) {
    if (data.maxRating === undefined) data.maxRating = KIDS_PRESET.maxRating;
    if (data.allowUnrated === undefined) data.allowUnrated = KIDS_PRESET.allowUnrated;
  }

  return { ok: true, data };
};
