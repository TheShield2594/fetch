-- The address that started a pairing, so pending pairings can be capped per client.
ALTER TABLE "DevicePairing" ADD COLUMN "requesterIp" TEXT;

CREATE INDEX "DevicePairing_requesterIp_idx" ON "DevicePairing"("requesterIp");
