-- A kids profile's rules, carried with the profile so every paired TV gets
-- them. When they last changed decides between two writes (newer wins).
ALTER TABLE "Profile" ADD COLUMN "kidsRules" JSONB;
ALTER TABLE "Profile" ADD COLUMN "kidsRulesUpdatedAt" TIMESTAMPTZ(6);
