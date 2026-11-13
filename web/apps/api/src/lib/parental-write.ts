import { Prisma } from "@prisma/client";
import { prisma } from "./prisma.js";
import { loosensParentalLimits, withoutLoosenedLimits, type ParentalLimits } from "./profile.js";
import { PROFILE_VIEW_SELECT, type ProfileRow } from "./profile-view.js";

const PARENTAL_FIELDS = ["isKidsProfile", "maxRating", "allowUnrated"] as const;
/** How often a write re-reads the limits after a concurrent change before giving up on them. */
const PARENTAL_WRITE_ATTEMPTS = 3;

const PARENTAL_SELECT = { isKidsProfile: true, maxRating: true, allowUnrated: true } as const;

const isRecordNotFound = (error: unknown): boolean =>
  error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2025";

export type GuardedProfileData = Partial<ParentalLimits> & Prisma.ProfileUpdateManyMutationInput;

export type GuardedProfileWrite =
  /**
   * `profile` is the row as written; it is absent (and nothing was written)
   * when nothing was left once the refused fields were taken out.
   */
  | { status: "ok"; profile?: ProfileRow; skipped: (keyof ParentalLimits)[] }
  | { status: "missing" }
  /** Strict mode: the change loosens the limits as they now stand, and the request has no authority to. */
  | { status: "forbidden" }
  /** Strict mode: the limits kept changing under the request, so nothing was written. */
  | { status: "conflict" };

/**
 * Writes `data` to a profile so that a parental field only lands if the
 * stored limits are still the ones it was checked against.
 *
 * Reading the limits, judging whether the write loosens them, and writing
 * are separate steps; a grown-up tightening the limits between them could
 * otherwise be undone by a value that was fine a moment earlier. So a write
 * that carries a parental field only matches the row while it still holds the
 * three values that were checked. On a conflict the limits are read and
 * checked again.
 *
 * - Lenient (the default; a backup import, a TV's profile push): a loosening
 *   without authority is left out field by field and the rest is written; if
 *   the limits keep changing, the parental fields are skipped and the rest is
 *   still written.
 * - Strict (`PATCH /profiles/:id`): a loosening without authority is
 *   `forbidden` and limits that keep changing are a `conflict`; nothing is
 *   written in either case.
 *
 * `initial` is the row the caller already checked, used for the first
 * attempt in place of a fresh read.
 */
export const writeProfileGuarded = async (
  profileId: string,
  data: GuardedProfileData,
  options: {
    checkAuthority: () => Promise<boolean>;
    strict?: boolean;
    initial?: ParentalLimits;
  }
): Promise<GuardedProfileWrite> => {
  const { strict = false } = options;
  let authority: boolean | undefined;
  const hasAuthority = async () => (authority ??= await options.checkAuthority());

  for (let attempt = 0; attempt < PARENTAL_WRITE_ATTEMPTS; attempt += 1) {
    const current =
      attempt === 0 && options.initial
        ? options.initial
        : await prisma.profile.findUnique({ where: { id: profileId }, select: PARENTAL_SELECT });
    if (!current) return { status: "missing" };

    let next = data;
    let skipped: (keyof ParentalLimits)[] = [];
    if (loosensParentalLimits(current, next) && !(await hasAuthority())) {
      if (strict) return { status: "forbidden" };
      const trimmed = withoutLoosenedLimits(current, next);
      next = trimmed.data;
      skipped = trimmed.dropped;
    }
    if (Object.keys(next).length === 0) return { status: "ok", skipped };

    const touchesLimits = PARENTAL_FIELDS.some((key) => next[key] !== undefined);
    if (!touchesLimits) {
      const profile = await prisma.profile.update({ where: { id: profileId }, data: next, select: PROFILE_VIEW_SELECT });
      return { status: "ok", profile, skipped };
    }
    try {
      const profile = await prisma.profile.update({
        where: {
          id: profileId,
          isKidsProfile: current.isKidsProfile,
          maxRating: current.maxRating,
          allowUnrated: current.allowUnrated,
        },
        data: next,
        select: PROFILE_VIEW_SELECT,
      });
      return { status: "ok", profile, skipped };
    } catch (error) {
      // No row matched: the limits changed (or the profile went) since they
      // were read. The next pass reads them again.
      if (!isRecordNotFound(error)) throw error;
    }
  }

  if (strict) return { status: "conflict" };

  // The limits kept changing under this request: write everything else and
  // leave them as whoever is changing them set them.
  const rest = { ...data };
  const skipped = PARENTAL_FIELDS.filter((key) => rest[key] !== undefined);
  for (const key of skipped) delete rest[key];
  if (Object.keys(rest).length === 0) return { status: "ok", skipped };
  const profile = await prisma.profile.update({ where: { id: profileId }, data: rest, select: PROFILE_VIEW_SELECT });
  return { status: "ok", profile, skipped };
};
