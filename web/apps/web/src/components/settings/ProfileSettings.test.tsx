import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
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
      verifyProfile: vi.fn(),
      clearProfilePin: vi.fn(),
    },
  };
});

let accountState: { account: { id: string; username: string; isAdmin: boolean } | null; isAdmin: boolean } = {
  account: null,
  isAdmin: false,
};
vi.mock("../../hooks/useAccount", () => ({
  useAccount: () => ({ ...accountState, refresh: () => {} }),
}));

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
const verifyProfile = vi.mocked(api.verifyProfile);
const clearProfilePin = vi.mocked(api.clearProfilePin);

const KID_SUMMARY = { watchEvents: 312, lists: 2, listItems: 41, playbackProgress: 6, ratings: 0, collections: 1 };

const ALEX: Profile = { id: "p-alex", name: "Alex", hasPin: false };
const KID: Profile = { id: "p-kid", name: "Kid", hasPin: true };

const renderSettings = async () => {
  render(
    <MemoryRouter>
      <ToastProvider>
        <ProfileSettings />
      </ToastProvider>
    </MemoryRouter>
  );
  await waitFor(() => expect(screen.queryByText("Loading profiles…")).not.toBeInTheDocument());
};

/** The card for a profile, in the list of them. */
const cardFor = (name: string) => {
  const list = screen.getByRole("list", { name: "Profiles" });
  const card = within(list)
    .getAllByRole("listitem")
    .find((item) => within(item).queryByText(name, { selector: "p" }));
  if (!card) throw new Error(`No profile card found for "${name}"`);
  return within(card);
};

/** Opens a profile's sheet and returns it. */
const openSheet = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
  await user.click(screen.getByRole("button", { name: `Edit ${name}` }));
  return within(await screen.findByRole("dialog", { name: `Edit ${name}` }));
};

/** Opens the confirmation for `name` and waits for it to say what goes. */
const startDelete = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
  const sheet = await openSheet(user, name);
  await user.click(sheet.getByRole("button", { name: `Delete ${name}…` }));
  return screen.findByRole("alertdialog", { name: `Delete ${name}?` });
};

beforeEach(() => {
  accountState = { account: null, isAdmin: false };
  window.localStorage.clear();
  activeProfile = ALEX;
  getProfiles.mockResolvedValue({ profiles: [ALEX, KID] });
  updateProfile.mockResolvedValue({ profile: ALEX });
  deleteProfile.mockResolvedValue(undefined as never);
  getProfileSummary.mockResolvedValue(KID_SUMMARY);
});

describe("ProfileSettings", () => {
  it("shows each profile as a card that says what it is, with one labelled action", async () => {
    await renderSettings();

    expect(cardFor("Alex").getByText("In use")).toBeInTheDocument();
    expect(cardFor("Alex").getByText("Primary")).toBeInTheDocument();
    expect(cardFor("Kid").getByText("PIN")).toBeInTheDocument();
    expect(cardFor("Kid").queryByText("Primary")).not.toBeInTheDocument();
    // #537: no profile action is an unlabeled icon.
    for (const name of ["Alex", "Kid"]) {
      const buttons = cardFor(name).getAllByRole("button");
      expect(buttons).toHaveLength(1);
      expect(buttons[0]).toHaveTextContent("Edit");
    }
  });

  it("marks a kids profile", async () => {
    getProfiles.mockResolvedValue({
      profiles: [ALEX, { ...KID, parental: { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false } }],
    });
    await renderSettings();

    expect(cardFor("Kid").getByText("Kids")).toBeInTheDocument();
  });

  it("says so when the profiles can't be loaded", async () => {
    getProfiles.mockRejectedValue(new Error("Profiles unavailable"));
    await renderSettings();

    expect(screen.getByText("Profiles unavailable")).toBeInTheDocument();
  });

  it("won't offer to delete the only profile there is", async () => {
    const user = userEvent.setup();
    getProfiles.mockResolvedValue({ profiles: [ALEX] });
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    expect(sheet.queryByRole("button", { name: /^Delete Alex/ })).not.toBeInTheDocument();
    expect(sheet.getByText(/only profile/)).toBeInTheDocument();
  });
});

describe("ProfileSettings editing", () => {
  it("renames the profile, updates the one in use and says so", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...ALEX, name: "Alexandra" } });
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    await user.type(sheet.getByLabelText("Name"), "andra");
    await user.click(sheet.getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(ALEX.id, expect.objectContaining({ name: "Alexandra" }))
    );
    // The switcher's copy of the active profile has to move with it, or the
    // header goes on greeting a name that no longer exists.
    expect(setActiveProfile).toHaveBeenCalledWith({ ...ALEX, name: "Alexandra" });
    expect(await screen.findByText("Saved Alexandra")).toBeInTheDocument();
  });

  it("has nothing to save until something changes", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    expect(sheet.getByRole("button", { name: "Save changes" })).toBeDisabled();
  });

  it("keeps the sheet up when the save is refused", async () => {
    const user = userEvent.setup();
    updateProfile.mockRejectedValue(new Error("Name already taken"));
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    await user.type(sheet.getByLabelText("Name"), "!");
    await user.click(sheet.getByRole("button", { name: "Save changes" }));

    expect(await sheet.findByText("Name already taken")).toBeInTheDocument();
    expect(sheet.getByLabelText("Name")).toHaveValue("Alex!");
  });

  it("saves name, colour, picture and a rating limit together, with the PIN of a locked profile", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...KID, avatarColorHex: "#43A047", avatarId: "fetch:fox" } });
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Emerald" }));
    await user.click(sheet.getByRole("button", { name: "Fox" }));
    await user.selectOptions(sheet.getByLabelText("Highest rating allowed on the TV"), "TEENS");
    // Kid has a PIN and isn't the profile in use, so the change carries it.
    await user.click(sheet.getByRole("button", { name: "Save changes" }));
    expect(await sheet.findByRole("alert")).toHaveTextContent("Enter Kid's PIN at the top");
    expect(updateProfile).not.toHaveBeenCalled();

    await user.type(sheet.getByLabelText("Kid's PIN"), "1234");
    await user.click(sheet.getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(KID.id, {
        avatarColorHex: "#43A047",
        avatarId: "fetch:fox",
        usesPrimaryAddons: false,
        parental: { isKidsProfile: false, maxRating: "TEENS", allowUnrated: true },
        currentPin: "1234",
      })
    );
  });

  it("applies the TV's kids preset when kids mode is switched on", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("switch", { name: /Kids profile/ }));

    expect(sheet.getByLabelText("Highest rating allowed on the TV")).toHaveValue("PARENTAL_GUIDANCE");
    expect(sheet.getByRole("switch", { name: /Show titles with no rating/ })).not.toBeChecked();
  });

  // #539: a parent setting a limit knows where it applies.
  it("says the rating limit applies on TVs and not on the website", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    expect(sheet.getByText(/apply on paired TVs only/)).toBeInTheDocument();
    expect(sheet.getByText(/website doesn't filter titles by rating/)).toBeInTheDocument();
  });

  it("names the primary profile and doesn't offer it addon sharing", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    expect(sheet.getByText(/Alex is the primary profile/)).toBeInTheDocument();
    expect(sheet.queryByRole("switch", { name: /primary profile's addons/ })).not.toBeInTheDocument();
  });
});

describe("ProfileSettings PINs", () => {
  it("sets a first PIN, typed twice, without asking for one that doesn't exist", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValue({ profile: { ...ALEX, hasPin: true } });
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    await user.click(sheet.getByRole("button", { name: "Set a PIN" }));
    expect(sheet.queryByLabelText("Current PIN")).not.toBeInTheDocument();
    await user.type(sheet.getByLabelText("PIN", { selector: "input" }), "1234");
    expect(sheet.getByRole("button", { name: "Save PIN" })).toBeDisabled();
    await user.type(sheet.getByLabelText("Type it again"), "1234");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(ALEX.id, { pin: "1234" }));
    expect(await screen.findByText("PIN set for Alex")).toBeInTheDocument();
  });

  // #537: one typo in a single masked field used to lock the profile.
  it("won't set a PIN whose second typing differs", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    await user.click(sheet.getByRole("button", { name: "Set a PIN" }));
    await user.type(sheet.getByLabelText("PIN", { selector: "input" }), "1234");
    await user.type(sheet.getByLabelText("Type it again"), "1243");

    expect(sheet.getByText("The two PINs don't match.")).toBeInTheDocument();
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));
    expect(await sheet.findByRole("alert")).toHaveTextContent(/don't match/);
    expect(updateProfile).not.toHaveBeenCalled();
  });

  // Setting a first PIN proves nothing — there is nothing yet to prove — so the
  // server takes one only for the profile you are in. Offering it on someone
  // else's profile would be offering a 403, and the household member whose
  // profile it is would be locked out with a PIN they never chose.
  it("explains, in words, why a first PIN needs the profile itself", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    expect(sheet.queryByRole("button", { name: "Set a PIN" })).not.toBeInTheDocument();
    expect(sheet.getByText(/first PIN can only be set from inside Alex/)).toBeInTheDocument();
    await user.click(sheet.getByRole("button", { name: "Switch to Alex" }));
    expect(openSwitcher).toHaveBeenCalled();
  });

  // Otherwise anyone with the app open could replace another profile's PIN
  // without knowing it, which is the whole point of having one.
  it("requires the current PIN to change an existing one", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.type(sheet.getByLabelText("New PIN"), "9999");
    await user.type(sheet.getByLabelText("Type it again"), "9999");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));
    expect(await sheet.findByRole("alert")).toHaveTextContent("Enter the current PIN.");

    await user.type(sheet.getByLabelText("Current PIN"), "1111");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: "9999", currentPin: "1111" }));
  });

  it("uses the PIN typed at the top for a locked profile that isn't in use", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.type(sheet.getByLabelText("Kid's PIN"), "1111");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    expect(sheet.queryByLabelText("Current PIN")).not.toBeInTheDocument();
    await user.type(sheet.getByLabelText("New PIN"), "9999");
    await user.type(sheet.getByLabelText("Type it again"), "9999");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: "9999", currentPin: "1111" }));
  });

  it("explains the 4–8 digit rule instead of sending a PIN that breaks it", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Alex");
    await user.click(sheet.getByRole("button", { name: "Set a PIN" }));
    await user.click(sheet.getByLabelText("PIN", { selector: "input" }));
    await user.paste("123456789");

    expect(sheet.getByLabelText("PIN", { selector: "input" })).toHaveValue("");
    expect(await sheet.findByRole("alert")).toHaveTextContent("PIN must be 4 to 8 digits.");
    expect(updateProfile).not.toHaveBeenCalled();
  });

  // A PIN set before the digits-only rule still has to unlock, so the field
  // that asks for the current one is not held to it.
  it("takes any existing PIN as the current one", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.type(sheet.getByLabelText("Current PIN"), "an old passphrase");
    await user.type(sheet.getByLabelText("New PIN"), "246810");
    await user.type(sheet.getByLabelText("Type it again"), "246810");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: "246810", currentPin: "an old passphrase" })
    );
  });

  it("names a wrong current PIN as such, not as a generic failure", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    updateProfile.mockRejectedValue(new ApiError("Unauthorized", 401));
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.type(sheet.getByLabelText("Current PIN"), "0000");
    await user.type(sheet.getByLabelText("New PIN"), "9999");
    await user.type(sheet.getByLabelText("Type it again"), "9999");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));

    expect(await sheet.findByText("Incorrect current PIN.")).toBeInTheDocument();
  });

  it("removes a PIN once the current one is given", async () => {
    const user = userEvent.setup();
    activeProfile = KID;
    updateProfile.mockResolvedValue({ profile: { ...KID, hasPin: false } });
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Remove PIN" }));
    await user.type(sheet.getByLabelText("Current PIN"), "1111");
    await user.click(sheet.getByRole("button", { name: "Remove PIN" }));

    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: null, currentPin: "1111" }));
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

    await waitFor(() => expect(deleteProfile).toHaveBeenCalledWith(KID.id, undefined));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Edit Kid" })).not.toBeInTheDocument());
  });

  it("does nothing at all if the confirmation is cancelled", async () => {
    const user = userEvent.setup();
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(deleteProfile).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  // A 401 here means the server wants proof of the PIN — a solvable problem,
  // so it asks rather than reporting a failure the user can't act on.
  it("asks for the PIN when the server demands one, then deletes", async () => {
    const user = userEvent.setup();
    deleteProfile.mockRejectedValueOnce(new ApiError("PIN required", 401));
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(await within(dialog).findByRole("button", { name: "Delete permanently" }));

    await user.type(await within(dialog).findByLabelText("PIN for Kid, to confirm"), "1111");
    await user.click(within(dialog).getByRole("button", { name: "Delete permanently" }));

    await waitFor(() => expect(deleteProfile).toHaveBeenLastCalledWith(KID.id, "1111"));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Edit Kid" })).not.toBeInTheDocument());
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

    expect(await within(dialog).findByText("Profile is in use")).toBeInTheDocument();
  });
});

describe("ProfileSettings adding", () => {
  it("adds a profile with the same flow as the switcher, then asks whether to switch", async () => {
    const user = userEvent.setup();
    const sam: Profile = { id: "p-sam", name: "Sam", hasPin: false };
    createProfile.mockResolvedValue({ profile: sam });
    verifyProfile.mockResolvedValue({ id: sam.id, name: sam.name });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Add profile" }));
    await user.type(screen.getByLabelText("Profile name"), "Sam");
    await user.click(screen.getByRole("button", { name: "Fox" }));
    await user.click(screen.getByRole("button", { name: /^create/i }));

    await waitFor(() => expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: undefined, avatarId: "fetch:fox" }));
    expect(await screen.findByText("Sam is ready")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit Sam" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Switch to Sam" }));
    await waitFor(() => expect(setActiveProfile).toHaveBeenCalledWith(sam));
  });

  it("stays as the current profile when asked to", async () => {
    const user = userEvent.setup();
    createProfile.mockResolvedValue({ profile: { id: "p-sam", name: "Sam", hasPin: false } });
    await renderSettings();

    await user.click(screen.getByRole("button", { name: "Add profile" }));
    await user.type(screen.getByLabelText("Profile name"), "Sam");
    await user.click(screen.getByRole("button", { name: /^create/i }));
    await user.click(await screen.findByRole("button", { name: "Stay as Alex" }));

    expect(setActiveProfile).not.toHaveBeenCalled();
    expect(verifyProfile).not.toHaveBeenCalled();
    expect(screen.queryByText("Sam is ready")).not.toBeInTheDocument();
  });
});

describe("ProfileSettings recovery and locking", () => {
  it("lets the admin clear a forgotten PIN with their password", async () => {
    accountState = { account: { id: "a1", username: "owner", isAdmin: true }, isAdmin: true };
    clearProfilePin
      .mockRejectedValueOnce(new ApiError("Incorrect password", 401))
      .mockResolvedValueOnce({ profile: { ...KID, hasPin: false } });
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.click(sheet.getByRole("button", { name: "Forgot the PIN?" }));
    const review = screen.getByRole("alertdialog", { name: "Clear the PIN on Kid?" });
    await user.type(within(review).getByLabelText("Your account password"), "wrong-one");
    await user.click(within(review).getByRole("button", { name: "Clear PIN" }));
    expect(await within(review).findByText("Incorrect password.")).toBeInTheDocument();
    expect(within(review).getByLabelText("Your account password")).toHaveAttribute("aria-invalid", "true");
    expect(within(review).getByLabelText("Your account password")).toHaveAccessibleDescription(/Incorrect password/);

    await user.clear(within(review).getByLabelText("Your account password"));
    await user.type(within(review).getByLabelText("Your account password"), "owner-password");
    await user.click(within(review).getByRole("button", { name: "Clear PIN" }));

    await waitFor(() => expect(clearProfilePin).toHaveBeenLastCalledWith(KID.id, "owner-password"));
    expect(await screen.findByText("PIN cleared for Kid")).toBeInTheDocument();
    expect(cardFor("Kid").queryByText("PIN")).not.toBeInTheDocument();
  });

  it("goes back to the PIN form with focus on 'Forgot the PIN?' when the clear is cancelled", async () => {
    accountState = { account: { id: "a1", username: "owner", isAdmin: true }, isAdmin: true };
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.click(sheet.getByRole("button", { name: "Forgot the PIN?" }));
    const review = screen.getByRole("alertdialog", { name: "Clear the PIN on Kid?" });
    await user.click(within(review).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("alertdialog", { name: "Clear the PIN on Kid?" })).not.toBeInTheDocument();
    await waitFor(() => expect(sheet.getByRole("button", { name: "Forgot the PIN?" })).toHaveFocus());
    expect(clearProfilePin).not.toHaveBeenCalled();
  });

  it("tells anyone else to ask the admin", async () => {
    accountState = { account: { id: "a2", username: "friend", isAdmin: false }, isAdmin: false };
    const user = userEvent.setup();
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    expect(sheet.queryByRole("button", { name: "Forgot the PIN?" })).not.toBeInTheDocument();
    expect(sheet.getByText("Forgot the PIN? Ask the admin to clear it.")).toBeInTheDocument();
  });

  it("keeps this browser's inactivity limit for PIN profiles", async () => {
    const user = userEvent.setup();
    await renderSettings();

    await user.selectOptions(screen.getByLabelText("Lock PIN profiles on this browser after"), "15");
    expect(window.localStorage.getItem("fetch_idle_lock_minutes")).toBe("15");
  });

  it("offers no inactivity limit when no profile has a PIN", async () => {
    getProfiles.mockResolvedValue({ profiles: [ALEX] });
    await renderSettings();
    expect(screen.queryByLabelText("Lock PIN profiles on this browser after")).not.toBeInTheDocument();
  });
});

describe("ProfileSettings review follow-ups", () => {
  it("doesn't offer Add profile until the list is in", async () => {
    let finish!: (value: { profiles: Profile[] }) => void;
    getProfiles.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    render(
      <MemoryRouter>
        <ToastProvider>
          <ProfileSettings />
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.queryByRole("button", { name: "Add profile" })).not.toBeInTheDocument();
    finish({ profiles: [ALEX, KID] });
    expect(await screen.findByRole("button", { name: "Add profile" })).toBeInTheDocument();
  });

  it("doesn't offer Add profile when the list failed to load", async () => {
    getProfiles.mockRejectedValue(new Error("Profiles unavailable"));
    await renderSettings();
    expect(screen.queryByRole("button", { name: "Add profile" })).not.toBeInTheDocument();
  });

  it("goes on with the new PIN after changing a locked profile's PIN", async () => {
    const user = userEvent.setup();
    updateProfile.mockResolvedValueOnce({ profile: KID });
    await renderSettings();

    const sheet = await openSheet(user, "Kid");
    await user.type(sheet.getByLabelText("Kid's PIN"), "1111");
    await user.click(sheet.getByRole("button", { name: "Change PIN" }));
    await user.type(sheet.getByLabelText("New PIN"), "9999");
    await user.type(sheet.getByLabelText("Type it again"), "9999");
    await user.click(sheet.getByRole("button", { name: "Save PIN" }));
    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(KID.id, { pin: "9999", currentPin: "1111" }));

    expect(sheet.getByLabelText("Kid's PIN")).toHaveValue("9999");
    await user.type(sheet.getByLabelText("Name"), "do");
    await user.click(sheet.getByRole("button", { name: "Save changes" }));
    await waitFor(() =>
      expect(updateProfile).toHaveBeenLastCalledWith(KID.id, expect.objectContaining({ name: "Kiddo", currentPin: "9999" }))
    );
  });

  it("asks afresh each time the delete review opens, and moves focus into it and back", async () => {
    const user = userEvent.setup();
    await renderSettings();

    let dialog = await startDelete(user, "Kid");
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await waitFor(() => expect(dialog).toHaveTextContent("312 watched entries"));
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Delete Kid…" })).toHaveFocus();

    getProfileSummary.mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("button", { name: "Delete Kid…" }));
    dialog = await screen.findByRole("alertdialog", { name: "Delete Kid?" });
    await waitFor(() => expect(dialog).toHaveTextContent("Its watch history, lists, resume points, ratings and collections"));
    expect(dialog).not.toHaveTextContent("312");
  });

  it("can't be cancelled while the delete is out", async () => {
    const user = userEvent.setup();
    deleteProfile.mockReturnValue(new Promise(() => {}));
    await renderSettings();

    const dialog = await startDelete(user, "Kid");
    await user.click(await within(dialog).findByRole("button", { name: "Delete permanently" }));

    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
  });
});

