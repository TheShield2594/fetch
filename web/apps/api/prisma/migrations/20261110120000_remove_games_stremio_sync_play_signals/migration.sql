-- Games (Steam/IGDB), the Stremio account library sync and addon play detection
-- were removed. Their tables go, along with the KV rows they left behind: the
-- per-profile Stremio library watermark and the last-run status of their
-- scheduled jobs, which Settings would otherwise keep listing.
DROP TABLE IF EXISTS "Game";
DROP TABLE IF EXISTS "PlaySignal";
DROP TABLE IF EXISTS "StremioAuth";

DELETE FROM "KV" WHERE "key" LIKE 'stremio:libraryWatermark%';
DELETE FROM "KV" WHERE "key" IN (
  'job-status:steam-sync',
  'job-status:stremio-library-sync',
  'job-status:stremio-play-signals'
);
