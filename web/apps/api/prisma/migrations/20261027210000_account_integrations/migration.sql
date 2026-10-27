-- Trakt and Stremio connections are now kept per account: a TraktToken or
-- StremioAuth row's id is the id of the account that connected it, and its
-- sync state in KV carries the same id as a suffix. No schema change; this only
-- hands an install's existing connection to its admin account, where one has
-- already been created. Installs without an account keep the "default" row and
-- unsuffixed keys until the admin is created, which claims them then.
UPDATE "TraktToken" SET "id" = admin."id"::text
FROM (SELECT "id" FROM "Account" WHERE "isAdmin" ORDER BY "createdAt" LIMIT 1) AS admin
WHERE "TraktToken"."id" = 'default';

UPDATE "StremioAuth" SET "id" = admin."id"::text
FROM (SELECT "id" FROM "Account" WHERE "isAdmin" ORDER BY "createdAt" LIMIT 1) AS admin
WHERE "StremioAuth"."id" = 'default';

UPDATE "KV" SET "key" = "KV"."key" || ':' || admin."id"::text
FROM (SELECT "id" FROM "Account" WHERE "isAdmin" ORDER BY "createdAt" LIMIT 1) AS admin
WHERE "KV"."key" IN (
  'trakt:watchlistSnapshot',
  'trakt:lastPolledAt',
  'trakt:historyBackfilledAt',
  'stremio:libraryWatermark'
);
