import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type Profile, runtimeConfig } from "../api";
import { ProfileSwitcher } from "./ProfileSwitcher";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getProfiles: vi.fn(),
      createProfile: vi.fn(),
      verifyProfile: vi.fn(),
    },
  };
});

// Imported after the mock so `api` here is the mocked object.
const { api } = await import("../api");
const getProfiles = vi.mocked(api.getProfiles);
const createProfile = vi.mocked(api.createProfile);
const verifyProfile = vi.mocked(api.verifyProfile);

const BEN: Profile = { id: "p-ben", name: "Ben", hasPin: false };
const LOCKED: Profile = { id: "p-kid", name: "Kid", hasPin: true };

beforeEach(() => {
  getProfiles.mockResolvedValue({ profiles: [BEN, LOCKED] });
});

const renderSwitcher = (props: Partial<React.ComponentProps<typeof ProfileSwitcher>> = {}) => {
  const onSelected = vi.fn();
  render(<ProfileSwitcher onSelected={onSelected} {...props} />);
  return { onSelected };
};

describe("ProfileSwitcher", () => {
  it("shows a loading state, then the profiles it fetched", async () => {
    renderSwitcher();

    expect(screen.getByText(/loading profiles/i)).toBeInTheDocument();

    expect(await screen.findByRole("button", { name: /ben/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /kid/i })).toBeInTheDocument();
    expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
  });

  it("reports a failed profile fetch instead of hanging on the spinner", async () => {
    getProfiles.mockRejectedValue(new Error("Network error – cannot reach API"));

    renderSwitcher();

    expect(await screen.findByText(/cannot reach api/i)).toBeInTheDocument();
  });

  it("offers a retry after a failed profile fetch", async () => {
    getProfiles.mockRejectedValueOnce(new Error("Network error – cannot reach API"));

    renderSwitcher();
    await userEvent.click(await screen.findByRole("button", { name: /try again/i }));

    expect(await screen.findByRole("button", { name: /ben/i })).toBeInTheDocument();
    expect(getProfiles).toHaveBeenCalledTimes(2);
  });

  it("goes straight to profile creation when the install has no profiles yet", async () => {
    getProfiles.mockResolvedValue({ profiles: [] });

    renderSwitcher();

    expect(await screen.findByText(/create your first profile/i)).toBeInTheDocument();
    // Nothing to go back to, so no cancel escape hatch.
    expect(screen.queryByRole("button", { name: /cancel/i })).not.toBeInTheDocument();
  });

  describe("selecting a PIN-free profile", () => {
    it("verifies it, stores the id and hands it back", async () => {
      verifyProfile.mockResolvedValue({ id: BEN.id, name: BEN.name });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /ben/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(BEN));
      expect(verifyProfile).toHaveBeenCalledWith(BEN.id, undefined, { persist: false });
      expect(runtimeConfig.getProfileId()).toBe(BEN.id);
    });

    it("surfaces a failure and keeps the previous selection", async () => {
      runtimeConfig.setProfileId("p-previous");
      verifyProfile.mockRejectedValue(new ApiError("Profile not found", 404));
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /ben/i }));

      expect(await screen.findByText(/profile not found/i)).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
      expect(runtimeConfig.getProfileId()).toBe("p-previous");
      // The list stays, so another profile is one click away.
      expect(screen.getByRole("button", { name: /kid/i })).toBeInTheDocument();
    });
  });

  describe("PIN entry", () => {
    it("prompts for a PIN instead of verifying straight away", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));

      expect(screen.getByText(/enter pin for kid/i)).toBeInTheDocument();
      expect(verifyProfile).not.toHaveBeenCalled();
    });

    it("keeps the unlock button disabled until a PIN is typed", async () => {
      renderSwitcher();
      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));

      const unlock = screen.getByRole("button", { name: /unlock/i });
      expect(unlock).toBeDisabled();

      await userEvent.type(screen.getByLabelText("PIN"), "1234");

      expect(unlock).toBeEnabled();
    });

    it("unlocks the profile on a correct PIN", async () => {
      verifyProfile.mockResolvedValue({ id: LOCKED.id, name: LOCKED.name, profileToken: "signed" });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "1234");
      await userEvent.click(screen.getByRole("button", { name: /unlock/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(LOCKED));
      expect(verifyProfile).toHaveBeenCalledWith(LOCKED.id, "1234", { persist: false });
      expect(runtimeConfig.getProfileToken()).toBe("signed");
      expect(runtimeConfig.getProfileId()).toBe(LOCKED.id);
    });

    it("reports an incorrect PIN without selecting the profile", async () => {
      verifyProfile.mockRejectedValue(new ApiError("Unauthorized", 401));
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "0000{Enter}");

      expect(await screen.findByText(/incorrect pin/i)).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
      expect(runtimeConfig.getProfileId()).toBe("");
    });

    it("distinguishes a server error from a wrong PIN", async () => {
      verifyProfile.mockRejectedValue(new ApiError("Profile store unavailable", 500));
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "1234{Enter}");

      expect(await screen.findByText(/profile store unavailable/i)).toBeInTheDocument();
      expect(screen.queryByText(/incorrect pin/i)).not.toBeInTheDocument();
    });

    it("returns to the picker without selecting anything", async () => {
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.click(screen.getByRole("button", { name: /back/i }));

      expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
    });
  });

  describe("creating a profile", () => {
    it("sends the trimmed name with no PIN when the PIN field is left blank", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: false };
      createProfile.mockResolvedValue({ profile: created });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "  Sam  ");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
      expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: undefined });
      expect(runtimeConfig.getProfileId()).toBe("p-new");
    });

    it("passes the PIN through when one is given", async () => {
      createProfile.mockResolvedValue({ profile: { id: "p-new", name: "Sam", hasPin: true } });
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), "4321");
      await userEvent.type(screen.getByLabelText("Confirm PIN"), "4321");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      await waitFor(() => expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: "4321" }));
    });

    it("verifies a new PIN profile with its PIN before entering it, so it isn't locked out at once", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name, profileToken: "signed" });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), "4321");
      await userEvent.type(screen.getByLabelText("Confirm PIN"), "4321");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
      expect(verifyProfile).toHaveBeenCalledWith("p-new", "4321", { persist: false });
      expect(runtimeConfig.getProfileToken()).toBe("signed");
    });

    // #391: the switcher was closed while the create was in flight.
    it("changes nothing when the switcher closes before the new profile is ready", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      let finishCreate!: (value: { profile: Profile }) => void;
      createProfile.mockReturnValue(new Promise((resolve) => { finishCreate = resolve; }));
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name, profileToken: "signed" });
      runtimeConfig.setProfileId("p-previous");
      runtimeConfig.setProfileToken("previous-profile-token");
      const onClose = vi.fn();
      const { onSelected } = renderSwitcher({ onClose });

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), "4321");
      await userEvent.type(screen.getByLabelText("Confirm PIN"), "4321");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));
      await userEvent.keyboard("{Escape}");
      finishCreate({ profile: created });

      await waitFor(() => expect(onClose).toHaveBeenCalledOnce(), { timeout: 1000 });
      await waitFor(() => expect(verifyProfile).toHaveBeenCalled());
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(onSelected).not.toHaveBeenCalled();
      expect(runtimeConfig.getProfileId()).toBe("p-previous");
      expect(runtimeConfig.getProfileToken()).toBe("previous-profile-token");
    });

    it("changes nothing when the form is cancelled before the new profile is ready", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: false };
      let finishCreate!: (value: { profile: Profile }) => void;
      createProfile.mockReturnValue(new Promise((resolve) => { finishCreate = resolve; }));
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name, profileToken: "" });
      runtimeConfig.setProfileToken("previous-profile-token");
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));
      await userEvent.click(screen.getByRole("button", { name: /cancel/i }));
      finishCreate({ profile: created });

      await waitFor(() => expect(verifyProfile).toHaveBeenCalled());
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(onSelected).not.toHaveBeenCalled();
      expect(runtimeConfig.getProfileToken()).toBe("previous-profile-token");
      expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
    });

    it("drops the previous profile's token and still enters the new profile if verifying fails", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockRejectedValue(new ApiError("Profile store unavailable", 500));
      runtimeConfig.setProfileToken("previous-profile-token");
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), "4321");
      await userEvent.type(screen.getByLabelText("Confirm PIN"), "4321");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
      expect(runtimeConfig.getProfileToken()).toBe("");
    });

    it.each(["123", "12ab"])("explains the PIN rule instead of creating a profile with PIN %j", async (pin) => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), pin);
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      expect(await screen.findByRole("alert")).toHaveTextContent("PIN must be 4 to 8 digits.");
      expect(createProfile).not.toHaveBeenCalled();
    });

    it("describes the 4–8 digit rule without truncating input", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      const field = screen.getByLabelText("PIN (optional)");
      expect(field).not.toHaveAttribute("maxLength");
      expect(field).toHaveAttribute("inputMode", "numeric");
      expect(field).toHaveAccessibleDescription("4–8 digits");
    });

    it("cannot be submitted with a blank name", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "   ");

      expect(screen.getByRole("button", { name: /create/i })).toBeDisabled();
      expect(createProfile).not.toHaveBeenCalled();
    });

    it("shows the server's rejection and stays on the form", async () => {
      createProfile.mockRejectedValue(new ApiError("A profile named Sam already exists", 409));
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /new profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.click(screen.getByRole("button", { name: /create/i }));

      expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
      expect(screen.getByLabelText("Profile name")).toHaveValue("Sam");
    });
  });

  describe("as a modal switcher", () => {
    // Both routes now go through the exit animation, so the close lands on the
    // animationend — or, in jsdom, on the hook's fallback timer — rather than
    // on the click itself. One render each: closing is idempotent afterwards,
    // so a second route in the same render has nothing left to close.
    it.each([
      ["the close button", async () => { await userEvent.click(screen.getByRole("button", { name: /close/i })); }],
      ["Escape", async () => { await userEvent.keyboard("{Escape}"); }],
    ])("closes on %s, once its exit has played", async (_label, close) => {
      const onClose = vi.fn();
      renderSwitcher({ onClose });
      await screen.findByRole("button", { name: /ben/i });

      await close();

      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByRole("dialog")).toHaveClass("overlay-exit");
      // The exit window is still keyboard-reachable without this: a focused
      // profile button would act on an Enter pressed while the dialog leaves.
      expect(screen.getByRole("dialog").querySelector(".overlay-dialog")).toHaveAttribute("inert");
      await waitFor(() => expect(onClose).toHaveBeenCalledOnce(), { timeout: 1000 });
    });

    // 32×32 as drawn; `.tap-target` is what takes the tappable area to 44 on a
    // touch screen without making the button itself any heavier.
    it("gives the close button a full-size touch target", async () => {
      renderSwitcher({ onClose: vi.fn() });
      await screen.findByRole("button", { name: /ben/i });

      expect(screen.getByRole("button", { name: /close/i }).className).toContain("tap-target");
    });

    it("renders as a labelled modal dialog", async () => {
      renderSwitcher({ onClose: vi.fn() });
      await screen.findByRole("button", { name: /ben/i });

      const dialog = screen.getByRole("dialog");
      expect(dialog).toHaveAttribute("aria-modal", "true");
      expect(dialog).toHaveAccessibleName("Switch profile");
    });

    it("has no dismiss affordance when used as the full-page gate", async () => {
      renderSwitcher();
      await screen.findByRole("button", { name: /ben/i });

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
    });
  });

  describe("accessibility", () => {
    it("heads the full-page gate with an h1", async () => {
      renderSwitcher();

      expect(await screen.findByRole("heading", { level: 1, name: /who's watching/i })).toBeInTheDocument();
    });

    it("drops to an h2 as a modal, since the page behind still owns the h1", async () => {
      renderSwitcher({ onClose: vi.fn() });

      expect(await screen.findByRole("heading", { level: 2, name: /who's watching/i })).toBeInTheDocument();
      expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
    });

    it("keeps a heading while loading and when the fetch fails", async () => {
      getProfiles.mockRejectedValue(new Error("Network error – cannot reach API"));
      renderSwitcher();

      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();

      await screen.findByText(/cannot reach api/i);
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    });

    it("names the PIN and create fields without relying on their placeholders", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      expect(screen.getByLabelText("PIN")).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: /back/i }));
      await userEvent.click(screen.getByRole("button", { name: /new profile/i }));
      expect(screen.getByLabelText("Profile name")).toBeInTheDocument();
      expect(screen.getByLabelText("PIN (optional)")).toBeInTheDocument();
    });
  });
});

describe("locking the profile in use", () => {
  it("offers to lock the active PIN profile, which leaves it without signing out", async () => {
    runtimeConfig.setProfileId(LOCKED.id);
    runtimeConfig.setProfileToken("kid-token");
    const locked = vi.fn();
    window.addEventListener("fetch:profile-locked", locked);
    renderSwitcher({ onClose: vi.fn() });

    await userEvent.click(await screen.findByRole("button", { name: "Lock Kid" }));

    expect(locked).toHaveBeenCalledOnce();
    expect(runtimeConfig.getProfileId()).toBe("");
    expect(runtimeConfig.getProfileToken()).toBe("");
    window.removeEventListener("fetch:profile-locked", locked);
  });

  it("offers nothing to lock for a profile without a PIN, or on the first-run picker", async () => {
    runtimeConfig.setProfileId(BEN.id);
    renderSwitcher({ onClose: vi.fn() });
    expect(await screen.findByRole("button", { name: /ben/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Lock / })).not.toBeInTheDocument();
  });
});
