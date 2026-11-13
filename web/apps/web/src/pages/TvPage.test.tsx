import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { ToastProvider } from "../hooks/useToast";
import { TvPage } from "./TvPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, api: { getDevices: vi.fn().mockResolvedValue({ devices: [] }), confirmPairing: vi.fn() } };
});

// Each has its own suite; this one is about the page that gathers them.
vi.mock("../components/tv/TvOverview", () => ({ TvOverview: () => <p>tv overview body</p> }));
vi.mock("../components/tv/TvAddonsSettings", () => ({ TvAddonsSettings: () => <p>tv addons body</p> }));
vi.mock("../components/tv/HomeLayoutSettings", () => ({ HomeLayoutSettings: () => <p>home rows body</p> }));
vi.mock("../components/tv/TvSettingsForm", () => ({ TvSettingsForm: () => <p>tv settings body</p> }));
vi.mock("../components/tv/HomeFoldersSettings", () => ({ HomeFoldersSettings: () => <p>home folders body</p> }));

const renderAt = (url = "/tv") =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <ToastProvider>
        <TvPage />
      </ToastProvider>
    </MemoryRouter>
  );

describe("TvPage", () => {
  // #527: the empty state is where pairing happens.
  it("offers the pairing box in its empty state", async () => {
    renderAt();

    expect(screen.getByRole("heading", { level: 1, name: "TV" })).toBeInTheDocument();
    expect(await screen.findByText("No TV is signed in yet")).toBeInTheDocument();
    expect(screen.getByLabelText("Code shown on the TV")).toBeInTheDocument();
  });

  it("gathers every TV setting under one destination", () => {
    renderAt();

    for (const [name, section] of [
      ["Home rows", "tv-home"],
      ["Home folders", "home-folders"],
      ["Addons", "tv-addons"],
      ["Appearance", "tv-appearance"],
      ["Layout", "tv-layout"],
      ["Playback", "tv-settings"],
      ["Subtitles", "tv-subtitles"],
      ["Streams", "tv-streams"],
      ["Content and metadata", "tv-content"],
      ["Synced data", "tv-overview"],
    ]) {
      expect(screen.getByRole("link", { name: new RegExp(`^${name}`) })).toHaveAttribute("href", `/tv?section=${section}`);
    }
  });

  it("opens one section by its address, with the way back", () => {
    renderAt("/tv?section=home-folders");

    expect(screen.getByRole("region", { name: "Home folders" })).toHaveTextContent("home folders body");
    expect(screen.getByRole("link", { name: "TV" })).toHaveAttribute("href", "/tv");
  });
});
