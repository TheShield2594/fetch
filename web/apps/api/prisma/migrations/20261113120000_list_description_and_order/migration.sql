-- A list can carry a short description, and its items a manual position.
-- `position` is NULL for every existing row and for anything added to a list
-- that has never been arranged: "never arranged" sorts after the arranged
-- rows, newest first, so nothing here needs backfilling.
ALTER TABLE "List" ADD COLUMN "description" TEXT;

ALTER TABLE "ListItem" ADD COLUMN "position" INTEGER;
