import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TvOverview } from "./TvOverview";
import type { SyncOverview } from "../../api";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return { ...actual, api: { getSyncOverview: vi.fn() } };
});

const { api } = await import("../../api");
const getSyncOverview = vi.mocked(api.getSyncOverview);

const OVERVIEW: SyncOverview = {
  profiles: [
    {
      id: "p1",
      name: "Alex",
      addonsFromPrimary: false,
      counts: { addons: 3, enabledAddons: 2, lists: 4, resumePoints: 1, watchedItems: 120 },
      tvSettingsUpdatedAt: null,
      homeLayoutUpdatedAt: null,
    },
  ],
  devices: [
    {
      id: "d1",
      name: "Living room",
      lastSeenAt: new Date().toISOString(),
      appVersion: "1.4.0",
      flavor: "playstore",
      lastSyncAt: new Date().toISOString(),
      lastSyncOk: false,
      lastSyncError: "The Fetch website answered 500",
    },
    {
      id: "d2",
      name: "Bedroom",
      lastSeenAt: null,
      appVersion: null,
      flavor: null,
      lastSyncAt: null,
      lastSyncOk: null,
      lastSyncError: null,
    },
  ],
  server: { database: { ok: true, latencyMs: 4 }, jobs: [] },
};

describe("TvOverview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getSyncOverview.mockResolvedValue(OVERVIEW);
  });

  it("shows each TV's build and flags one whose sync is failing, with its error", async () => {
    render(<TvOverview />);
    expect(await screen.findByText("Living room")).toBeInTheDocument();
    expect(screen.getByText("v1.4.0 · Play Store")).toBeInTheDocument();
    expect(screen.getByText("Last sync failed")).toBeInTheDocument();
    expect(screen.getByText("The Fetch website answered 500")).toBeInTheDocument();
    expect(screen.getByText("Hasn't synced yet")).toBeInTheDocument();
  });

  it("shows per-profile counts, enabled of installed for addons", async () => {
    render(<TvOverview />);
    expect(await screen.findByText("Alex")).toBeInTheDocument();
    expect(screen.getByText("2/3")).toBeInTheDocument();
    expect(screen.getByText("120")).toBeInTheDocument();
  });
});
