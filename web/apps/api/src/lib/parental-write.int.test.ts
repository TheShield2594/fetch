import { describe, expect, it } from "vitest";
import type { FastifyRequest } from "fastify";
import { prisma } from "./prisma.js";
import { hasParentalAuthority } from "./profile.js";
import { writeProfileGuarded } from "./parental-write.js";
import "./test-fixtures/int-db.js";

// #678: authority to loosen a profile's parental limits is judged on the
// acting profile (its kids mode, its PIN), and the guarded write only pinned
// the target's limits. These run the check and the write the way
// `POST /import` and `PATCH /profiles/:id` do, in one transaction, against a
// real Postgres, with a second connection changing the acting profile
// mid-write.

const PIN_HASH = "scrypt$16384$8$1$00$00";

/** A request acting as `profileId`, with no profile token. */
const actingAs = (profileId: string) => ({ headers: { "x-profile-id": profileId } }) as unknown as FastifyRequest;

const setUp = async () => {
  const grownUp = await prisma.profile.create({ data: { name: "Grown-up" } });
  const kid = await prisma.profile.create({
    data: { name: "Kid", isKidsProfile: true, maxRating: "ALL_AGES", allowUnrated: false },
  });
  return { grownUp, kid };
};

/** A loosening of the kid's limits, written as an import writes it. */
const loosenKid = (kidId: string, grownUpId: string, beforeAuthority: () => Promise<void> = async () => {}) =>
  prisma.$transaction(async (tx) =>
    writeProfileGuarded(
      kidId,
      { maxRating: "ADULTS_ONLY", allowUnrated: true },
      {
        checkAuthority: async () => {
          await beforeAuthority();
          return hasParentalAuthority(actingAs(grownUpId), tx, { lockIn: tx });
        },
        client: tx,
      }
    )
  );

/** A change to a profile made in its own transaction, held open until `release` is called. */
const heldChange = (profileId: string, data: { pinHash?: string; isKidsProfile?: boolean }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  let applied!: () => void;
  const written = new Promise<void>((resolve) => (applied = resolve));
  const committed = prisma.$transaction(async (tx) => {
    await tx.profile.update({ where: { id: profileId }, data });
    applied();
    await gate;
  });
  return { written, release, committed };
};

/** Whether `promise` settles within `ms`. */
const settlesWithin = (promise: Promise<unknown>, ms: number) =>
  Promise.race([promise.then(() => true), new Promise<boolean>((resolve) => setTimeout(() => resolve(false), ms))]);

describe("a parental loosening and a change to the acting profile at the same time (#678)", () => {
  it("a PIN set on the acting profile before the check commits first, and the loosening doesn't land", async () => {
    const { grownUp, kid } = await setUp();
    const pin = heldChange(grownUp.id, { pinHash: PIN_HASH });
    await pin.written;

    const write = loosenKid(kid.id, grownUp.id);
    // The check waits for the PIN change rather than reading around it.
    expect(await settlesWithin(write, 300)).toBe(false);
    pin.release();
    await pin.committed;

    const result = await write;
    expect(result).toMatchObject({ status: "ok", skipped: expect.arrayContaining(["maxRating", "allowUnrated"]) });
    const stored = await prisma.profile.findUniqueOrThrow({ where: { id: kid.id } });
    expect(stored).toMatchObject({ maxRating: "ALL_AGES", allowUnrated: false });
  });

  it("a PIN set on the acting profile after authority was given waits for the write", async () => {
    const { grownUp, kid } = await setUp();
    let pinChange: Promise<unknown> | undefined;
    let pinSettledBeforeCommit = true;

    const result = await prisma.$transaction(async (tx) => {
      const authority = await hasParentalAuthority(actingAs(grownUp.id), tx, { lockIn: tx });
      // Someone sets a PIN on the grown-up between the check and the write.
      pinChange = prisma.profile.update({ where: { id: grownUp.id }, data: { pinHash: PIN_HASH } });
      pinSettledBeforeCommit = await settlesWithin(pinChange, 300);
      return writeProfileGuarded(
        kid.id,
        { maxRating: "ADULTS_ONLY" },
        { checkAuthority: async () => authority, client: tx }
      );
    });
    await pinChange;

    // The loosening was allowed while the profile had no PIN, and the PIN
    // only landed once it had committed: there was no moment the two overlapped.
    expect(pinSettledBeforeCommit).toBe(false);
    expect(result).toMatchObject({ status: "ok", skipped: [] });
    expect(await prisma.profile.findUniqueOrThrow({ where: { id: kid.id } })).toMatchObject({ maxRating: "ADULTS_ONLY" });
    expect(await prisma.profile.findUniqueOrThrow({ where: { id: grownUp.id } })).toMatchObject({ pinHash: PIN_HASH });
  });

  it("the acting profile becoming a kids profile mid-write stops the loosening", async () => {
    const { grownUp, kid } = await setUp();
    const kidsMode = heldChange(grownUp.id, { isKidsProfile: true });
    await kidsMode.written;

    const write = loosenKid(kid.id, grownUp.id);
    expect(await settlesWithin(write, 300)).toBe(false);
    kidsMode.release();
    await kidsMode.committed;

    expect(await write).toMatchObject({ status: "ok", skipped: expect.arrayContaining(["maxRating", "allowUnrated"]) });
    expect(await prisma.profile.findUniqueOrThrow({ where: { id: kid.id } })).toMatchObject({
      maxRating: "ALL_AGES",
      allowUnrated: false,
    });
  });

  it("a retry after a limit conflict asks for authority again and skips the loosening once it's gone", async () => {
    const { grownUp, kid } = await setUp();
    // What the request read and checked before its transaction: the kid's
    // limits a moment ago, a little looser than they are about to be.
    const initial = { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false };
    await prisma.profile.update({ where: { id: kid.id }, data: { maxRating: "PARENTAL_GUIDANCE" } });
    let asked = 0;

    const result = await prisma.$transaction(async (tx) =>
      writeProfileGuarded(
        kid.id,
        { maxRating: "ADULTS_ONLY" },
        {
          initial,
          client: tx,
          checkAuthority: async () => {
            asked += 1;
            if (asked === 1) {
              // Between the first attempt's check and its write, another
              // request tightens the kid's limits (forcing a retry), and the
              // grown-up's profile gets a PIN.
              await prisma.profile.update({ where: { id: kid.id }, data: { maxRating: "ALL_AGES" } });
              await prisma.profile.update({ where: { id: grownUp.id }, data: { pinHash: PIN_HASH } });
              return true;
            }
            return hasParentalAuthority(actingAs(grownUp.id), tx, { lockIn: tx });
          },
        }
      )
    );

    expect(asked).toBe(2);
    expect(result).toMatchObject({ status: "ok", skipped: ["maxRating"] });
    expect(await prisma.profile.findUniqueOrThrow({ where: { id: kid.id } })).toMatchObject({ maxRating: "ALL_AGES" });
  });

  it("an import into the grown-up's own profile, twice at once, doesn't deadlock", async () => {
    const grownUp = await prisma.profile.create({ data: { name: "Grown-up", maxRating: "ALL_AGES" } });
    const loosenOwn = (maxRating: string) =>
      prisma.$transaction(async (tx) =>
        writeProfileGuarded(
          grownUp.id,
          { maxRating },
          { checkAuthority: () => hasParentalAuthority(actingAs(grownUp.id), tx, { lockIn: tx }), client: tx }
        )
      );

    const results = await Promise.all([loosenOwn("ADULTS_ONLY"), loosenOwn("PARENTAL_GUIDANCE")]);

    expect(results.map((result) => result.status)).toEqual(["ok", "ok"]);
  });
});
