-- Sync state a connection keeps between runs (a media server's item index).
ALTER TABLE "ProfileConnection" ADD COLUMN "state" JSONB NOT NULL DEFAULT '{}';
