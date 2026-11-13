-- Which generation of a profile's Stremio key is current (#565). Replacing a
-- profile's addon URL in Settings bumps it, so a leaked URL can be retired
-- without rotating API_TOKEN. Existing profiles start at 0, which derives the
-- same key as before, so URLs already installed keep working.
ALTER TABLE "Profile" ADD COLUMN "stremioKeyGeneration" INTEGER NOT NULL DEFAULT 0;
