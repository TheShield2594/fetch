import { Prisma } from "@prisma/client";

export const P2002 = new Prisma.PrismaClientKnownRequestError("Unique constraint failed", {
  code: "P2002",
  clientVersion: "test",
});

/** What Prisma throws when an `update`'s `where` matches no row. */
export const recordNotFound = () =>
  new Prisma.PrismaClientKnownRequestError("No record was found for an update.", {
    code: "P2025",
    clientVersion: "test",
  });
