import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  deriveShareKey,
  isLiveGeneration,
  isShareLinkPath,
  nextGeneration,
  retiredGeneration,
  shareKeyMatches,
} from "./share-keys.js";

const ID = "11111111-1111-4111-8111-111111111111";

describe("share keys", () => {
  const original = process.env.API_TOKEN;
  beforeEach(() => {
    process.env.API_TOKEN = "a-test-token";
  });
  afterEach(() => {
    process.env.API_TOKEN = original;
  });

  it("matches the key of the current generation and no other", () => {
    const key = deriveShareKey("list", ID, 1)!;

    expect(shareKeyMatches(key, "list", ID, 1)).toBe(true);
    expect(shareKeyMatches(key, "list", ID, 2)).toBe(false);
    expect(shareKeyMatches(key, "calendar-feed", ID, 1)).toBe(false);
    expect(shareKeyMatches(key, "list", "22222222-2222-4222-8222-222222222222", 1)).toBe(false);
    expect(shareKeyMatches("short", "list", ID, 1)).toBe(false);
    expect(shareKeyMatches(undefined, "list", ID, 1)).toBe(false);
  });

  it("matches nothing while the link is off or was never on", () => {
    const key = deriveShareKey("list", ID, 1)!;

    expect(shareKeyMatches(key, "list", ID, null)).toBe(false);
    expect(shareKeyMatches(key, "list", ID, retiredGeneration(1))).toBe(false);
  });

  it("never returns to a generation an address was made from", () => {
    const first = nextGeneration(null);
    const off = retiredGeneration(first);
    const second = nextGeneration(off);

    expect([first, off, second]).toEqual([1, -1, 2]);
    expect(isLiveGeneration(off)).toBe(false);
    expect(nextGeneration(second)).toBe(3);
  });

  it("changes with API_TOKEN", () => {
    const key = deriveShareKey("list", ID, 1)!;
    process.env.API_TOKEN = "another-token";

    expect(shareKeyMatches(key, "list", ID, 1)).toBe(false);
  });

  it("recognises only the two public link shapes", () => {
    const key = deriveShareKey("list", ID, 1)!;

    expect(isShareLinkPath(`/calendar/feed/${ID}/${key}/calendar.ics`)).toBe(true);
    expect(isShareLinkPath(`/shared/lists/${ID}/${key}?x=1`)).toBe(true);
    expect(isShareLinkPath(`/calendar/feed`)).toBe(false);
    expect(isShareLinkPath(`/shared/lists/${ID}/${key}/items`)).toBe(false);
    expect(isShareLinkPath(`/lists/${ID}`)).toBe(false);
  });
});
