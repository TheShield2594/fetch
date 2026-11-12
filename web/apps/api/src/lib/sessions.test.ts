import { beforeEach, describe, expect, it, vi } from "vitest";

const prismaMock = { session: { deleteMany: vi.fn() } };
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));

const { sweepExpiredSessions } = await import("./sessions.js");

describe("sweepExpiredSessions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.session.deleteMany.mockResolvedValue({ count: 2 });
  });

  it("deletes the sessions that have expired, and no others", async () => {
    const now = new Date("2026-11-12T12:00:00Z");

    await expect(sweepExpiredSessions(now)).resolves.toBe(2);

    expect(prismaMock.session.deleteMany).toHaveBeenCalledWith({ where: { expiresAt: { lte: now } } });
  });
});
