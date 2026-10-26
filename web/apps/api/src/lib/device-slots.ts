import { prisma } from "./prisma.js";
import { PROFILE_VIEW_SELECT, type ProfileRow } from "./profile-view.js";

/** The TV's `ProfileManager.MAX_PROFILES`; profiles past it have no slot on that device. */
export const MAX_DEVICE_PROFILE_SLOTS = 6;

/** Slot 1 is the TV's primary profile, the one others can share addons with. */
export const PRIMARY_SLOT = 1;

export type SlottedProfile = { slot: number; profile: ProfileRow };

/**
 * Every profile this device can show, with the slot it shows it in.
 *
 * Slots are assigned on first sight and then kept, so a profile stays in the
 * same place on a TV — and keeps the TV-side settings stored under that slot —
 * while profiles are added and removed elsewhere. A new profile takes the
 * lowest free slot. When slot 1 is empty (its profile was deleted) the oldest
 * profile moves into it, because the TV requires a primary.
 *
 * Only the profiles of the device's own account (`accountId`), or every
 * profile for a device paired before accounts existed.
 */
export const listDeviceProfiles = async (deviceId: string, accountId: string | null): Promise<SlottedProfile[]> =>
  prisma.$transaction(async (tx) => {
    const [profiles, slots] = await Promise.all([
      tx.profile.findMany({
        where: accountId ? { accountId } : {},
        orderBy: { createdAt: "asc" },
        select: PROFILE_VIEW_SELECT,
      }),
      tx.deviceProfileSlot.findMany({ where: { deviceId } }),
    ]);

    const slotByProfile = new Map(slots.map((row) => [row.profileId, row.slot]));
    const used = new Set(slots.map((row) => row.slot));

    if (!used.has(PRIMARY_SLOT) && profiles.length > 0) {
      const oldest = profiles[0]!;
      const previous = slotByProfile.get(oldest.id);
      if (previous !== undefined) {
        await tx.deviceProfileSlot.delete({ where: { deviceId_slot: { deviceId, slot: previous } } });
        used.delete(previous);
      }
      await tx.deviceProfileSlot.create({ data: { deviceId, slot: PRIMARY_SLOT, profileId: oldest.id } });
      slotByProfile.set(oldest.id, PRIMARY_SLOT);
      used.add(PRIMARY_SLOT);
    }

    for (const profile of profiles) {
      if (slotByProfile.has(profile.id)) continue;
      let slot = 1;
      while (used.has(slot) && slot <= MAX_DEVICE_PROFILE_SLOTS) slot += 1;
      if (slot > MAX_DEVICE_PROFILE_SLOTS) break;
      await tx.deviceProfileSlot.create({ data: { deviceId, slot, profileId: profile.id } });
      slotByProfile.set(profile.id, slot);
      used.add(slot);
    }

    return profiles
      .filter((profile) => slotByProfile.has(profile.id))
      .map((profile) => ({ slot: slotByProfile.get(profile.id)!, profile }))
      .sort((a, b) => a.slot - b.slot);
  });

export const findSlotProfileId = async (deviceId: string, slot: number): Promise<string | null> => {
  const row = await prisma.deviceProfileSlot.findUnique({ where: { deviceId_slot: { deviceId, slot } } });
  return row?.profileId ?? null;
};
