-- Device pairing (#91), collections (#95) and the profile fields the TV keeps (#96).

ALTER TABLE "Profile"
    ADD COLUMN "avatarColorHex" TEXT,
    ADD COLUMN "avatarId" TEXT,
    ADD COLUMN "profileBackgroundId" TEXT,
    ADD COLUMN "usesPrimaryAddons" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "usesPrimaryPlugins" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "isKidsProfile" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "maxRating" TEXT,
    ADD COLUMN "allowUnrated" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "updatedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE "Device" (
    "id" UUID NOT NULL DEFAULT gen_random_uuid(),
    "name" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMPTZ(6),

    CONSTRAINT "Device_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Device_tokenHash_key" ON "Device"("tokenHash");

CREATE TABLE "DevicePairing" (
    "id" UUID NOT NULL DEFAULT gen_random_uuid(),
    "code" TEXT NOT NULL,
    "pollSecretHash" TEXT NOT NULL,
    "deviceName" TEXT NOT NULL,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "confirmedAt" TIMESTAMPTZ(6),
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DevicePairing_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "DevicePairing_code_key" ON "DevicePairing"("code");
CREATE UNIQUE INDEX "DevicePairing_pollSecretHash_key" ON "DevicePairing"("pollSecretHash");

CREATE TABLE "DeviceProfileSlot" (
    "deviceId" UUID NOT NULL,
    "slot" INTEGER NOT NULL,
    "profileId" UUID NOT NULL,

    CONSTRAINT "DeviceProfileSlot_pkey" PRIMARY KEY ("deviceId", "slot")
);

CREATE UNIQUE INDEX "DeviceProfileSlot_deviceId_profileId_key" ON "DeviceProfileSlot"("deviceId", "profileId");

ALTER TABLE "DeviceProfileSlot" ADD CONSTRAINT "DeviceProfileSlot_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "DeviceProfileSlot" ADD CONSTRAINT "DeviceProfileSlot_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "ProfileCollections" (
    "profileId" UUID NOT NULL,
    "collections" JSONB NOT NULL,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "ProfileCollections_pkey" PRIMARY KEY ("profileId")
);

ALTER TABLE "ProfileCollections" ADD CONSTRAINT "ProfileCollections_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;
