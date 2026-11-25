-- A version for a profile's collections (#669), so a write can name the copy it
-- was based on (`If-Match`) the way the TV settings and Home layout documents
-- do. Every stored list starts at 1; "no row" is version 0.
ALTER TABLE "ProfileCollections" ADD COLUMN "version" INTEGER NOT NULL DEFAULT 1;
