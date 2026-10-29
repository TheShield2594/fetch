/**
 * The avatars a profile can wear, the same set the TV ships in its APK
 * (`BundledAvatars.kt`), so a picture chosen here is the one the TV draws. The
 * images are copied into `public/avatars/` from the app's assets.
 *
 * Artwork: made for Fetch with Higgsfield, see `docs/avatars.md` at the repo root.
 */

export type BuiltinAvatar = { id: string; name: string; category: "animals" | "characters" | "fun"; bgColor: string };

export const BUILTIN_AVATARS: BuiltinAvatar[] = [
  { id: "fetch:fox", name: "Fox", category: "animals", bgColor: "#F4511E" },
  { id: "fetch:cat", name: "Cat", category: "animals", bgColor: "#8E24AA" },
  { id: "fetch:dog", name: "Dog", category: "animals", bgColor: "#8D6E63" },
  { id: "fetch:panda", name: "Panda", category: "animals", bgColor: "#43A047" },
  { id: "fetch:koala", name: "Koala", category: "animals", bgColor: "#78909C" },
  { id: "fetch:lion", name: "Lion", category: "animals", bgColor: "#00897B" },
  { id: "fetch:tiger", name: "Tiger", category: "animals", bgColor: "#00838F" },
  { id: "fetch:frog", name: "Frog", category: "animals", bgColor: "#00897B" },
  { id: "fetch:owl", name: "Owl", category: "animals", bgColor: "#5C6BC0" },
  { id: "fetch:penguin", name: "Penguin", category: "animals", bgColor: "#1E88E5" },
  { id: "fetch:unicorn", name: "Unicorn", category: "animals", bgColor: "#D81B60" },
  { id: "fetch:dragon", name: "Dragon", category: "animals", bgColor: "#C62828" },
  { id: "fetch:octopus", name: "Octopus", category: "animals", bgColor: "#8E24AA" },
  { id: "fetch:monkey", name: "Monkey", category: "animals", bgColor: "#26A69A" },
  { id: "fetch:rabbit", name: "Rabbit", category: "animals", bgColor: "#EC407A" },
  { id: "fetch:bear", name: "Bear", category: "animals", bgColor: "#FFB300" },
  { id: "fetch:hamster", name: "Hamster", category: "animals", bgColor: "#5C6BC0" },
  { id: "fetch:sloth", name: "Sloth", category: "animals", bgColor: "#7CB342" },
  { id: "fetch:dinosaur", name: "Dinosaur", category: "animals", bgColor: "#F9A825" },
  { id: "fetch:butterfly", name: "Butterfly", category: "animals", bgColor: "#00ACC1" },
  { id: "fetch:robot", name: "Robot", category: "characters", bgColor: "#546E7A" },
  { id: "fetch:alien", name: "Alien", category: "characters", bgColor: "#7B1FA2" },
  { id: "fetch:ghost", name: "Ghost", category: "characters", bgColor: "#5E35B1" },
  { id: "fetch:space_invader", name: "Space Invader", category: "characters", bgColor: "#00ACC1" },
  { id: "fetch:cool", name: "Cool", category: "characters", bgColor: "#1E88E5" },
  { id: "fetch:pumpkin", name: "Pumpkin", category: "characters", bgColor: "#4527A0" },
  { id: "fetch:rocket", name: "Rocket", category: "fun", bgColor: "#F4511E" },
  { id: "fetch:star", name: "Star", category: "fun", bgColor: "#3949AB" },
  { id: "fetch:pizza", name: "Pizza", category: "fun", bgColor: "#00897B" },
  { id: "fetch:popcorn", name: "Popcorn", category: "fun", bgColor: "#1E88E5" },
  { id: "fetch:controller", name: "Controller", category: "fun", bgColor: "#455A64" },
  { id: "fetch:guitar", name: "Guitar", category: "fun", bgColor: "#6A1B9A" },
  { id: "fetch:soccer", name: "Soccer", category: "fun", bgColor: "#43A047" },
  { id: "fetch:basketball", name: "Basketball", category: "fun", bgColor: "#3949AB" },
  { id: "fetch:rainbow", name: "Rainbow", category: "fun", bgColor: "#26A69A" },
  { id: "fetch:cactus", name: "Cactus", category: "fun", bgColor: "#689F38" },
  { id: "fetch:sun", name: "Sun", category: "fun", bgColor: "#1565C0" },
  { id: "fetch:crystal_ball", name: "Crystal Ball", category: "fun", bgColor: "#263238" },
  { id: "fetch:clapper", name: "Clapper", category: "fun", bgColor: "#37474F" },
  { id: "fetch:donut", name: "Donut", category: "fun", bgColor: "#D81B60" },
];

const BY_ID = new Map(BUILTIN_AVATARS.map((avatar) => [avatar.id, avatar]));

export const builtinAvatar = (id: string | null | undefined): BuiltinAvatar | null => (id ? BY_ID.get(id) ?? null : null);

export const avatarImageUrl = (id: string): string => `/avatars/${id.replace(/^fetch:/, "")}.webp`;

/** The colours the TV offers for a profile (`PROFILE_AVATAR_COLORS`). */
export const PROFILE_COLORS: { hex: string; name: string }[] = [
  { hex: "#E53935", name: "Crimson" },
  { hex: "#1E88E5", name: "Ocean" },
  { hex: "#8E24AA", name: "Violet" },
  { hex: "#43A047", name: "Emerald" },
  { hex: "#FFB300", name: "Amber" },
  { hex: "#D81B60", name: "Rose" },
  { hex: "#00ACC1", name: "Cyan" },
  { hex: "#6D4C41", name: "Brown" },
];
