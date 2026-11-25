import { describe, expect, it, vi } from "vitest";
import { runtimeConfig } from "../api";
import { apiUrl, sharedListFromPath, sharedListPageUrl } from "./shareLinks";

const ID = "22222222-2222-4222-8222-222222222222";
const KEY = "k".repeat(43);

describe("share links", () => {
  it("makes an absolute API address whether the API is a path here or its own host", () => {
    vi.spyOn(runtimeConfig, "getApiBase").mockReturnValueOnce("/api");
    expect(apiUrl("/calendar/feed/x/y/calendar.ics")).toBe(`${window.location.origin}/api/calendar/feed/x/y/calendar.ics`);

    vi.spyOn(runtimeConfig, "getApiBase").mockReturnValueOnce("https://fetch.example/api/");
    expect(apiUrl("/shared/lists/a/b")).toBe("https://fetch.example/api/shared/lists/a/b");
  });

  it("turns the API's share path into the page to send, and back", () => {
    const page = sharedListPageUrl(`/shared/lists/${ID}/${KEY}`);

    expect(page).toBe(`${window.location.origin}/shared/list/${ID}/${KEY}`);
    expect(sharedListFromPath(new URL(page!).pathname)).toEqual({ listId: ID, key: KEY });
  });

  it("recognises no other page as a shared list", () => {
    expect(sharedListFromPath("/lists")).toBeNull();
    expect(sharedListFromPath(`/shared/list/${ID}/short`)).toBeNull();
    expect(sharedListPageUrl("/lists/abc")).toBeNull();
  });
});
