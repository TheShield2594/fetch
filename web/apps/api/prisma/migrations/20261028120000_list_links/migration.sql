-- A custom list linked to a Trakt or MDBList list of the same profile.
CREATE TABLE "ListLink" (
    "id" UUID NOT NULL,
    "listId" UUID NOT NULL,
    "profileId" UUID NOT NULL,
    "provider" TEXT NOT NULL,
    "remoteId" TEXT NOT NULL,
    "remoteName" TEXT NOT NULL,
    "snapshot" JSONB,
    "lastSyncedAt" TIMESTAMPTZ(6),
    "lastError" TEXT,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "ListLink_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "ListLink_listId_key" ON "ListLink"("listId");
CREATE UNIQUE INDEX "ListLink_profileId_provider_remoteId_key" ON "ListLink"("profileId", "provider", "remoteId");
CREATE INDEX "ListLink_provider_idx" ON "ListLink"("provider");

ALTER TABLE "ListLink" ADD CONSTRAINT "ListLink_listId_fkey"
    FOREIGN KEY ("listId") REFERENCES "List"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "ListLink" ADD CONSTRAINT "ListLink_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;
