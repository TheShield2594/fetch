import type { Profile } from "../api";
import { avatarColor, initials } from "../utils/avatar";
import { avatarImageUrl, builtinAvatar } from "../utils/builtinAvatars";

const SIZES = {
  sm: "h-8 w-8 text-xs",
  md: "h-10 w-10 text-sm",
  lg: "h-16 w-16 text-lg",
} as const;

/**
 * A profile's circle, drawn the way the TV draws it: the chosen built-in
 * avatar on its own background, or the initials on the profile's colour. A
 * profile that has chosen neither falls back to the colour derived from its
 * name, as it always has.
 */
export function ProfileAvatar({
  profile,
  size = "sm",
}: {
  profile: Pick<Profile, "name" | "avatarColorHex" | "avatarId">;
  size?: keyof typeof SIZES;
}) {
  const avatar = builtinAvatar(profile.avatarId);
  const background = profile.avatarColorHex ?? avatar?.bgColor ?? avatarColor(profile.name);
  return (
    <span
      aria-hidden="true"
      className={`flex flex-none items-center justify-center overflow-hidden rounded-full font-bold text-white ${SIZES[size]}`}
      style={{ backgroundColor: background }}
    >
      {avatar ? (
        <img src={avatarImageUrl(avatar.id)} alt="" className="h-[78%] w-[78%] object-contain" loading="lazy" />
      ) : (
        initials(profile.name)
      )}
    </span>
  );
}
