-- Accounts: people who sign in to the website. Each account owns its profiles,
-- and through them everything a profile owns. There is no public sign-up: the
-- first account is created with API_TOKEN and is the admin; every other one
-- redeems a single-use invite.
--
-- Session tokens and invite codes are stored only as SHA-256 digests.
-- Profile.accountId is nullable so existing installs migrate without a choice
-- being made for them: their profiles are claimed by the admin account when it
-- is created.
CREATE TABLE "Account" (
    "id" UUID NOT NULL,
    "username" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "isAdmin" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Account_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Account_username_key" ON "Account"("username");

CREATE TABLE "Session" (
    "id" UUID NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "accountId" UUID NOT NULL,

    CONSTRAINT "Session_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Session_tokenHash_key" ON "Session"("tokenHash");

CREATE INDEX "Session_accountId_idx" ON "Session"("accountId");

ALTER TABLE "Session" ADD CONSTRAINT "Session_accountId_fkey"
    FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "Invite" (
    "id" UUID NOT NULL,
    "codeHash" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "usedAt" TIMESTAMPTZ(6),
    "createdById" UUID,
    "usedByAccountId" UUID,

    CONSTRAINT "Invite_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Invite_codeHash_key" ON "Invite"("codeHash");

CREATE UNIQUE INDEX "Invite_usedByAccountId_key" ON "Invite"("usedByAccountId");

ALTER TABLE "Invite" ADD CONSTRAINT "Invite_createdById_fkey"
    FOREIGN KEY ("createdById") REFERENCES "Account"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "Invite" ADD CONSTRAINT "Invite_usedByAccountId_fkey"
    FOREIGN KEY ("usedByAccountId") REFERENCES "Account"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "Profile" ADD COLUMN "accountId" UUID;

CREATE INDEX "Profile_accountId_idx" ON "Profile"("accountId");

ALTER TABLE "Profile" ADD CONSTRAINT "Profile_accountId_fkey"
    FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;
