-- Per-profile addons and per-profile JSON documents (TV settings, Home layout),
-- the storage behind the website pages that manage what a TV runs.

CREATE TABLE "ProfileAddon" (
    "id" UUID NOT NULL DEFAULT gen_random_uuid(),
    "manifestUrl" TEXT NOT NULL,
    "position" INTEGER NOT NULL,
    "enabled" BOOLEAN NOT NULL DEFAULT true,
    "addonId" TEXT,
    "name" TEXT NOT NULL,
    "version" TEXT,
    "description" TEXT,
    "logo" TEXT,
    "manifest" JSONB,
    "manifestFetchedAt" TIMESTAMPTZ(6),
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "profileId" UUID NOT NULL,

    CONSTRAINT "ProfileAddon_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "ProfileAddon_profileId_manifestUrl_key" ON "ProfileAddon"("profileId", "manifestUrl");

CREATE INDEX "ProfileAddon_profileId_position_idx" ON "ProfileAddon"("profileId", "position");

ALTER TABLE "ProfileAddon" ADD CONSTRAINT "ProfileAddon_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "ProfileDocument" (
    "kind" TEXT NOT NULL,
    "document" JSONB NOT NULL,
    "version" INTEGER NOT NULL,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "profileId" UUID NOT NULL,

    CONSTRAINT "ProfileDocument_pkey" PRIMARY KEY ("profileId", "kind")
);

ALTER TABLE "ProfileDocument" ADD CONSTRAINT "ProfileDocument_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;
