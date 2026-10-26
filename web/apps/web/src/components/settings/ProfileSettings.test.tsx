import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Profile } from "../../api";
import { ToastProvider } from "../../hooks/useToast";
import { ProfileSettings } from "./ProfileSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getProfiles: vi.fn(),
      updateProfile: vi.fn(),
      deleteProfile: vi.fn(),
      getProfileSummary: vi.fn(),
      createProfile: vi.fn(),
    },
  };
});

const setActiveProfile = vi.fn();
const openSwitcher = vi.fn();
let activeProfile: Profile | null = null;

vi.mock("../../hooks/useProfile", () => ({
  useProfile: () => ({ profile: activeProfile, setProfile: setActiveProfile, openSwitcher }),
}));

const { api, ApiError } = await import("../../api");
const getProfiles = vi.mocked(api.getProfiles);
const updateProfile = vi.mocked(api.updateProfile);
const deleteProfile = vi.mocked(api.deleteProfile);
const getProfileSummary = vi.mocked(api.getProfileSummary);
const createProfile = vi.mocked(api.createProfile);

const KID_SUMMARY = { watchEvents: 312, lists: 2, listItems: 41, playbackProgress: 6, ratings: 0, collections: 1 };

/** Opens the confirmation for `name` and waits for it to say what goes. */
const startDelete = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
  await user.click(screen.getByRole("button", { name: `Delete ${name}` }));
  return screen.findByRole("alertdialog", { name: `Delete ${name}?` });
};

const ALEX: Profile = { id: "p-alex", name: "Alex", hasPin: false };
const KID: Profile = { id: "p-kid", name: "Kid", hasPin: true };

const renderSettings = async () => {
  render(
    <ToastProvider>
      <ProfileSettings />
    </ToastProvider>
  );
  await waitFor(() => expect(screen.queryByText("Loading profiles...")).not.toBeInTheDocument());
};

/** The row for a profile — the name also appears in the "watching as" line. */
const rowFor = (name: string) => {
  const row = screen
    .getAllByText(name)
    .map((el) => el.closest("div.rounded-xl"))
    .find(Boolean);
  if (!row) throw new Error(`No profile row found for "${name}"`);
  return within(row as HTMLElement);
};

beforeEach(() => {
  activeProfile = ALEX;
  getProfiles.mockResolvedValue({ profiles: [ALEX, KID] });
  updateProfile.mockResolvedValue({ profile: ALEX });
  deleteProfile.mockResolvedValue(undefined as never);
  getProfileSummary.mockResolvedValue(KID_SUMMARY);
});

describe("ProfileSettings", () => {
  it("marks which profile is in use and which is locked", async () => {
    await renderSettings();

    expect(rowFor("Alex").getByText("Active")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove PIN for Kid" })).toBeInTheDocument();
    // No PIN to remove on the unprotected one.
    expect(screen.queryByRole("button", { name: "Remove PIN for Alex" })).not.toBeInTheDocument();
  });

  it("says so when the profiles can't be loaded", async () => {
    getProfiles.mockRejectedValue(new Error("Profiles unavailable"));
    await renderSettings();

    expect(screen.getByText("Profiles unavailable")).toBeInTheDocument();
  });

  it("won't offer to delete the only profile there is", async () => {
    getProfiles.mockResolvedValue({ profiles: [ALEX] });
    await renderSettings();

    expect(screen.getByRole("button", { name: "Delete Alex" })).toBeDisabled();
  });
});

describe("ProfileSettings renaming", () => {
  it("renames the profile and updates the one in use", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...ALEX, name: "Alexandra" } });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Rename Alex" }));
    await user.type(screen.getByLabelText("Rename Alex"), "andra");
    await user.click(screen.getByRole("button", { name: "Save name" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(ALEX.id, { name: "Alexandra" }));
    // The switcher's copy of the active profile has to move with it, or the
    // header goes on greeting a name that no longer exists.
    expect(setActiveProfile).toHaveBeenCalledWith({ ...ALEX, name: "Alexandra" });
    expect(await screen.findByText("Alexandra")).toBeInTheDocument();
  });

  it("doesn't call the server for a name that didn't change", async () => {
    const user = userEvent.setup();
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Rename Alex" }));
    await user.click(screen.getByRole("button", { name: "Save name" }));

    expect(updateProfile).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Rename Alex" })).toBeInTheDocument();
  });

  it("keeps the form up when the rename is refused", async () => {
    const user = userEvent.setup();
    updateProfile.mockRejectedValue(new Error("Name already taken"));
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Rename Alex" }));
    await user.type(screen.getByLabelText("Rename Alex"), "!");
    await user.click(screen.getByRole("button", { name: "Save name" }));

    expect(await screen.findByText("Name already taken")).toBeInTheDocument();
    expect(screen.getByLabelText("Rename Alex")).toHaveValue("Alex!");
  });
});

describe("ProfileSettings PINs", () => {
  it("sets a first PIN without asking for one that doesn't exist", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...ALEX, hasPin: true } });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Set PIN for Alex" }));
    expect(screen.queryByLabelText("Current PIN for Alex")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("PIN for Alex"), "1234");
    await user.click(screen.getByRole("button", { name: "Save PIN" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(ALEX.id, { pin: "1234" }));
    expect(await screen.findByText("PIN set for Alex")).toBeInTheDocument();
  });

  // Setting a first PIN proves nothing — there is nothing yet to prove — so the
  // server takes one only for the profile you are in. Offering the button on
  // someone else's row would be offering a 403, and the household member whose
  // profile it is would be locked out with a PIN they never chose.
  it("won't offer a first PIN on a profile you aren't using", async () => {
    activeProfile = KID;
    await renderSettings();

    expect(screen.getByRole("button", { name: "Set PIN for Alex" })).toBeDisabled();
    // Changing one, which does take proof, stays available from any profile.
    expect(screen.getByRole("button", { name: "Change PIN for Kid" })).toBeEnabled();
  });

  // Otherwise anyone with the app open could replace another profile's PIN
  // without knowing it, which is the whole point of having one.
  it("requires the current PIN to change an existing one", async () => {
    const user = userEvent.setup();
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Change PIN for Kid" }));
    await user.type(screen.getByLabelText("New PIN for Kid"), "9999");

    expect(screen.getByRole("button", { name: "Save PIN" })).toBeDisabled();

    await user.type(screen.getByLabelText("Current PIN for Kid"), "1111");
    await user.click(screen.getByRole("button", { name: "Save PIN" }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: "9999", currentPin: "1111" })
    );
  });

  it("names a wrong current PIN as such, not as a generic failure", async () => {
    const user = userEvent.setup();
    updateProfile.mockRejectedValue(new ApiError("Unauthorized", 401));
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Change PIN for Kid" }));
    await user.type(screen.getByLabelText("Current PIN for Kid"), "0000");
    await user.type(screen.getByLabelText("New PIN for Kid"), "9999");
    await user.click(screen.getByRole("button", { name: "Save PIN" }));

    expect(await screen.findByText("Incorrect current PIN.")).toBeInTheDocument();
  });

  it("removes a PIN once the current one is given", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...KID, hasPin: false } });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Remove PIN for Kid" }));
    await user.type(screen.getByLabelText("Current PIN for Kid, to confirm removal"), "1111");
    await user.click(screen.getByRole("button", { name: "Confirm PIN removal" }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: null, currentPin: "1111" })
    );
    expect(await screen.findByText("PIN removed for Kid")).toBeInTheDocument();
  });
});

describe("ProfileSettings deletion", () => {
  it("names what goes with the profile before deleting it", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const dialog = await startDelete(user, "Kid");

    await waitFor(() =>
      expect(dialog).toHaveTextContent(
        "Its 312 watched entries, 2 lists (41 titles), 6 resume points and 1 collection will be deleted with it"
      )
    );
    expect(getProfileSummary).toHaveBeenCalledWith(KID.id);
    expect(deleteProfile).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole("button", { name: "Delete permanently" }));

    await waitFor(() => expect(deleteProfile).toHaveBeenCalledWith(KID.id));
    await waitFor(() => expect(screen.queryByText("Kid")).not.toBeInTheDocument());
  });

  it("does nothing at all if the confirmation is cancelled", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(deleteProfile).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByText("Kid")).toBeInTheDocument();
  });

  // A 401 here means the server wants proof of the PIN — a solvable problem,
  // so it asks rather than reporting a failure the user can't act on.
  it("asks for the PIN when the server demands one, then deletes", async () => {
    const user = userEvent.setup();
    deleteProfile.mockRejectedValueOnce(new ApiError("PIN required", 401));
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(await within(dialog).findByRole("button", { name: "Delete permanently" }));

    const pinField = await screen.findByLabelText("PIN for Kid, to confirm deletion");
    await user.type(pinField, "1111");
    await user.click(screen.getByRole("button", { name: "Confirm deletion of Kid" }));

    await waitFor(() => expect(deleteProfile).toHaveBeenLastCalledWith(KID.id, "1111"));
    await waitFor(() => expect(screen.queryByText("Kid")).not.toBeInTheDocument());
  });

  it("reopens the switcher when the profile you were using is deleted", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(await within(dialog).findByRole("button", { name: "Delete permanently" }));

    // Every request from here is scoped to a profile that no longer exists, so
    // the app has to ask which one you are now.
    await waitFor(() => expect(openSwitcher).toHaveBeenCalled());
  });

  it("reports a refusal that isn't about the PIN", async () => {
    const user = userEvent.setup();
    deleteProfile.mockRejectedValue(new ApiError("Profile is in use", 409));
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(await within(dialog).findByRole("button", { name: "Delete permanently" }));

    expect(await screen.findByText("Profile is in use")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete Kid" })).toBeInTheDocument();
  });

  describe("appearance and parental controls", () => {
    it("saves colour, picture and a rating limit together", async () => {
      const user = userEvent.setup();
      updateProfile.mockResolvedValue({ profile: { ...KID, avatarColorHex: "#43A047", avatarId: "fetch:fox" } });
      await renderSettings();

      await user.click(screen.getByRole("button", { name: "Appearance and parental controls for Kid" }));
      await user.click(screen.getByRole("button", { name: "Emerald" }));
      await user.click(screen.getByRole("button", { name: "Fox" }));
      await user.selectOptions(screen.getByLabelText("Highest rating allowed"), "TEENS");
      await user.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() =>
        expect(updateProfile).toHaveBeenCalledWith(KID.id, {
          avatarColorHex: "#43A047",
          avatarId: "fetch:fox",
          usesPrimaryAddons: false,
          parental: { isKidsProfile: false, maxRating: "TEENS", allowUnrated: true },
        })
      );
    });

    it("applies the TV's kids preset when kids mode is switched on", async () => {
      const user = userEvent.setup();
      await renderSettings();

      await user.click(screen.getByRole("button", { name: "Appearance and parental controls for Kid" }));
      await user.click(screen.getByRole("checkbox", { name: /Kids profile/ }));

      expect(screen.getByLabelText("Highest rating allowed")).toHaveValue("PARENTAL_GUIDANCE");
      expect(screen.getByRole("checkbox", { name: "Show titles with no rating" })).not.toBeChecked();
    });

    it("doesn't offer addon sharing on the primary profile", async () => {
      const user = userEvent.setup();
      await renderSettings();

      await user.click(screen.getByRole("button", { name: "Appearance and parental controls for Alex" }));

      expect(screen.queryByRole("checkbox", { name: /primary profile's addons/ })).not.toBeInTheDocument();
    });
  });

  it("adds a profile", async () => {
    const user = userEvent.setup();
    createProfile.mockResolvedValue({ profile: { id: "p-sam", name: "Sam", hasPin: false } });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Add Profile" }));
    await user.type(screen.getByLabelText("New profile name"), "Sam");
    await user.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() => expect(createProfile).toHaveBeenCalledWith({ name: "Sam" }));
    expect(await screen.findByText("Sam")).toBeInTheDocument();
  });
});
