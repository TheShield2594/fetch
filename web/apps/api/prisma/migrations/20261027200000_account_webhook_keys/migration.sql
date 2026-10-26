-- Each account's own Plex/Jellyfin/Emby webhook key, so a media server's plays
-- land in that account's profiles and nobody else's. Stored as a SHA-256 hash.
ALTER TABLE "Account" ADD COLUMN "webhookKeyHash" TEXT;
ALTER TABLE "Account" ADD COLUMN "webhookKeyCreatedAt" TIMESTAMPTZ(6);

CREATE UNIQUE INDEX "Account_webhookKeyHash_key" ON "Account"("webhookKeyHash");
