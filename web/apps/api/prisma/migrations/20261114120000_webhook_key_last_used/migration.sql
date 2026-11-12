-- When a media server last reached a webhook with the account's key, so
-- Settings can say a link is working rather than only that one exists. NULL
-- for every existing key: nothing has been recorded yet.
ALTER TABLE "Account" ADD COLUMN "webhookKeyLastUsedAt" TIMESTAMPTZ(6);
