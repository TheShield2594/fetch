-- Jellyseerr requests are gone from Fetch, as they are from the TV app. This
-- drops the stored server URL and API key, and the job status it reported.
DELETE FROM "KV" WHERE "key" IN ('jellyseerr:config', 'job-status:jellyseerr-request');
