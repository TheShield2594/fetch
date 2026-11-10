-- An optional email address on each account: sign-in by email, password
-- resets, emailed invites, and notifications by email.
ALTER TABLE "Account" ADD COLUMN "email" TEXT;
ALTER TABLE "Account" ADD COLUMN "emailVerifiedAt" TIMESTAMPTZ(6);
ALTER TABLE "Account" ADD COLUMN "emailNotifications" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Account" ADD COLUMN "emailVerifyTokenHash" TEXT;
ALTER TABLE "Account" ADD COLUMN "emailVerifyExpiresAt" TIMESTAMPTZ(6);
ALTER TABLE "Account" ADD COLUMN "passwordResetTokenHash" TEXT;
ALTER TABLE "Account" ADD COLUMN "passwordResetExpiresAt" TIMESTAMPTZ(6);

CREATE UNIQUE INDEX "Account_email_key" ON "Account"("email");
CREATE UNIQUE INDEX "Account_emailVerifyTokenHash_key" ON "Account"("emailVerifyTokenHash");
CREATE UNIQUE INDEX "Account_passwordResetTokenHash_key" ON "Account"("passwordResetTokenHash");

-- The address an invite was emailed to, if it was.
ALTER TABLE "Invite" ADD COLUMN "email" TEXT;
