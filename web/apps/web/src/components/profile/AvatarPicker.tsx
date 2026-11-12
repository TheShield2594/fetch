import { Check } from "lucide-react";
import { BUILTIN_AVATARS, avatarImageUrl, PROFILE_COLORS } from "../../utils/builtinAvatars";

export type AvatarChoice = { avatarColorHex: string | null; avatarId: string | null };

const LEGEND = "text-xs font-semibold uppercase tracking-[0.04em]";

/**
 * A profile's picture and colour: the 40 illustrated avatars the TV ships, or
 * initials, on one of the TV's colours. Used when a profile is made and when
 * it is edited, so both offer the same choice.
 */
export function AvatarPicker({
  value,
  onChange,
}: {
  value: AvatarChoice;
  onChange: (next: AvatarChoice) => void;
}) {
  return (
    <div className="space-y-4">
      <fieldset className="space-y-2">
        <legend className={LEGEND} style={{ color: "var(--text-mute)" }}>Picture</legend>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(3rem,1fr))] gap-2">
          <button
            type="button"
            onClick={() => onChange({ ...value, avatarId: null })}
            aria-pressed={value.avatarId === null}
            className="flex aspect-square items-center justify-center rounded-full border-2 text-2xs font-semibold focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            style={{
              borderColor: value.avatarId === null ? "var(--text)" : "var(--border)",
              color: "var(--text-dim)",
            }}
          >
            Initials
          </button>
          {BUILTIN_AVATARS.map((avatar) => {
            const selected = value.avatarId === avatar.id;
            return (
              <button
                key={avatar.id}
                type="button"
                onClick={() => onChange({ ...value, avatarId: avatar.id })}
                aria-label={avatar.name}
                aria-pressed={selected}
                title={avatar.name}
                className="flex aspect-square items-center justify-center rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
                style={{
                  background: avatar.bgColor,
                  boxShadow: selected ? "0 0 0 2px var(--bg-1), 0 0 0 4px var(--text)" : undefined,
                }}
              >
                <img src={avatarImageUrl(avatar.id)} alt="" className="h-[78%] w-[78%] object-contain" loading="lazy" />
              </button>
            );
          })}
        </div>
      </fieldset>

      <fieldset className="space-y-2">
        <legend className={LEGEND} style={{ color: "var(--text-mute)" }}>Colour</legend>
        <div className="flex flex-wrap gap-2.5">
          {PROFILE_COLORS.map((color) => {
            const selected = value.avatarColorHex?.toUpperCase() === color.hex;
            return (
              <button
                key={color.hex}
                type="button"
                onClick={() => onChange({ ...value, avatarColorHex: color.hex })}
                aria-label={color.name}
                aria-pressed={selected}
                className="flex h-9 w-9 items-center justify-center rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
                style={{ backgroundColor: color.hex, boxShadow: selected ? "0 0 0 2px var(--bg-1), 0 0 0 4px var(--text)" : undefined }}
              >
                {selected && <Check size={16} className="text-white" />}
              </button>
            );
          })}
        </div>
      </fieldset>
    </div>
  );
}
