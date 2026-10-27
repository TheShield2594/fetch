-- A paired TV belongs to the account that confirmed its code, and sees only
-- that account's profiles. Nullable for devices paired before accounts
-- existed: the admin account claims them when it is created, as it does the
-- profiles.
ALTER TABLE "Device" ADD COLUMN "accountId" UUID;

CREATE INDEX "Device_accountId_idx" ON "Device"("accountId");

ALTER TABLE "Device" ADD CONSTRAINT "Device_accountId_fkey"
    FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "DevicePairing" ADD COLUMN "accountId" UUID;

ALTER TABLE "DevicePairing" ADD CONSTRAINT "DevicePairing_accountId_fkey"
    FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;
