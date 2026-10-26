import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SimklSettings, simklSummaryText } from "./SimklSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getSimklStatus: vi.fn(),
      startSimklPin: vi.fn(),
      pollSimklPin: vi.fn(),
      syncSimkl: vi.fn(),
      disconnectSimkl: vi.fn(),
    },
  };
});

const { api } = await import("../../api");
const status = (over = {}) => ({
  configured: true,
  connected: false,
  username: null,
  lastSyncedAt: null,
  pending: null,
  ...over,
});
const PIN = { userCode: "AB12CD", verificationUrl: "https://simkl.com/pin", expiresAt: "2099-01-01T00:00:00Z", interval: 1 };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getSimklStatus).mockResolvedValue(status());
  vi.mocked(api.startSimklPin).mockResolvedValue(PIN);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SimklSettings", () => {
  it("shows the code to enter, then the connection once it is entered", async () => {
    vi.mocked(api.pollSimklPin).mockResolvedValue({ result: "connected", status: status({ connected: true, username: "sam" }) });
    const user = userEvent.setup();
    render(<SimklSettings />);

    await user.click(await screen.findByRole("button", { name: /connect simkl/i }));

    expect(screen.getByLabelText("Simkl code")).toHaveTextContent("AB12CD");
    expect(screen.getByRole("link", { name: /simkl\.com\/pin/ })).toHaveAttribute("href", PIN.verificationUrl);
    await waitFor(() => expect(screen.getByText("Connected as sam")).toBeInTheDocument(), { timeout: 3000 });
  });

  it("says when Simkl isn't set up on the server, and offers no sign-in", async () => {
    vi.mocked(api.getSimklStatus).mockResolvedValue(status({ configured: false }));
    render(<SimklSettings />);

    expect(await screen.findByRole("alert")).toHaveTextContent(/SIMKL_CLIENT_ID/);
    expect(screen.getByRole("button", { name: /connect simkl/i })).toBeDisabled();
  });

  it("syncs a connected profile and says what came in", async () => {
    vi.mocked(api.getSimklStatus).mockResolvedValue(status({ connected: true, username: "sam" }));
    vi.mocked(api.syncSimkl).mockResolvedValue({ movies: 2, episodes: 1, watchlist: 0, skipped: 0, full: false });
    const user = userEvent.setup();
    render(<SimklSettings />);

    await user.click(await screen.findByRole("button", { name: /sync now/i }));

    expect(api.syncSimkl).toHaveBeenCalledWith(false);
    expect(await screen.findByRole("status")).toHaveTextContent("Brought in 2 films, 1 episode.");
  });
});

describe("simklSummaryText", () => {
  it("says so when nothing was new", () => {
    expect(simklSummaryText({ movies: 0, episodes: 0, watchlist: 0, skipped: 3, full: false })).toBe("Nothing new on Simkl.");
  });
});

void act;
