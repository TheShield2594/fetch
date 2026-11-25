import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildRouteApp } from "../lib/test-fixtures/route-app.js";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";

const prismaMock = { profile: { findUnique: vi.fn(), update: vi.fn() } };
vi.mock("../lib/prisma.js", () => ({ prisma: prismaMock }));
vi.mock("../lib/profile.js", () => ({
  resolveProfile: async (request: { profileId?: string }) => {
    request.profileId = PROFILE_ID;
  },
}));
const getCalendar = vi.fn();
vi.mock("../lib/calendar.js", () => ({ getCalendar: (...a: unknown[]) => getCalendar(...a) }));
vi.mock("../lib/integration-owner.js", () => ({ accountOwnerForProfile: async () => "owner-1" }));
const getSpoilerProtection = vi.fn();
vi.mock("../lib/settings.js", () => ({ getSpoilerProtection: (...a: unknown[]) => getSpoilerProtection(...a) }));

const buildApp = () => buildRouteApp(() => import("./calendar-feed.js"));
const keyFor = async (generation: number) =>
  (await import("../lib/share-keys.js")).deriveShareKey("calendar-feed", PROFILE_ID, generation)!;
const feedUrl = async (generation: number) => `/calendar/feed/${PROFILE_ID}/${await keyFor(generation)}/calendar.ics`;

describe("calendar feed", () => {
  const original = process.env.API_TOKEN;
  beforeEach(() => {
    vi.clearAllMocks();
    process.env.API_TOKEN = "a-test-token";
    prismaMock.profile.update.mockResolvedValue({});
    getCalendar.mockResolvedValue([]);
    getSpoilerProtection.mockResolvedValue(true);
  });
  afterEach(() => {
    process.env.API_TOKEN = original;
  });

  it("is off until the profile turns it on", async () => {
    prismaMock.profile.findUnique.mockResolvedValue({ calendarFeedGeneration: null });

    const response = await (await buildApp()).inject({ method: "GET", url: "/calendar/feed" });

    expect(response.json()).toEqual({ enabled: false, path: null });
  });

  it("turns on with an address, and a second POST replaces it", async () => {
    prismaMock.profile.findUnique
      .mockResolvedValueOnce({ calendarFeedGeneration: null })
      .mockResolvedValueOnce({ calendarFeedGeneration: 1 });

    const response = await (await buildApp()).inject({ method: "POST", url: "/calendar/feed" });

    expect(prismaMock.profile.update).toHaveBeenCalledWith({ where: { id: PROFILE_ID }, data: { calendarFeedGeneration: 1 } });
    expect(response.json()).toEqual({ enabled: true, path: await feedUrl(1) });
  });

  it("turns off without forgetting which addresses were used", async () => {
    prismaMock.profile.findUnique.mockResolvedValue({ calendarFeedGeneration: 2 });

    const response = await (await buildApp()).inject({ method: "DELETE", url: "/calendar/feed" });

    expect(response.statusCode).toBe(204);
    expect(prismaMock.profile.update).toHaveBeenCalledWith({ where: { id: PROFILE_ID }, data: { calendarFeedGeneration: -2 } });
  });

  it("serves the profile's calendar as text/calendar, under its account's spoiler setting", async () => {
    prismaMock.profile.findUnique.mockResolvedValue({ name: "Alex", calendarFeedGeneration: 1 });
    getCalendar.mockResolvedValue([
      {
        kind: "episode", seriesImdbId: "tt1", seriesName: "Severance", poster: null, still: null, backdrop: null,
        season: 2, episode: 3, episodeName: null, airDate: "2026-12-01", airsAt: null, watched: false, overview: null,
      },
    ]);

    const response = await (await buildApp()).inject({ method: "GET", url: await feedUrl(1) });

    expect(response.statusCode).toBe(200);
    expect(response.headers["content-type"]).toBe("text/calendar; charset=utf-8");
    expect(response.body).toContain("X-WR-CALNAME:Fetch · Alex");
    expect(response.body).toContain("SUMMARY:Severance S02E03");
    expect(getSpoilerProtection).toHaveBeenCalledWith("owner-1");
    expect(getCalendar).toHaveBeenCalledWith(PROFILE_ID, expect.objectContaining({ days: 90 }), true, expect.anything());
  });

  it.each([
    ["a replaced address", 2],
    ["a feed turned off", -1],
    ["a feed never turned on", null],
  ])("404s for %s", async (_label, generation) => {
    prismaMock.profile.findUnique.mockResolvedValue({ name: "Alex", calendarFeedGeneration: generation });

    const response = await (await buildApp()).inject({ method: "GET", url: await feedUrl(1) });

    expect(response.statusCode).toBe(404);
    expect(getCalendar).not.toHaveBeenCalled();
  });
});
