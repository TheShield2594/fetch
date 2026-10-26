import { useState } from "react";
import { Check, Loader2 } from "lucide-react";
import { api, RATING_LEVELS, type Profile, type ProfileParental, type RatingLevel } from "../../api";
import { BUILTIN_AVATARS, avatarImageUrl, PROFILE_COLORS } from "../../utils/builtinAvatars";
import { ProfileAvatar } from "../ProfileAvatar";

export const RATING_LABELS: Record<RatingLevel, string> = {
  ALL_AGES: "All ages (G, TV-Y, TV-G)",
  OLDER_KIDS: "Older kids (TV-Y7)",
  PARENTAL_GUIDANCE: "Parental guidance (PG, TV-PG)",
  TEENS: "Teens (PG-13, TV-14)",
  MATURE: "Mature (R, TV-MA)",
  ADULTS_ONLY: "Adults only (NC-17)",
};

/** What the TV applies when a profile is marked as a kids profile. */
const KIDS_PRESET: Pick<ProfileParental, "maxRating" | "allowUnrated"> = {
  maxRating: "PARENTAL_GUIDANCE",
  allowUnrated: false,
};

const UNRESTRICTED: ProfileParental = { isKidsProfile: false, maxRating: null, allowUnrated: true };

type Draft = {
  avatarColorHex: string | null;
  avatarId: string | null;
  usesPrimaryAddons: boolean;
  parental: ProfileParental;
};

const draftOf = (profile: Profile): Draft => ({
  avatarColorHex: profile.avatarColorHex ?? null,
  avatarId: profile.avatarId ?? null,
  usesPrimaryAddons: profile.usesPrimaryAddons ?? false,
  parental: profile.parental ?? UNRESTRICTED,
});

const LABEL = "text-xs font-semibold uppercase tracking-[0.04em]";

/**
 * Everything about a profile the TV shows or enforces, edited in one place:
 * its colour and picture, whether it shares the primary profile's addons, and
 * the parental limit. Saved together, so a TV that syncs mid-edit never sees
 * half a change.
 */
export function ProfileEditor({
  profile,
  isPrimary,
  onSaved,
  onCancel,
}: {
  profile: Profile;
  /** The primary profile is the one others share addons with, so it can't share its own. */
  isPrimary: boolean;
  onSaved: (profile: Profile) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<Draft>(() => draftOf(profile));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const setParental = (next: Partial<ProfileParental>) =>
    setDraft((current) => ({ ...current, parental: { ...current.parental, ...next } }));

  const toggleKids = (isKidsProfile: boolean) =>
    setParental(isKidsProfile ? { isKidsProfile, ...KIDS_PRESET } : { isKidsProfile });

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const { profile: updated } = await api.updateProfile(profile.id, {
        avatarColorHex: draft.avatarColorHex,
        avatarId: draft.avatarId,
        ...(isPrimary ? {} : { usesPrimaryAddons: draft.usesPrimaryAddons }),
        parental: draft.parental,
      });
      onSaved(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save this profile.");
      setSaving(false);
    }
  };

  const preview = { name: profile.name, avatarColorHex: draft.avatarColorHex, avatarId: draft.avatarId };

  return (
    <div className="mt-3 space-y-5 border-t pt-4" style={{ borderColor: "var(--border)" }}>
      <div className="flex items-center gap-3">
        <ProfileAvatar profile={preview} size="lg" />
        <p className="text-sm" style={{ color: "var(--text-dim)" }}>
          How {profile.name} appears on the website and on every paired TV.
        </p>
      </div>

      <fieldset className="space-y-2">
        <legend className={LABEL} style={{ color: "var(--text-mute)" }}>Colour</legend>
        <div className="flex flex-wrap gap-2">
          {PROFILE_COLORS.map((color) => {
            const selected = draft.avatarColorHex?.toUpperCase() === color.hex;
            return (
              <button
                key={color.hex}
                type="button"
                onClick={() => setDraft((d) => ({ ...d, avatarColorHex: color.hex }))}
                aria-label={color.name}
                aria-pressed={selected}
                className="flex h-8 w-8 items-center justify-center rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
                style={{ backgroundColor: color.hex, boxShadow: selected ? "0 0 0 2px var(--bg-1), 0 0 0 4px var(--text)" : undefined }}
              >
                {selected && <Check size={14} className="text-white" />}
              </button>
            );
          })}
        </div>
      </fieldset>

      <fieldset className="space-y-2">
        <legend className={LABEL} style={{ color: "var(--text-mute)" }}>Picture</legend>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(2.75rem,1fr))] gap-2">
          <button
            type="button"
            onClick={() => setDraft((d) => ({ ...d, avatarId: null }))}
            aria-pressed={draft.avatarId === null}
            className="flex h-11 items-center justify-center rounded-xl border text-2xs font-semibold focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            style={{
              borderColor: draft.avatarId === null ? "var(--text)" : "var(--border)",
              color: "var(--text-dim)",
            }}
          >
            Initials
          </button>
          {BUILTIN_AVATARS.map((avatar) => {
            const selected = draft.avatarId === avatar.id;
            return (
              <button
                key={avatar.id}
                type="button"
                onClick={() => setDraft((d) => ({ ...d, avatarId: avatar.id }))}
                aria-label={avatar.name}
                aria-pressed={selected}
                title={avatar.name}
                className="flex h-11 items-center justify-center rounded-xl border focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                style={{ borderColor: selected ? "var(--text)" : "var(--border)", background: avatar.bgColor }}
              >
                <img src={avatarImageUrl(avatar.id)} alt="" className="h-7 w-7 object-contain" loading="lazy" />
              </button>
            );
          })}
        </div>
      </fieldset>

      {!isPrimary && (
        <label className="flex items-start gap-3 text-sm" style={{ color: "var(--text)" }}>
          <input
            type="checkbox"
            checked={draft.usesPrimaryAddons}
            onChange={(e) => setDraft((d) => ({ ...d, usesPrimaryAddons: e.target.checked }))}
            className="mt-0.5"
          />
          <span>
            Use the primary profile&apos;s addons
            <span className="block text-xs" style={{ color: "var(--text-mute)" }}>
              On the TV, this profile sees the addons installed for the primary profile instead of its own.
            </span>
          </span>
        </label>
      )}

      <fieldset className="space-y-3">
        <legend className={LABEL} style={{ color: "var(--text-mute)" }}>Parental controls</legend>
        <label className="flex items-start gap-3 text-sm" style={{ color: "var(--text)" }}>
          <input
            type="checkbox"
            checked={draft.parental.isKidsProfile}
            onChange={(e) => toggleKids(e.target.checked)}
            className="mt-0.5"
          />
          <span>
            Kids profile
            <span className="block text-xs" style={{ color: "var(--text-mute)" }}>
              Limits titles to parental guidance and hides unrated ones. The TV also keeps addons and settings
              behind the parent PIN.
            </span>
          </span>
        </label>
        <label className="block text-sm" style={{ color: "var(--text)" }}>
          <span className="mb-1 block">Highest rating allowed</span>
          <select
            value={draft.parental.maxRating ?? ""}
            onChange={(e) => setParental({ maxRating: (e.target.value || null) as RatingLevel | null })}
            className="w-full rounded-lg border px-2.5 py-1.5 text-sm"
            style={{ borderColor: "var(--border)", color: "var(--text)", background: "var(--bg-1)" }}
          >
            <option value="">No limit</option>
            {RATING_LEVELS.map((level) => (
              <option key={level} value={level}>{RATING_LABELS[level]}</option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-3 text-sm" style={{ color: "var(--text)" }}>
          <input
            type="checkbox"
            checked={draft.parental.allowUnrated}
            disabled={draft.parental.maxRating === null}
            onChange={(e) => setParental({ allowUnrated: e.target.checked })}
          />
          Show titles with no rating
        </label>
        <p className="text-xs" style={{ color: "var(--text-mute)" }}>
          A paired TV applies the limit on its next sync and keeps applying it offline.
        </p>
      </fieldset>

      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
      <div className="flex gap-2">
        <button type="button" onClick={save} disabled={saving} className="btn-primary btn-sm">
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} Save
        </button>
        <button type="button" onClick={onCancel} className="btn-secondary btn-sm">Cancel</button>
      </div>
    </div>
  );
}
