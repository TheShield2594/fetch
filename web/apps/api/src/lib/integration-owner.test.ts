import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyRequest } from "fastify";
import type { AuthPrincipal } from "./request-auth.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";

const prismaMock = {
  profile: { findUnique: vi.fn() },
};
vi.mock("./prisma.js", () => ({ prisma: prismaMock }));
vi.mock("./sessions.js", () => ({ findAdminAccountId: async () => "admin-account" }));

const request = (auth: AuthPrincipal | undefined, headers: Record<string, string> = {}, profileId?: string) =>
  ({ auth, headers, ...(profileId ? { profileId } : {}) }) as unknown as FastifyRequest;

describe("ownerForRequest", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    prismaMock.profile.findUnique.mockResolvedValue({ accountId: "friend-account" });
  });

  it("is the signed-in account, whatever profile is named", async () => {
    const { ownerForRequest } = await import("./integration-owner.js");
    const session: AuthPrincipal = { kind: "session", sessionId: "s", accountId: "own-account", isAdmin: false };
    expect(await ownerForRequest(request(session, { "x-profile-id": PROFILE_ID }))).toBe("own-account");
    expect(prismaMock.profile.findUnique).not.toHaveBeenCalled();
  });

  // The Stremio addon calls with API_TOKEN for every install URL it serves.
  it("is the named profile's account for API_TOKEN", async () => {
    const { ownerForRequest } = await import("./integration-owner.js");
    expect(await ownerForRequest(request({ kind: "token" }, { "x-profile-id": PROFILE_ID }))).toBe("friend-account");
    expect(await ownerForRequest(request({ kind: "token" }, {}, PROFILE_ID))).toBe("friend-account");
  });

  it("falls back to the admin for API_TOKEN naming no profile, or one that doesn't exist", async () => {
    const { ownerForRequest } = await import("./integration-owner.js");
    expect(await ownerForRequest(request({ kind: "token" }))).toBe("admin-account");
    expect(await ownerForRequest(request({ kind: "token" }, { "x-profile-id": "not-a-uuid" }))).toBe("admin-account");
    prismaMock.profile.findUnique.mockResolvedValue(null);
    expect(await ownerForRequest(request({ kind: "token" }, { "x-profile-id": PROFILE_ID }))).toBe("admin-account");
  });

  it("does not let an unauthenticated request pick an account by header", async () => {
    const { ownerForRequest } = await import("./integration-owner.js");
    expect(await ownerForRequest(request(undefined, { "x-profile-id": PROFILE_ID }))).toBe("admin-account");
  });
});
