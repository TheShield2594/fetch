import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SetupWizard, WIZARD_STEPS, previousStep } from "./SetupWizard";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getAuthStatus: vi.fn(),
      bootstrapAdmin: vi.fn(),
      login: vi.fn(),
      register: vi.fn(),
      changePassword: vi.fn(),
      getTmdbStatus: vi.fn(),
      setTmdbKey: vi.fn(),
      removeTmdbKey: vi.fn(),
      getProfiles: vi.fn(),
      updateProfile: vi.fn(),
      verifyProfile: vi.fn(),
      getTraktStatus: vi.fn(),
      getSimklStatus: vi.fn(),
      getMdbListStatus: vi.fn(),
      getMediaServers: vi.fn(),
      confirmPairing: vi.fn(),
    },
  };
});

// Each talks to the server and opens a sign-in flow; the wizard only places them.
vi.mock("../components/settings/TraktSettings", () => ({ TraktSettings: () => <p>trakt settings</p> }));
vi.mock("../components/settings/TrackerSignInSettings", () => ({
  SimklSettings: () => <p>simkl settings</p>,
  MdbListSettings: () => <p>mdblist settings</p>,
}));
vi.mock("../components/settings/MediaServersSettings", () => ({ MediaServersSettings: () => <p>media server settings</p> }));

const { api } = await import("../api");
const getAuthStatus = vi.mocked(api.getAuthStatus);
const bootstrapAdmin = vi.mocked(api.bootstrapAdmin);
const login = vi.mocked(api.login);
const register = vi.mocked(api.register);
const changePassword = vi.mocked(api.changePassword);
const getTmdbStatus = vi.mocked(api.getTmdbStatus);
const setTmdbKey = vi.mocked(api.setTmdbKey);
const getProfiles = vi.mocked(api.getProfiles);
const updateProfile = vi.mocked(api.updateProfile);
const verifyProfile = vi.mocked(api.verifyProfile);
const getTraktStatus = vi.mocked(api.getTraktStatus);
const getSimklStatus = vi.mocked(api.getSimklStatus);
const getMdbListStatus = vi.mocked(api.getMdbListStatus);
const getMediaServers = vi.mocked(api.getMediaServers);
const confirmPairing = vi.mocked(api.confirmPairing);

const DEFAULT_PROFILE = { id: "p1", name: "Default", hasPin: false };

const ADMIN = { id: "a1", username: "owner", isAdmin: true };
const FRIEND = { id: "a2", username: "friend", isAdmin: false };

beforeEach(() => {
  getAuthStatus.mockReset().mockResolvedValue({ setupRequired: true });
  bootstrapAdmin.mockReset().mockResolvedValue(ADMIN);
  login.mockReset().mockResolvedValue(FRIEND);
  register.mockReset().mockResolvedValue(FRIEND);
  changePassword.mockReset().mockResolvedValue(undefined);
  getTmdbStatus.mockReset().mockResolvedValue({ configured: true, source: "env" });
  setTmdbKey.mockReset().mockResolvedValue({ configured: true, source: "db" });
  getProfiles.mockReset().mockResolvedValue({ profiles: [DEFAULT_PROFILE] });
  updateProfile.mockReset().mockImplementation((id, payload) =>
    Promise.resolve({ profile: { ...DEFAULT_PROFILE, id, name: payload.name ?? DEFAULT_PROFILE.name } })
  );
  verifyProfile.mockReset().mockImplementation((id) => Promise.resolve({ id, name: "Owner" }));
  // A server with Simkl's client id and Plex/Jellyfin, but none for Trakt or MDBList.
  getTraktStatus.mockReset().mockResolvedValue({ connected: false, configured: false, expiresAt: null, redirectUri: "" });
  getSimklStatus.mockReset().mockResolvedValue({ configured: true, connected: false, lastSyncedAt: null } as never);
  getMdbListStatus.mockReset().mockResolvedValue({ configured: false, connected: false, lastSyncedAt: null } as never);
  getMediaServers.mockReset().mockResolvedValue({
    providers: [{ id: "plex", name: "Plex", signIn: "link" }],
    servers: [],
    plexLink: null,
  } as never);
  confirmPairing.mockReset().mockResolvedValue({ deviceName: "Living room" } as never);
});

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

type User = ReturnType<typeof userEvent.setup>;

async function fillNewAccount(user: User, password = "a-good-password", confirm = password) {
  await user.type(screen.getByLabelText("Username"), "Owner");
  await user.type(screen.getByLabelText("Password"), password);
  await user.type(screen.getByLabelText("Confirm password"), confirm);
}

/** Creates the admin and lands on the profile step. */
async function createAdmin(user: User) {
  await user.type(await screen.findByLabelText("API token"), "secret-token");
  await fillNewAccount(user);
  await user.click(screen.getByRole("button", { name: /create account/i }));
  expect(await screen.findByRole("heading", { name: "Who's watching?" })).toBeInTheDocument();
}

/** Keeps the suggested name and lands on the TMDB step. */
async function nameProfile(user: User) {
  await screen.findByDisplayValue("owner");
  await user.click(screen.getByRole("button", { name: /continue/i }));
  expect(await screen.findByRole("heading", { name: "Artwork and search" })).toBeInTheDocument();
}

describe("previousStep", () => {
  it("has no step before the entry point", () => {
    expect(previousStep("account")).toBeNull();
  });

  it("walks back one step at a time", () => {
    expect(WIZARD_STEPS.map(previousStep)).toEqual([null, "account", "profile", "tmdb", "history", "tv"]);
  });
});

describe("SetupWizard, first run", () => {
  it("creates the admin with the API token and the chosen name and password", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/create the admin account/i)).toBeInTheDocument();
    await createAdmin(user);

    expect(bootstrapAdmin).toHaveBeenCalledWith("secret-token", "Owner", "a-good-password");
  });

  it("labels the progress indicator with the step it is on", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    const progress = await screen.findByRole("progressbar", { name: /setup progress/i });
    expect(progress).toHaveAttribute("aria-valuenow", "1");
    expect(progress).toHaveAttribute("aria-valuemax", String(WIZARD_STEPS.length));

    await createAdmin(user);

    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "2");
    expect(screen.getByText(`Step 2 of ${WIZARD_STEPS.length}`)).toBeInTheDocument();
  });

  it("refuses passwords that don't match without asking the server", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await user.type(await screen.findByLabelText("API token"), "secret-token");
    await fillNewAccount(user, "a-good-password", "a-different-one");

    await user.click(screen.getByRole("button", { name: /create account/i }));

    const confirm = screen.getByLabelText("Confirm password");
    expect(confirm).toHaveAccessibleDescription(/don't match/i);
    expect(confirm).toHaveAttribute("aria-invalid", "true");
    expect(confirm).toHaveFocus();
    expect(bootstrapAdmin).not.toHaveBeenCalled();
  });

  // The button used to stay disabled until every field was filled, without
  // saying which one it was waiting for.
  it("names the missing field next to it and focuses it", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await screen.findByLabelText("API token");
    await fillNewAccount(user, "a-good-password", "a-good-password");

    const submit = screen.getByRole("button", { name: /create account/i });
    expect(submit).toBeEnabled();
    await user.click(submit);

    const token = screen.getByLabelText("API token");
    expect(token).toHaveAccessibleDescription(/api token/i);
    expect(token).toHaveAttribute("aria-invalid", "true");
    expect(token).toHaveFocus();
    expect(bootstrapAdmin).not.toHaveBeenCalled();
  });

  // Onboarding used to dead-end here on a server without a key: it named the
  // environment variable to set and told you to restart.
  it("takes a TMDB key without leaving setup", async () => {
    getTmdbStatus.mockResolvedValue({ configured: false, source: null });
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);

    await user.type(await screen.findByLabelText("TMDB API key"), "tmdb-key");
    await user.click(screen.getByRole("button", { name: /save tmdb key/i }));

    await waitFor(() => expect(setTmdbKey).toHaveBeenCalledWith("tmdb-key"));
    expect(await screen.findByText("Active")).toBeInTheDocument();
  });

  it("offers no way back from the entry step", async () => {
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/create the admin account/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /back/i })).not.toBeInTheDocument();
  });

  it("steps back to the account step as signed in, not as a second sign-up", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);

    await user.click(screen.getByRole("button", { name: /back/i }));

    expect(await screen.findByText(/signed in as/i)).toHaveTextContent("owner");
    await user.click(screen.getByRole("button", { name: /continue/i }));
    expect(await screen.findByRole("heading", { name: "Who's watching?" })).toBeInTheDocument();
  });

  it("walks back through every step after the first", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);

    await user.click(await screen.findByRole("button", { name: /continue/i }));
    expect(await screen.findByRole("heading", { name: "Bring your history" })).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: /continue/i }));
    expect(await screen.findByRole("heading", { name: /have a fetch tv/i })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /skip/i }));
    expect(await screen.findByText(/you're all set/i)).toBeInTheDocument();

    for (const heading of [/have a fetch tv/i, /bring your history/i, /artwork and search/i, /who's watching/i]) {
      await user.click(screen.getByRole("button", { name: /back/i }));
      expect(await screen.findByRole("heading", { name: heading })).toBeInTheDocument();
    }

    await user.click(screen.getByRole("button", { name: /back/i }));
    await waitFor(() => expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "1"));
  });

  // #533: nobody was asked their name; the first profile was "Default".
  it("names the first profile after the person, and opens it", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);

    const name = await screen.findByLabelText("Your name");
    expect(name).toHaveValue("owner");
    await user.clear(name);
    await user.type(name, "Sam");
    await user.click(screen.getByRole("button", { name: /continue/i }));

    await screen.findByRole("heading", { name: "Artwork and search" });
    expect(updateProfile).toHaveBeenCalledWith("p1", { name: "Sam", avatarId: null, avatarColorHex: null });
    expect(verifyProfile).toHaveBeenCalledWith("p1");
  });

  it("says what is lost before going on without a TMDB key", async () => {
    getTmdbStatus.mockResolvedValue({ configured: false, source: null });
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);

    await user.click(await screen.findByRole("button", { name: /skip for now/i }));
    expect(screen.getByRole("alert")).toHaveTextContent(/search finds nothing/i);
    await user.click(screen.getByRole("button", { name: /go on without artwork/i }));

    expect(await screen.findByRole("heading", { name: "Bring your history" })).toBeInTheDocument();
  });

  // No step offers what the server can't do.
  it("lists only the history sources this server can offer", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);
    await user.click(await screen.findByRole("button", { name: /continue/i }));

    expect(await screen.findByRole("button", { name: /simkl/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /plex, jellyfin or emby/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /trakt/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /mdblist/i })).toBeNull();

    await user.click(screen.getByRole("button", { name: /simkl/i }));
    expect(screen.getByText("simkl settings")).toBeInTheDocument();
  });

  it("leaves a profile with a PIN as it is, rather than editing it without the PIN", async () => {
    getProfiles.mockResolvedValue({ profiles: [{ ...DEFAULT_PROFILE, name: "Sam", hasPin: true }] });
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);

    expect(await screen.findByText(/has a PIN/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /continue/i }));

    expect(await screen.findByRole("heading", { name: "Artwork and search" })).toBeInTheDocument();
    expect(updateProfile).not.toHaveBeenCalled();
  });

  it("says a source check failed, rather than that the server offers none, and retries", async () => {
    getSimklStatus.mockRejectedValueOnce(new Error("timeout"));
    getMediaServers.mockRejectedValueOnce(new Error("timeout"));
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);
    await user.click(await screen.findByRole("button", { name: /continue/i }));

    expect(await screen.findByText(/couldn't check every source/i)).toBeInTheDocument();
    expect(screen.queryByText(/isn't set up for any history sources/i)).toBeNull();
    await user.click(screen.getByRole("button", { name: /try again/i }));

    expect(await screen.findByRole("button", { name: /simkl/i })).toBeInTheDocument();
    expect(screen.queryByText(/couldn't check every source/i)).toBeNull();
  });

  it("signs in a TV during setup", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);
    await nameProfile(user);
    await user.click(await screen.findByRole("button", { name: /continue/i }));
    await user.click(await screen.findByRole("button", { name: /continue/i }));

    await user.type(await screen.findByLabelText("Code shown on the TV"), "abcdefgh");
    await user.click(screen.getByRole("button", { name: /sign in tv/i }));

    expect(await screen.findByText("Living room is signed in.")).toBeInTheDocument();
    expect(confirmPairing).toHaveBeenCalledWith("ABCD-EFGH", undefined);
  });
});

describe("SetupWizard, an install that is already set up", () => {
  it("signs in and goes straight into the app", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false });
    const onComplete = vi.fn();
    const user = userEvent.setup();
    render(<SetupWizard onComplete={onComplete} />);

    expect(await screen.findByRole("heading", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("API token")).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("Username"), "friend");
    await user.type(screen.getByLabelText("Password"), "friend-password");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(login).toHaveBeenCalledWith("friend", "friend-password");
  });

  it("says where to go when the password is forgotten", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false });
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/forgot your password\? ask the admin/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /recovery guide/i })).toHaveAttribute(
      "href",
      expect.stringContaining("troubleshooting.md")
    );
  });

  it("asks for a new password after signing in with a temporary one", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false });
    login.mockResolvedValue({ ...FRIEND, mustChangePassword: true });
    const onComplete = vi.fn();
    const user = userEvent.setup();
    render(<SetupWizard onComplete={onComplete} />);

    await user.type(await screen.findByLabelText("Username"), "friend");
    await user.type(screen.getByLabelText("Password"), "temp-pass-123");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("heading", { name: /choose a new password/i })).toBeInTheDocument();
    expect(onComplete).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("New password"), "short");
    await user.type(screen.getByLabelText("Confirm new password"), "short");
    await user.click(screen.getByRole("button", { name: /save and continue/i }));
    expect(await screen.findByText(/use at least 8 characters/i)).toBeInTheDocument();
    expect(changePassword).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText("New password"));
    await user.clear(screen.getByLabelText("Confirm new password"));
    await user.type(screen.getByLabelText("New password"), "my-own-password");
    await user.type(screen.getByLabelText("Confirm new password"), "my-own-password");
    await user.click(screen.getByRole("button", { name: /save and continue/i }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(changePassword).toHaveBeenCalledWith("temp-pass-123", "my-own-password");
  });

  it("shows what went wrong when the server can't be reached, and retries", async () => {
    getAuthStatus.mockRejectedValueOnce(new Error("Network error")).mockResolvedValueOnce({ setupRequired: false });
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Network error");
    await user.click(screen.getByRole("button", { name: /try again/i }));

    expect(await screen.findByRole("heading", { name: /sign in/i })).toBeInTheDocument();
  });
});

describe("SetupWizard, choosing a password", () => {
  it("states the length rule up front and refuses a short password without asking the server", async () => {
    window.history.replaceState(null, "", "/invite/abc_DEF-123");
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText("At least 8 characters")).toBeInTheDocument();
    await fillNewAccount(user, "short", "short");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/use at least 8 characters/i)).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });
});

describe("SetupWizard, an invite link", () => {
  it("creates the invited account from the code in the link, then leaves the link", async () => {
    window.history.replaceState(null, "", "/invite/abc_DEF-123");
    const onComplete = vi.fn();
    const user = userEvent.setup();
    render(<SetupWizard onComplete={onComplete} />);

    expect(await screen.findByText(/you've been invited/i)).toBeInTheDocument();
    expect(getAuthStatus).not.toHaveBeenCalled();
    await fillNewAccount(user);
    await user.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(register).toHaveBeenCalledWith("abc_DEF-123", "Owner", "a-good-password");
    expect(window.location.pathname).toBe("/");
  });
});
