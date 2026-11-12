-- The web app's theme, kept on the profile so it follows the person to another
-- browser and two profiles in one browser can each have their own. NULL until
-- a profile picks one: the browser's own choice stands until then.
ALTER TABLE "Profile" ADD COLUMN "webTheme" TEXT;
