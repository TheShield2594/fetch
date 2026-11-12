import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = { kV: { deleteMany: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const { sweepStaleAttempts } = await import("./attempt-lockout.js");

describe("sweepStaleAttempts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.kV.deleteMany.mockResolvedValue({ count: 3 });
  });

  // Only this module's own rows, and only once they are past the window in
  // which they still count: other KV rows (settings, job status) are untouched.
  it("deletes sign-in and PIN attempt records nobody has added to for a day", async () => {
    const now = new Date("2026-11-12T12:00:00Z");

    await expect(sweepStaleAttempts(now)).resolves.toBe(3);

    expect(prismaMock.kV.deleteMany).toHaveBeenCalledWith({
      where: {
        OR: [{ key: { startsWith: "loginattempts:" } }, { key: { startsWith: "pinattempts:" } }],
        updatedAt: { lt: new Date("2026-11-11T12:00:00Z") },
      },
    });
  });
});
