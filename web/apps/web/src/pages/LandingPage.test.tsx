import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { LandingPage, parseRelease } from "./LandingPage";

const RELEASE = {
  tag_name: "v1.9.1",
  html_url: "https://github.com/TheShield2594/fetch/releases/tag/v1.9.1",
  assets: [
    { name: "fetch-v1.9.1-arm64-v8a.apk", browser_download_url: "https://example.test/arm64.apk", size: 67_524_356 },
    { name: "fetch-v1.9.1-universal.apk", browser_download_url: "https://example.test/universal.apk", size: 213_825_780 },
    { name: "fetch-v1.9.1-linux-amd64.deb", browser_download_url: "https://example.test/fetch.deb", size: 71_697_256 },
  ],
};

const renderPage = () =>
  render(
    <MemoryRouter>
      <LandingPage />
    </MemoryRouter>
  );

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("parseRelease", () => {
  it("takes the universal APK, which installs on any Android TV", () => {
    expect(parseRelease(RELEASE)).toEqual({
      tag: "v1.9.1",
      apkUrl: "https://example.test/universal.apk",
      apkSizeMb: 214,
      pageUrl: RELEASE.html_url,
    });
  });

  it("returns nothing for an answer it doesn't recognise", () => {
    expect(parseRelease(null)).toBeNull();
    expect(parseRelease({ message: "API rate limit exceeded" })).toBeNull();
  });
});

describe("LandingPage", () => {
  it("links the latest APK and shows its version once GitHub answers", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(RELEASE), { status: 200 }));
    renderPage();

    const download = screen.getByRole("link", { name: "Download for Android TV" });
    await waitFor(() => expect(download).toHaveAttribute("href", "https://example.test/universal.apk"));
    expect(screen.getByRole("link", { name: "Linux build" })).toHaveAttribute("href", RELEASE.html_url);
    expect(screen.getByText(/v1\.9\.1 · universal APK, 214 MB/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Fetch v1.9.1" })).toBeInTheDocument();
  });

  it("falls back to the releases page when GitHub can't be reached", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("offline"));
    renderPage();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.getByRole("link", { name: "Download for Android TV" })).toHaveAttribute(
      "href",
      "https://github.com/TheShield2594/fetch/releases/latest"
    );
  });

  it("asks GitHub once an hour at most, not on every visit", async () => {
    window.sessionStorage.setItem(
      "fetch_landing_release",
      JSON.stringify({ at: Date.now(), release: parseRelease(RELEASE) })
    );
    const fetchMock = vi.spyOn(globalThis, "fetch");
    renderPage();

    expect(screen.getByRole("link", { name: "Download for Android TV" })).toHaveAttribute(
      "href",
      "https://example.test/universal.apk"
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("names every screenshot for a screen reader", () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("offline"));
    renderPage();

    for (const img of screen.getAllByRole("img")) {
      expect(img.getAttribute("alt")).toBeTruthy();
    }
  });
});
