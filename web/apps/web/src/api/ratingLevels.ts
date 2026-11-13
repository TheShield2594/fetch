/**
 * The TV's age ladder, strictest first. Movie and TV ratings share it.
 *
 * Kept out of `api/types.ts` on purpose: that module is otherwise types only,
 * and while it carried this one runtime value the bundler placed it in the
 * chunk of the only component that reads it (the profile sheet). `api.ts`
 * re-exports `types.ts`, so the entry then imported that chunk statically and
 * every cold start preloaded the profile and settings code with it. Import this
 * straight from here, not through `../api`.
 */
export const RATING_LEVELS = ["ALL_AGES", "OLDER_KIDS", "PARENTAL_GUIDANCE", "TEENS", "MATURE", "ADULTS_ONLY"] as const;
