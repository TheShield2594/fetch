import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MdbListRatingsSettings } from "./MdbListRatingsSettings";

const PROFILE = { id: "11111111-1111-4111-8111-111111111111", name: "Alex", hasPin: false };

vi.mock("../../hooks/useProfile", () => ({ useProfile: () => ({ profile: PROFILE }) }));

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getTvSettings: vi.fn(),
      saveTvSettings: vi.fn(),
      getMdbListKeyStatus: vi.fn(),
      setMdbListKey: vi.fn(),
      removeMdbListKey: vi.fn(),
    },
  };
});

const { api, ApiError } = await import("../../api");
const getTvSettings = vi.mocked(api.getTvSettings);
const saveTvSettings = vi.mocked(api.saveTvSettings);

// What a TV synced: ratings on, Trakt hidden, and a setting this form doesn't edit.
const STORED = {
  version: 1,
  features: {
    mdblist_settings: {
      mdblist_enabled: { type: "boolean", value: true },
      mdblist_show_trakt: { type: "boolean", value: false },
      mdblist_show_on_hero: { type: "boolean", value: true },
    },
  },
};

async function renderSettings() {
  render(<MdbListRatingsSettings />);
  await waitFor(() => expect(screen.queryByText("Loading rating settings...")).not.toBeInTheDocument());
  await waitFor(() => expect(screen.queryByText("Checking the MDBList key...")).not.toBeInTheDocument());
}

describe("MdbListRatingsSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getTvSettings.mockResolvedValue({ settings: STORED, version: 4, updatedAt: null });
    saveTvSettings.mockImplementation(async (_id, settings) => ({ settings, version: 5, updatedAt: null }));
    vi.mocked(api.getMdbListKeyStatus).mockResolvedValue({ hasKey: false, signedIn: false });
  });

  it("shows the TV's choices: ratings on, Trakt hidden", async () => {
    await renderSettings();

    expect(screen.getByRole("switch", { name: "Show MDBList ratings on detail pages" })).toBeChecked();
    expect(screen.getByRole("switch", { name: "Trakt" })).not.toBeChecked();
    expect(screen.getByRole("switch", { name: "IMDb" })).toBeChecked();
  });

  it("saves a new order into the TV's keys, keeping the settings it doesn't edit", async () => {
    await renderSettings();

    await userEvent.click(screen.getByRole("button", { name: "Move Trakt up" }));
    await userEvent.click(screen.getByRole("button", { name: /save rating settings/i }));

    await waitFor(() => expect(saveTvSettings).toHaveBeenCalledTimes(1));
    const [profileId, document, version] = saveTvSettings.mock.calls[0]!;
    expect(profileId).toBe(PROFILE.id);
    expect(version).toBe(4);
    const feature = document.features?.mdblist_settings;
    expect(feature?.mdblist_rating_order).toEqual({
      type: "string",
      value: "trakt,imdb,tmdb,tomatoes,audience,letterboxd,metacritic,mal",
    });
    expect(feature?.mdblist_show_on_hero).toEqual({ type: "boolean", value: true });
    expect(await screen.findByText(/The TV picks this up/)).toBeInTheDocument();
  });

  it("says when the profile's MDBList sign-in stands in for a key", async () => {
    vi.mocked(api.getMdbListKeyStatus).mockResolvedValue({ hasKey: false, signedIn: true });
    await renderSettings();

    expect(screen.getByText("Using your MDBList sign-in")).toBeInTheDocument();
  });

  it("saves a pasted key, and shows MDBList's refusal of a bad one", async () => {
    vi.mocked(api.setMdbListKey)
      .mockRejectedValueOnce(new ApiError("MDBList didn't accept that API key", 400))
      .mockResolvedValueOnce({ hasKey: true, signedIn: false });
    await renderSettings();

    await userEvent.type(screen.getByLabelText("MDBList API key"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Save API key" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("MDBList didn't accept that API key");

    await userEvent.clear(screen.getByLabelText("MDBList API key"));
    await userEvent.type(screen.getByLabelText("MDBList API key"), " right-key ");
    await userEvent.click(screen.getByRole("button", { name: "Save API key" }));

    expect(await screen.findByText("API key saved")).toBeInTheDocument();
    expect(api.setMdbListKey).toHaveBeenLastCalledWith("right-key");
    expect(screen.getByRole("button", { name: /remove key/i })).toBeInTheDocument();
  });
});
