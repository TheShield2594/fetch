-- One more notification channel (#646): an Apprise API server, which in turn
-- reaches email, Telegram, Matrix, Pushover and the rest of what Apprise
-- speaks. On its own in this migration: a new enum value can't be used in the
-- transaction that adds it.
ALTER TYPE "NotificationChannelKind" ADD VALUE IF NOT EXISTS 'apprise';
