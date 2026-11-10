import { beforeEach, describe, expect, it } from "vitest";
import { aiRecsCache, aiRecsCacheClear, aiRecsCacheDeleteForProfiles, trendingCache } from "./cache.js";

const MINE = "11111111-1111-4111-8111-111111111111";
const THEIRS = "22222222-2222-4222-8222-222222222222";
const entry = { data: [] };

describe("AI recommendation cache", () => {
  beforeEach(() => {
    aiRecsCache.clear();
    trendingCache.clear();
    aiRecsCache.set(`ai-recs:movie:15:${MINE}`, entry);
    aiRecsCache.set(`ai-recs:series:15:${THEIRS}`, entry);
    // The Stremio addon's AI catalog, which lives beside the shared catalogs.
    trendingCache.set(`ai-recs:movie:${MINE}`, entry);
    trendingCache.set(`ai-recs:movie:${THEIRS}`, entry);
    trendingCache.set("trending:movie:week", entry);
  });

  it("drops only the named profiles' recommendations, in both caches", () => {
    aiRecsCacheDeleteForProfiles([MINE]);

    expect([...aiRecsCache.keys()]).toEqual([`ai-recs:series:15:${THEIRS}`]);
    expect([...trendingCache.keys()].sort()).toEqual([`ai-recs:movie:${THEIRS}`, "trending:movie:week"]);
  });

  it("drops nothing for an empty list of profiles", () => {
    aiRecsCacheDeleteForProfiles([]);

    expect(aiRecsCache.size).toBe(2);
    expect(trendingCache.size).toBe(3);
  });

  it("clears every profile's for the install-wide jobs, and leaves the shared catalogs", () => {
    aiRecsCacheClear();

    expect(aiRecsCache.size).toBe(0);
    expect([...trendingCache.keys()]).toEqual(["trending:movie:week"]);
  });
});
