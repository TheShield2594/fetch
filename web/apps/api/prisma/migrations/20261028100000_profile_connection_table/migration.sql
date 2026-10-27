-- A profile's sign-ins to Simkl, MDBList, Plex, Jellyfin and Emby.
CREATE TABLE "ProfileConnection" (
    "id" UUID NOT NULL,
    "profileId" UUID NOT NULL,
    "provider" TEXT NOT NULL,
    "externalKey" TEXT NOT NULL DEFAULT '',
    "credentials" TEXT NOT NULL,
    "details" JSONB NOT NULL DEFAULT '{}',
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "ProfileConnection_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "ProfileConnection_profileId_provider_externalKey_key" ON "ProfileConnection"("profileId", "provider", "externalKey");
CREATE INDEX "ProfileConnection_provider_idx" ON "ProfileConnection"("provider");

ALTER TABLE "ProfileConnection" ADD CONSTRAINT "ProfileConnection_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;
