-- Addon manifest URLs and notification channel URLs are now encrypted at rest
-- (each can carry a credential). Encryption happens in the API's startup
-- sweep, not here: the key derives from API_TOKEN, which the database never
-- sees. Ciphertext differs on every write, so the per-profile uniqueness of an
-- addon moves from the URL to a keyed hash of it, filled in by the same sweep.
ALTER TABLE "ProfileAddon" ADD COLUMN "manifestUrlKey" TEXT;

DROP INDEX "ProfileAddon_profileId_manifestUrl_key";

CREATE UNIQUE INDEX "ProfileAddon_profileId_manifestUrlKey_key" ON "ProfileAddon"("profileId", "manifestUrlKey");
