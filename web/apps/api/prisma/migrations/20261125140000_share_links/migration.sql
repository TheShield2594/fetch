-- Links that work without signing in (#646): a profile's calendar as an iCal
-- feed, and a list as a read-only page. Neither stores a secret: the key in
-- the link is derived from API_TOKEN, the id and this generation. NULL is a
-- link never turned on, a positive number one that is on, and a negative one
-- a link turned off, kept so that turning it on again can't revive its address.
ALTER TABLE "Profile" ADD COLUMN "calendarFeedGeneration" INTEGER;
ALTER TABLE "List" ADD COLUMN "shareGeneration" INTEGER;

-- Whether a notification channel also gets the server's own alerts: a
-- background job that keeps failing, a TV whose sync does. Off for every
-- existing channel; only the admin can turn it on.
ALTER TABLE "NotificationChannel" ADD COLUMN "adminAlerts" BOOLEAN NOT NULL DEFAULT false;
