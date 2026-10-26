import { describe, expect, it } from "vitest";
import { prisma } from "./prisma.js";
import { createProfile } from "./test-fixtures/int-db.js";
import { listDeviceProfiles } from "./device-slots.js";
import { hashSecret } from "./device-token.js";

// Slot numbering and what a profile deletion takes with it both rest on the
// foreign keys and unique indexes in the migration, which a mocked Prisma
// would never notice missing.

const createDevice = () =>
  prisma.device.create({ data: { name: "TV", tokenHash: hashSecret(`fdt_${Math.random()}`) } });

describe("device profile slots", () => {
  it("keeps a profile's slot when an older profile is deleted, and refills the primary", async () => {
    const device = await createDevice();
    const first = await createProfile();
    const second = await createProfile();
    const third = await createProfile();

    expect((await listDeviceProfiles(device.id)).map((p) => [p.slot, p.profile.id])).toEqual([
      [1, first.id],
      [2, second.id],
      [3, third.id],
    ]);

    await prisma.profile.delete({ where: { id: second.id } });
    expect((await listDeviceProfiles(device.id)).map((p) => [p.slot, p.profile.id])).toEqual([
      [1, first.id],
      [3, third.id],
    ]);

    // The TV needs a primary, so the oldest remaining profile moves into slot 1.
    await prisma.profile.delete({ where: { id: first.id } });
    expect((await listDeviceProfiles(device.id)).map((p) => [p.slot, p.profile.id])).toEqual([[1, third.id]]);
  });

  it("goes when the device is revoked", async () => {
    const device = await createDevice();
    await createProfile();
    await listDeviceProfiles(device.id);

    await prisma.device.delete({ where: { id: device.id } });

    expect(await prisma.deviceProfileSlot.count({ where: { deviceId: device.id } })).toBe(0);
  });
});

describe("deleting a profile", () => {
  it("takes its collections with it", async () => {
    const profile = await createProfile();
    await prisma.profileCollections.create({
      data: { profileId: profile.id, collections: [{ id: "c", title: "C" }], updatedAt: new Date() },
    });

    await prisma.profile.delete({ where: { id: profile.id } });

    expect(await prisma.profileCollections.count({ where: { profileId: profile.id } })).toBe(0);
  });
});
