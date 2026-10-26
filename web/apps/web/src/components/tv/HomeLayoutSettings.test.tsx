import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { HomeLayoutSettings } from "./HomeLayoutSettings";
import type { HomeRow } from "../../api";

const PROFILE = { id: "11111111-1111-4111-8111-111111111111", name: "Alex", hasPin: false };

vi.mock("../../hooks/useProfile", () => ({ useProfile: () => ({ profile: PROFILE }) }));

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return { ...actual, api: { getHomeLayout: vi.fn(), saveHomeLayout: vi.fn() } };
});

const { api, ApiError } = await import("../../api");
const getHomeLayout = vi.mocked(api.getHomeLayout);
const saveHomeLayout = vi.mocked(api.saveHomeLayout);

const row = (over: Partial<HomeRow>): HomeRow => ({
  key: "builtin:continue_watching",
  title: "Continue Watching",
  hidden: false,
  source: "builtin",
  addonName: null,
  type: null,
  available: true,
  ...over,
});

const ROWS = [
  row({}),
  row({ key: "addon:com.cinemeta:movie:top", title: "Popular", source: "addon", addonName: "Cinemeta", type: "movie" }),
  row({ key: "list:l1", title: "Watchlist", source: "list" }),
];

async function renderLayout() {
  render(<HomeLayoutSettings />);
  await waitFor(() => expect(screen.queryByText("Loading Home rows...")).not.toBeInTheDocument());
}

describe("HomeLayoutSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getHomeLayout.mockResolvedValue({ rows: ROWS, version: 3, updatedAt: null });
  });

  it("saves a new order and hidden rows against the version it loaded", async () => {
    saveHomeLayout.mockImplementation(async (_profileId, rows) => ({
      rows: rows.map((r) => ({ ...ROWS.find((x) => x.key === r.key)!, hidden: r.hidden })),
      version: 4,
      updatedAt: "2026-10-26T12:00:00Z",
    }));
    await renderLayout();

    expect(screen.getByRole("button", { name: /Save layout/ })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Move Watchlist up" }));
    await userEvent.click(screen.getByRole("switch", { name: "Show Popular on Home" }));
    await userEvent.click(screen.getByRole("button", { name: /Save layout/ }));

    expect(saveHomeLayout).toHaveBeenCalledWith(
      PROFILE.id,
      [
        { key: "builtin:continue_watching", hidden: false, title: "Continue Watching" },
        { key: "list:l1", hidden: false, title: "Watchlist" },
        { key: "addon:com.cinemeta:movie:top", hidden: true, title: "Popular" },
      ],
      3
    );
    expect(await screen.findByText(/Saved\./)).toBeInTheDocument();
  });

  it("says when the layout changed elsewhere, and reloads on request", async () => {
    saveHomeLayout.mockRejectedValue(new ApiError("changed", 412, "version_conflict"));
    await renderLayout();
    await userEvent.click(screen.getByRole("button", { name: "Move Watchlist up" }));
    await userEvent.click(screen.getByRole("button", { name: /Save layout/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("changed on another device");

    await userEvent.click(screen.getByRole("button", { name: "Load the latest" }));
    expect(getHomeLayout).toHaveBeenCalledTimes(2);
  });

  it("marks a row it can't reach but keeps it", async () => {
    getHomeLayout.mockResolvedValue({
      rows: [row({ key: "addon:gone:movie:x", title: "Old row", source: "addon", available: false })],
      version: 1,
      updatedAt: null,
    });
    await renderLayout();
    expect(screen.getByText("Old row")).toBeInTheDocument();
    expect(screen.getByText("Not reachable from here")).toBeInTheDocument();
  });
});
