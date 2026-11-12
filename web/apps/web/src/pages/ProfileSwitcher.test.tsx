import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
      lockProfile: vi.fn(),
    },
  };
});

// Imported after the mock so `api` here is the mocked object.
const { api } = await import("../api");
const getProfiles = vi.mocked(api.getProfiles);
const createProfile = vi.mocked(api.createProfile);
const verifyProfile = vi.mocked(api.verifyProfile);
const lockProfile = vi.mocked(api.lockProfile);

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
      expect(verifyProfile).toHaveBeenCalledWith(BEN.id);
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
      verifyProfile.mockResolvedValue({ id: LOCKED.id, name: LOCKED.name });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "1234");
      await userEvent.click(screen.getByRole("button", { name: /unlock/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(LOCKED));
      expect(verifyProfile).toHaveBeenCalledWith(LOCKED.id, "1234");
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

    it("goes ahead on its own once all 8 digits are in", async () => {
      verifyProfile.mockResolvedValue({ id: LOCKED.id, name: LOCKED.name });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "12345678");

      await waitFor(() => expect(onSelected).toHaveBeenCalled());
      expect(verifyProfile).toHaveBeenCalledWith(LOCKED.id, "12345678");
    });

    it("clears and marks a wrong PIN so the next try starts empty", async () => {
      verifyProfile.mockRejectedValue(new ApiError("Incorrect PIN", 401));
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "0000{Enter}");

      expect(await screen.findByText(/incorrect pin/i)).toBeInTheDocument();
      expect(screen.getByLabelText("PIN")).toHaveValue("");
      expect(screen.getByLabelText("PIN")).toHaveAttribute("aria-invalid", "true");
    });

    it("counts down to when a locked-out profile can be tried again", async () => {
      verifyProfile.mockRejectedValue(new ApiError("Too many requests. Try again in a moment.", 429, undefined, 125));
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        fireEvent.change(screen.getByLabelText("PIN"), { target: { value: "0000" } });
        fireEvent.submit(screen.getByLabelText("PIN").closest("form")!);

        expect(await screen.findByText("2:05")).toBeInTheDocument();
        // Let the countdown's effect start its clock before moving the time.
        await act(async () => {});
        expect(screen.getByLabelText("PIN")).toBeDisabled();
        expect(screen.getByRole("button", { name: /unlock/i })).toBeDisabled();

        await act(async () => { vi.advanceTimersByTime(5000); });
        // A real millisecond or two also passes alongside the fake clock.
        expect(screen.getByRole("status").textContent).toMatch(/in (2:00|1:59)\./);

        await act(async () => { vi.advanceTimersByTime(121_000); });
        expect(screen.queryByText(/try again in/i)).not.toBeInTheDocument();
        expect(screen.getByLabelText("PIN")).toBeEnabled();
      } finally {
        vi.useRealTimers();
      }
    });

    it("still takes a PIN set before the digits-only rule", async () => {
      verifyProfile.mockResolvedValue({ id: LOCKED.id, name: LOCKED.name });
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.click(screen.getByRole("button", { name: /letters or more than 8 digits/i }));
      await userEvent.type(screen.getByLabelText("PIN"), "hunter2{Enter}");

      await waitFor(() => expect(onSelected).toHaveBeenCalled());
      expect(verifyProfile).toHaveBeenCalledWith(LOCKED.id, "hunter2");
    });

    it("returns to the picker without selecting anything", async () => {
      const { onSelected } = renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /kid/i }));
      await userEvent.click(screen.getByRole("button", { name: /back/i }));

      expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
    });
  });

  describe("the grid (#536)", () => {
    it("marks the profile in use, the kids profile and the PIN-protected one", async () => {
      runtimeConfig.setProfileId(BEN.id);
      getProfiles.mockResolvedValue({
        profiles: [BEN, { ...LOCKED, parental: { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE", allowUnrated: false } }],
      });
      renderSwitcher();

      expect(await screen.findByRole("button", { name: "Ben, in use" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Kid, kids profile, PIN protected" })).toBeInTheDocument();
      expect(screen.getByText("Kids")).toBeInTheDocument();
    });

    it("draws each avatar at 96px or more", async () => {
      renderSwitcher();

      const tile = await screen.findByRole("button", { name: /ben/i });
      // xl: 96px on a phone, 128px from `sm`.
      expect(tile.querySelector(".h-24.w-24")).not.toBeNull();
    });

    it("ends with an add-profile tile", async () => {
      renderSwitcher();

      const tiles = within(await screen.findByRole("list")).getAllByRole("button");
      expect(tiles.at(-1)).toHaveAccessibleName("Add profile");
    });
  });

  describe("creating a profile", () => {
    /** Fills the add-profile form and creates it; returns once the request is out. */
    const create = async (name: string, pin?: string) => {
      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), name);
      if (pin) {
        await userEvent.type(screen.getByLabelText("PIN (optional)"), pin);
        await userEvent.type(screen.getByLabelText("Type the PIN again"), pin);
      }
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));
    };

    it("sends the trimmed name with no PIN, then asks before switching to it", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: false };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name });
      const { onSelected } = renderSwitcher();

      await create("  Sam  ");

      expect(await screen.findByText("Sam is ready")).toBeInTheDocument();
      expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: undefined });
      expect(onSelected).not.toHaveBeenCalled();

      await userEvent.click(screen.getByRole("button", { name: "Switch to Sam" }));
      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
      expect(runtimeConfig.getProfileId()).toBe("p-new");
    });

    it("goes back to the grid, with the new profile in it, when not switching", async () => {
      createProfile.mockResolvedValue({ profile: { id: "p-new", name: "Sam", hasPin: false } });
      const { onSelected } = renderSwitcher();

      await create("Sam");
      await userEvent.click(await screen.findByRole("button", { name: "Not now" }));

      expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /sam/i })).toBeInTheDocument();
      expect(onSelected).not.toHaveBeenCalled();
      expect(verifyProfile).not.toHaveBeenCalled();
    });

    it("enters the first profile on a new install straight away", async () => {
      getProfiles.mockResolvedValue({ profiles: [] });
      const created: Profile = { id: "p-new", name: "Sam", hasPin: false };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name });
      const { onSelected } = renderSwitcher();

      await userEvent.type(await screen.findByLabelText("Profile name"), "Sam");
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
    });

    it("sends the picture and colour chosen", async () => {
      createProfile.mockResolvedValue({ profile: { id: "p-new", name: "Sam", hasPin: false } });
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.click(screen.getByRole("button", { name: "Fox" }));
      await userEvent.click(screen.getByRole("button", { name: "Ocean" }));
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));

      await waitFor(() =>
        expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: undefined, avatarId: "fetch:fox", avatarColorHex: "#1E88E5" })
      );
    });

    it("takes a PIN only once it has been typed twice the same", async () => {
      createProfile.mockResolvedValue({ profile: { id: "p-new", name: "Sam", hasPin: true } });
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), "4321");
      await userEvent.type(screen.getByLabelText("Type the PIN again"), "4312");
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));

      expect(await screen.findByRole("alert")).toHaveTextContent(/don't match/);
      expect(createProfile).not.toHaveBeenCalled();

      await userEvent.clear(screen.getByLabelText("Type the PIN again"));
      await userEvent.type(screen.getByLabelText("Type the PIN again"), "4321");
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));
      await waitFor(() => expect(createProfile).toHaveBeenCalledWith({ name: "Sam", pin: "4321" }));
    });

    it("verifies a new PIN profile with its PIN before entering it, so it isn't locked out at once", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockResolvedValue({ id: created.id, name: created.name });
      const { onSelected } = renderSwitcher();

      await create("Sam", "4321");
      await userEvent.click(await screen.findByRole("button", { name: "Switch to Sam" }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
      expect(verifyProfile).toHaveBeenCalledWith("p-new", "4321");
    });

    // #391: the switcher was closed while the create was in flight.
    it("changes nothing when the switcher closes before the new profile is ready", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      let finishCreate!: (value: { profile: Profile }) => void;
      createProfile.mockReturnValue(new Promise((resolve) => { finishCreate = resolve; }));
      runtimeConfig.setProfileId("p-previous");
      const onClose = vi.fn();
      const { onSelected } = renderSwitcher({ onClose });

      await create("Sam", "4321");
      await userEvent.keyboard("{Escape}");
      finishCreate({ profile: created });

      await waitFor(() => expect(onClose).toHaveBeenCalledOnce(), { timeout: 1000 });
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(verifyProfile).not.toHaveBeenCalled();
      expect(onSelected).not.toHaveBeenCalled();
      expect(runtimeConfig.getProfileId()).toBe("p-previous");
    });

    it("changes nothing when the form is cancelled before the new profile is ready", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: false };
      let finishCreate!: (value: { profile: Profile }) => void;
      createProfile.mockReturnValue(new Promise((resolve) => { finishCreate = resolve; }));
      const { onSelected } = renderSwitcher();

      await create("Sam");
      await userEvent.click(screen.getByRole("button", { name: /cancel/i }));
      finishCreate({ profile: created });

      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(onSelected).not.toHaveBeenCalled();
      expect(screen.getByText(/who's watching/i)).toBeInTheDocument();
      expect(screen.queryByText("Sam is ready")).not.toBeInTheDocument();
    });

    it("still enters the new profile if verifying fails", async () => {
      const created: Profile = { id: "p-new", name: "Sam", hasPin: true };
      createProfile.mockResolvedValue({ profile: created });
      verifyProfile.mockRejectedValue(new ApiError("Profile store unavailable", 500));
      const { onSelected } = renderSwitcher();

      await create("Sam", "4321");
      await userEvent.click(await screen.findByRole("button", { name: "Switch to Sam" }));

      await waitFor(() => expect(onSelected).toHaveBeenCalledWith(created));
    });

    it.each(["123", "12ab"])("explains the PIN rule instead of creating a profile with PIN %j", async (pin) => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "Sam");
      await userEvent.type(screen.getByLabelText("PIN (optional)"), pin);
      await userEvent.click(screen.getByRole("button", { name: /^create/i }));

      expect(await screen.findByRole("alert")).toHaveTextContent("PIN must be 4 to 8 digits.");
      expect(createProfile).not.toHaveBeenCalled();
    });

    it("takes digits only, up to 8, on the phone's number pad, and says so", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      const field = screen.getByLabelText("PIN (optional)");
      expect(field).toHaveAttribute("inputMode", "numeric");
      expect(field).toHaveAccessibleDescription(/4–8 digits/);

      await userEvent.type(field, "12a3456789");
      expect(field).toHaveValue("12345678");
    });

    it("refuses a pasted 9-digit PIN rather than cutting it to 8", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.click(screen.getByLabelText("PIN (optional)"));
      await userEvent.paste("123456789");

      expect(screen.getByLabelText("PIN (optional)")).toHaveValue("");
      expect(await screen.findByRole("alert")).toHaveTextContent("PIN must be 4 to 8 digits.");
    });

    it("cannot be submitted with a blank name", async () => {
      renderSwitcher();

      await userEvent.click(await screen.findByRole("button", { name: /add profile/i }));
      await userEvent.type(screen.getByLabelText("Profile name"), "   ");

      expect(screen.getByRole("button", { name: /^create/i })).toBeDisabled();
      expect(createProfile).not.toHaveBeenCalled();
    });

    it("shows the server's rejection and stays on the form", async () => {
      createProfile.mockRejectedValue(new ApiError("A profile named Sam already exists", 409));
      const { onSelected } = renderSwitcher();

      await create("Sam");

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
      await userEvent.click(screen.getByRole("button", { name: /add profile/i }));
      expect(screen.getByLabelText("Profile name")).toBeInTheDocument();
      expect(screen.getByLabelText("PIN (optional)")).toBeInTheDocument();
    });
  });
});

describe("locking the profile in use", () => {
  it("offers to lock the active PIN profile, which leaves it without signing out", async () => {
    runtimeConfig.setProfileId(LOCKED.id);
    lockProfile.mockResolvedValue(undefined);
    const locked = vi.fn();
    window.addEventListener("fetch:profile-locked", locked);
    renderSwitcher({ onClose: vi.fn() });

    await userEvent.click(await screen.findByRole("button", { name: "Lock Kid" }));

    expect(locked).toHaveBeenCalledOnce();
    expect(runtimeConfig.getProfileId()).toBe("");
    // The PIN capability is an HttpOnly cookie, so the API is asked to drop it.
    expect(lockProfile).toHaveBeenCalledOnce();
    window.removeEventListener("fetch:profile-locked", locked);
  });

  it("offers nothing to lock for a profile without a PIN, or on the first-run picker", async () => {
    runtimeConfig.setProfileId(BEN.id);
    renderSwitcher({ onClose: vi.fn() });
    expect(await screen.findByRole("button", { name: /ben/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Lock / })).not.toBeInTheDocument();
  });
});
