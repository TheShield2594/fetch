-- Trakt and Stremio connections move from per account to per profile, as in
-- the TV app. Each connection goes to its account's first profile (a Stremio
-- connection to the profile that made it, where known); the pre-accounts
-- "default" connection goes to the admin's first profile, or the oldest
-- profile on an install without accounts. Their sync state in KV follows.

CREATE TEMP TABLE connection_owner (owner TEXT PRIMARY KEY, profile_id UUID);

INSERT INTO connection_owner (owner, profile_id)
SELECT a."id"::text,
       (SELECT p."id" FROM "Profile" p WHERE p."accountId" = a."id" ORDER BY p."createdAt" LIMIT 1)
FROM "Account" a;

INSERT INTO connection_owner (owner, profile_id)
VALUES ('default', COALESCE(
  (SELECT p."id" FROM "Profile" p JOIN "Account" a ON a."id" = p."accountId"
   WHERE a."isAdmin" ORDER BY a."createdAt", p."createdAt" LIMIT 1),
  (SELECT p."id" FROM "Profile" p ORDER BY p."createdAt" LIMIT 1)
));

-- TraktToken: a profile id column that cascades, then rekey.
ALTER TABLE "TraktToken" ADD COLUMN "profileId" UUID;
UPDATE "TraktToken" t SET "profileId" = o.profile_id, "id" = o.profile_id::text
FROM connection_owner o WHERE t."id" = o.owner AND o.profile_id IS NOT NULL;
DELETE FROM "TraktToken" WHERE "profileId" IS NULL;
ALTER TABLE "TraktToken" ALTER COLUMN "id" DROP DEFAULT;
CREATE UNIQUE INDEX "TraktToken_profileId_key" ON "TraktToken"("profileId");
ALTER TABLE "TraktToken" ADD CONSTRAINT "TraktToken_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- StremioAuth already names its profile; keep it where it still exists. The
-- watermark moves with the row, before the row is rekeyed.
CREATE TEMP TABLE stremio_move AS
SELECT s."id" AS old_id, COALESCE(p."id", o.profile_id) AS profile_id
FROM "StremioAuth" s
LEFT JOIN "Profile" p ON p."id" = s."profileId"
LEFT JOIN connection_owner o ON o.owner = s."id";

UPDATE "KV" k SET "key" = 'stremio:libraryWatermark:' || m.profile_id::text
FROM stremio_move m
WHERE m.profile_id IS NOT NULL
  AND k."key" = CASE WHEN m.old_id = 'default' THEN 'stremio:libraryWatermark'
                     ELSE 'stremio:libraryWatermark:' || m.old_id END;

UPDATE "StremioAuth" s SET "id" = m.profile_id::text, "profileId" = m.profile_id
FROM stremio_move m WHERE s."id" = m.old_id AND m.profile_id IS NOT NULL;
DELETE FROM "StremioAuth" s USING stremio_move m WHERE s."id" = m.old_id AND m.profile_id IS NULL;
DROP TABLE stremio_move;

ALTER TABLE "StremioAuth" ALTER COLUMN "id" DROP DEFAULT;
CREATE UNIQUE INDEX "StremioAuth_profileId_key" ON "StremioAuth"("profileId");
ALTER TABLE "StremioAuth" ADD CONSTRAINT "StremioAuth_profileId_fkey"
    FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Trakt sync state: "<base>" (pre-accounts) or "<base>:<accountId>" becomes
-- "<base>:<profileId>".
UPDATE "KV" k SET "key" = split_part(k."key", ':', 1) || ':' || split_part(k."key", ':', 2) || ':' || o.profile_id::text
FROM connection_owner o
WHERE o.profile_id IS NOT NULL
  AND split_part(k."key", ':', 1) || ':' || split_part(k."key", ':', 2) IN
      ('trakt:watchlistSnapshot', 'trakt:lastPolledAt', 'trakt:historyBackfilledAt')
  AND COALESCE(NULLIF(split_part(k."key", ':', 3), ''), 'default') = o.owner;

DROP TABLE connection_owner;
