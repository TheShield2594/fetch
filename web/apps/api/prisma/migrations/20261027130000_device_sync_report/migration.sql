-- What a paired device reports after each sync: its build and how the sync went.
ALTER TABLE "Device" ADD COLUMN "appVersion" TEXT;
ALTER TABLE "Device" ADD COLUMN "flavor" TEXT;
ALTER TABLE "Device" ADD COLUMN "lastSyncAt" TIMESTAMPTZ(6);
ALTER TABLE "Device" ADD COLUMN "lastSyncOk" BOOLEAN;
ALTER TABLE "Device" ADD COLUMN "lastSyncError" TEXT;
