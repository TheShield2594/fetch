import { describe, expect, it, vi } from "vitest";
import type { Prisma } from "@prisma/client";
import { recordNotFound } from "./test-fixtures/prisma-errors.js";

vi.mock("./prisma.js", () => ({ prisma: {} }));

const { writeProfileGuarded } = await import("./parental-write.js");

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const KIDS = { isKidsProfile: true, maxRating: "PG", allowUnrated: false };

describe("writeProfileGuarded", () => {
  it("asks for authority again after the limits change under the write", async () => {
    const update = vi
      .fn()
      .mockRejectedValueOnce(recordNotFound())
      .mockImplementation(async ({ data }: { data: unknown }) => data);
    const client = {
      profile: { findUnique: vi.fn().mockResolvedValue(KIDS), update },
    } as unknown as Prisma.TransactionClient;
    // Allowed on the first attempt; by the retry the acting profile has lost
    // the authority (it became a kids profile, say).
    const checkAuthority = vi.fn().mockResolvedValueOnce(true).mockResolvedValue(false);

    const result = await writeProfileGuarded(PROFILE_ID, { isKidsProfile: false, name: "Sam" }, { checkAuthority, client });

    expect(checkAuthority).toHaveBeenCalledTimes(2);
    expect(result).toMatchObject({ status: "ok", skipped: ["isKidsProfile"] });
    expect(update).toHaveBeenLastCalledWith(expect.objectContaining({ data: { name: "Sam" } }));
  });

  it("asks once when the first attempt lands", async () => {
    const client = {
      profile: {
        findUnique: vi.fn().mockResolvedValue(KIDS),
        update: vi.fn().mockImplementation(async ({ data }: { data: unknown }) => data),
      },
    } as unknown as Prisma.TransactionClient;
    const checkAuthority = vi.fn().mockResolvedValue(true);

    const result = await writeProfileGuarded(PROFILE_ID, { isKidsProfile: false }, { checkAuthority, client });

    expect(checkAuthority).toHaveBeenCalledTimes(1);
    expect(result).toMatchObject({ status: "ok", skipped: [] });
  });
});
