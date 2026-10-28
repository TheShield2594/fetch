import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MediaServersSettings, mediaServerSummaryText } from "./MediaServersSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getMediaServers: vi.fn(),
      startPlexLink: vi.fn(),
      pollPlexLink: vi.fn(),
      signInMediaServer: vi.fn(),
      setMediaServerImport: vi.fn(),
      setMediaServerLibraries: vi.fn(),
      refreshMediaServerLibraries: vi.fn(),
      syncMediaServer: vi.fn(),
      removeMediaServer: vi.fn(),
    },
  };
});

const { api } = await import("../../api");
const SERVER = {
  id: "c1",
  provider: "jellyfin" as const,
  serverName: "Den",
  address: "https://jf.example.com",
  userName: "sam",
  importWatchState: true,
  lastSyncedAt: null,
  libraries: null,
  connectedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getMediaServers).mockResolvedValue({ providers: [], servers: [], plexLink: null });
});

describe("MediaServersSettings", () => {
  it("signs in to Plex with a link code and lists the servers it finds", async () => {
    vi.mocked(api.startPlexLink).mockResolvedValue({ code: "ABCD", linkUrl: "https://plex.tv/link", expiresAt: "2099-01-01T00:00:00Z", interval: 1 });
    vi.mocked(api.pollPlexLink).mockResolvedValue({ result: "connected", servers: [{ ...SERVER, id: "p1", provider: "plex", serverName: "Basement" }] });
    const user = userEvent.setup();
    render(<MediaServersSettings />);

    await user.click(await screen.findByRole("button", { name: "Sign in to Plex" }));

    expect(screen.getByLabelText("Plex code")).toHaveTextContent("ABCD");
    await waitFor(() => expect(screen.getByText("Basement (Plex)")).toBeInTheDocument(), { timeout: 3000 });
  });

  it("signs in to Jellyfin with an address, user name and password", async () => {
    vi.mocked(api.signInMediaServer).mockResolvedValue({ serverName: "Den", userName: "sam" });
    const user = userEvent.setup();
    render(<MediaServersSettings />);

    await user.type(await screen.findByLabelText("Jellyfin server address"), "https://jf.example.com");
    await user.type(screen.getByLabelText("Jellyfin user name"), "sam");
    await user.type(screen.getByLabelText("Jellyfin password"), "hunter2");
    vi.mocked(api.getMediaServers).mockResolvedValue({ providers: [], servers: [SERVER], plexLink: null });
    await user.click(screen.getByRole("button", { name: "Sign in to Jellyfin" }));

    expect(api.signInMediaServer).toHaveBeenCalledWith("jellyfin", "https://jf.example.com", "sam", "hunter2");
    expect(await screen.findByText("Den (Jellyfin)")).toBeInTheDocument();
  });

  it("syncs, toggles importing and removes a server", async () => {
    vi.mocked(api.getMediaServers).mockResolvedValue({ providers: [], servers: [SERVER], plexLink: null });
    vi.mocked(api.syncMediaServer).mockResolvedValue({ movies: 3, episodes: 0, resumePoints: 1, skipped: 0 });
    vi.mocked(api.setMediaServerImport).mockResolvedValue({ ...SERVER, importWatchState: false });
    vi.mocked(api.removeMediaServer).mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<MediaServersSettings />);

    await user.click(await screen.findByRole("button", { name: "Sync Den (Jellyfin)" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Brought in 3 films, 1 to resume.");

    await user.click(screen.getByRole("switch"));
    expect(api.setMediaServerImport).toHaveBeenCalledWith("c1", false);

    await user.click(screen.getByRole("button", { name: "Remove Den (Jellyfin)" }));
    expect(api.removeMediaServer).toHaveBeenCalledWith("c1");
  });

  it("lists the server's libraries and saves which ones a sync reads", async () => {
    const libraries = [
      { id: "lib-films", name: "Films", kind: "movies" as const, selected: true },
      { id: "lib-kids", name: "Kids", kind: "shows" as const, selected: true },
    ];
    vi.mocked(api.getMediaServers).mockResolvedValue({ providers: [], servers: [{ ...SERVER, libraries }], plexLink: null });
    vi.mocked(api.setMediaServerLibraries).mockResolvedValue({ ...SERVER, libraries });
    const user = userEvent.setup();
    render(<MediaServersSettings />);

    await user.click(await screen.findByRole("checkbox", { name: /Kids/ }));

    expect(api.setMediaServerLibraries).toHaveBeenCalledWith("c1", ["lib-films"]);
  });

  it("asks the server for its libraries when they haven't been listed", async () => {
    vi.mocked(api.getMediaServers).mockResolvedValue({ providers: [], servers: [SERVER], plexLink: null });
    vi.mocked(api.refreshMediaServerLibraries).mockResolvedValue(SERVER);
    const user = userEvent.setup();
    render(<MediaServersSettings />);

    expect(await screen.findByText("All of them, until the server lists them.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Check the server for libraries" }));

    expect(api.refreshMediaServerLibraries).toHaveBeenCalledWith("c1");
  });
});

describe("mediaServerSummaryText", () => {
  it("says so when nothing was new", () => {
    expect(mediaServerSummaryText({ movies: 0, episodes: 0, resumePoints: 0, skipped: 2 })).toBe("Nothing new on the server.");
  });
});
