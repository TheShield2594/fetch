-- Optional two-factor sign-in (#564). Off for every existing account: all the
-- columns start empty, and an account without a secret signs in as before.
--
-- "totpSecret" and "totpPendingSecret" are encrypted at rest like integration
-- credentials (lib/secret-box.ts). "totpRecoveryCodes" holds SHA-256 hashes of
-- the one-time recovery codes, never the codes.
ALTER TABLE "Account" ADD COLUMN "totpSecret" TEXT;
ALTER TABLE "Account" ADD COLUMN "totpPendingSecret" TEXT;
ALTER TABLE "Account" ADD COLUMN "totpEnabledAt" TIMESTAMPTZ(6);
ALTER TABLE "Account" ADD COLUMN "totpLastUsedStep" INTEGER;
ALTER TABLE "Account" ADD COLUMN "totpRecoveryCodes" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
