-- Personal access tokens (#645): a named, revocable credential an account
-- makes for a script or a Home Assistant integration, so it needs neither a
-- sign-in that expires nor API_TOKEN. Only the token's SHA-256 is stored.
CREATE TABLE "AccessToken" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "readOnly" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMPTZ(6),
    "expiresAt" TIMESTAMPTZ(6),
    "accountId" UUID NOT NULL,

    CONSTRAINT "AccessToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "AccessToken_tokenHash_key" ON "AccessToken"("tokenHash");
CREATE INDEX "AccessToken_accountId_idx" ON "AccessToken"("accountId");

ALTER TABLE "AccessToken" ADD CONSTRAINT "AccessToken_accountId_fkey"
    FOREIGN KEY ("accountId") REFERENCES "Account"("id") ON DELETE CASCADE ON UPDATE CASCADE;
