-- Account security (#540, #541, #542): which browser each sign-in is, a short
-- activity log, a flag for a password someone else set, and the profile a TV
-- last reported using.
ALTER TABLE "Account" ADD COLUMN "mustChangePassword" BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE "Session" ADD COLUMN "userAgent" TEXT;
ALTER TABLE "Session" ADD COLUMN "ipAddress" TEXT;

ALTER TABLE "Device" ADD COLUMN "lastProfileId" UUID;

CREATE TABLE "AccountEvent" (
    "id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "detail" TEXT,
    "ipAddress" TEXT,
    "userAgent" TEXT,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "accountId" UUID NOT NULL,

    CONSTRAINT "AccountEvent_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "AccountEvent_accountId_createdAt_idx" ON "AccountEvent"("accountId", "createdAt");

ALTER TABLE "AccountEvent" ADD CONSTRAINT "AccountEvent_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;
