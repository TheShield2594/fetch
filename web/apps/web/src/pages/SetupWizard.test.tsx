import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EmailLinkPage, SetupWizard, WIZARD_STEPS, previousStep } from "./SetupWizard";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getAuthStatus: vi.fn(),
      bootstrapAdmin: vi.fn(),
      login: vi.fn(),
      register: vi.fn(),
      forgotPassword: vi.fn(),
      verifyEmail: vi.fn(),
      resetPassword: vi.fn(),
      getTmdbStatus: vi.fn(),
      setTmdbKey: vi.fn(),
      removeTmdbKey: vi.fn(),
    },
  };
});

// Talks to the server and opens an OAuth flow; the wizard only places it.
vi.mock("../components/settings/TraktSettings", () => ({ TraktSettings: () => <p>trakt settings</p> }));

const { api } = await import("../api");
const getAuthStatus = vi.mocked(api.getAuthStatus);
const bootstrapAdmin = vi.mocked(api.bootstrapAdmin);
const login = vi.mocked(api.login);
const register = vi.mocked(api.register);
const forgotPassword = vi.mocked(api.forgotPassword);
const verifyEmail = vi.mocked(api.verifyEmail);
const resetPassword = vi.mocked(api.resetPassword);
const getTmdbStatus = vi.mocked(api.getTmdbStatus);
const setTmdbKey = vi.mocked(api.setTmdbKey);

const ADMIN = { id: "a1", username: "owner", isAdmin: true };
const FRIEND = { id: "a2", username: "friend", isAdmin: false };

beforeEach(() => {
  getAuthStatus.mockReset().mockResolvedValue({ setupRequired: true });
  bootstrapAdmin.mockReset().mockResolvedValue(ADMIN);
  login.mockReset().mockResolvedValue(FRIEND);
  register.mockReset().mockResolvedValue(FRIEND);
  forgotPassword.mockReset().mockResolvedValue({ ok: true });
  verifyEmail.mockReset().mockResolvedValue(undefined);
  resetPassword.mockReset().mockResolvedValue(FRIEND);
  getTmdbStatus.mockReset().mockResolvedValue({ configured: true, source: "env" });
  setTmdbKey.mockReset().mockResolvedValue({ configured: true, source: "db" });
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

/** Creates the admin and lands on the tmdb step. */
async function createAdmin(user: User) {
  await user.type(await screen.findByLabelText("API token"), "secret-token");
  await fillNewAccount(user);
  await user.click(screen.getByRole("button", { name: /create account/i }));
  expect(await screen.findByText(/tmdb metadata/i)).toBeInTheDocument();
}

describe("previousStep", () => {
  it("has no step before the entry point", () => {
    expect(previousStep("account")).toBeNull();
  });

  it("walks back one step at a time", () => {
    expect(WIZARD_STEPS.map(previousStep)).toEqual([null, "account", "tmdb", "trakt"]);
  });
});

describe("SetupWizard, first run", () => {
  it("creates the admin with the API token and the chosen name and password", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/create the admin account/i)).toBeInTheDocument();
    await createAdmin(user);

    expect(bootstrapAdmin).toHaveBeenCalledWith("secret-token", "Owner", "a-good-password", "");
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
    expect(await screen.findByText(/tmdb metadata/i)).toBeInTheDocument();
  });

  it("walks back through every step after the first", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);
    await createAdmin(user);

    await user.click(screen.getByRole("button", { name: /continue/i }));
    expect(await screen.findByText(/connect trakt/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /continue/i }));
    expect(await screen.findByText(/you're all set/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /back/i }));
    expect(await screen.findByText(/connect trakt/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /back/i }));
    expect(await screen.findByText(/tmdb metadata/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /back/i }));
    await waitFor(() => expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "1"));
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

    expect(screen.queryByLabelText("Email (optional)")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Username or email"), "friend");
    await user.type(screen.getByLabelText("Password"), "friend-password");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(login).toHaveBeenCalledWith("friend", "friend-password");
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

describe("SetupWizard, an invite link", () => {
  it("creates the invited account from the code in the link, then leaves the link", async () => {
    window.history.replaceState(null, "", "/invite/abc_DEF-123");
    const onComplete = vi.fn();
    const user = userEvent.setup();
    render(<SetupWizard onComplete={onComplete} />);

    expect(await screen.findByText(/you've been invited/i)).toBeInTheDocument();
    await fillNewAccount(user);
    await user.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(register).toHaveBeenCalledWith("abc_DEF-123", "Owner", "a-good-password", "");
    expect(window.location.pathname).toBe("/");
  });

  it("shows the invite form even when the server's status can't be read", async () => {
    getAuthStatus.mockRejectedValue(new Error("Network error"));
    window.history.replaceState(null, "", "/invite/abc_DEF-123");
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/you've been invited/i)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("SetupWizard, email", () => {
  it("passes an email given at sign-up, and says it will be confirmed when the server can send mail", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false, emailEnabled: true });
    window.history.replaceState(null, "", "/invite/abc_DEF-123");
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByText(/send a link to confirm it/i)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Username"), "Owner");
    await user.type(screen.getByLabelText("Email (optional)"), "owner@example.com");
    await user.type(screen.getByLabelText("Password"), "a-good-password");
    await user.type(screen.getByLabelText("Confirm password"), "a-good-password");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() =>
      expect(register).toHaveBeenCalledWith("abc_DEF-123", "Owner", "a-good-password", "owner@example.com")
    );
  });

  it("refuses something that isn't an address", async () => {
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    await user.type(await screen.findByLabelText("API token"), "secret-token");
    await fillNewAccount(user);
    await user.type(screen.getByLabelText("Email (optional)"), "owner");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/valid email address/i)).toBeInTheDocument();
    expect(bootstrapAdmin).not.toHaveBeenCalled();
  });

  it("offers a reset link on the sign-in form only when the server can send mail", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false, emailEnabled: true });
    const user = userEvent.setup();
    render(<SetupWizard onComplete={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: /forgot your password/i }));
    await user.type(screen.getByLabelText("Email"), "friend@example.com");
    await user.click(screen.getByRole("button", { name: /send reset link/i }));

    expect(await screen.findByRole("status")).toHaveTextContent(/reset link is on its way/i);
    expect(forgotPassword).toHaveBeenCalledWith("friend@example.com");
  });

  it("hides the reset link when the server can't send mail", async () => {
    getAuthStatus.mockResolvedValue({ setupRequired: false, emailEnabled: false });
    render(<SetupWizard onComplete={vi.fn()} />);

    expect(await screen.findByRole("heading", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /forgot your password/i })).not.toBeInTheDocument();
  });
});

describe("EmailLinkPage", () => {
  it("confirms an address from the link, once", async () => {
    const onDone = vi.fn();
    const user = userEvent.setup();
    render(<EmailLinkPage link={{ kind: "verify", token: "tok" }} onDone={onDone} />);

    expect(await screen.findByRole("status")).toHaveTextContent(/confirmed/i);
    expect(verifyEmail).toHaveBeenCalledTimes(1);
    expect(verifyEmail).toHaveBeenCalledWith("tok");
    await user.click(screen.getByRole("button", { name: /continue to fetch/i }));
    expect(onDone).toHaveBeenCalled();
  });

  it("says when a confirmation link has expired", async () => {
    verifyEmail.mockRejectedValue(new Error("This confirmation link is invalid or has expired"));
    render(<EmailLinkPage link={{ kind: "verify", token: "tok" }} onDone={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(/expired/i);
  });

  it("sets a new password from a reset link", async () => {
    const user = userEvent.setup();
    render(<EmailLinkPage link={{ kind: "reset", token: "tok" }} onDone={vi.fn()} />);

    await user.type(screen.getByLabelText("New password"), "brand-new-password");
    await user.type(screen.getByLabelText("Confirm password"), "brand-new-password");
    await user.click(screen.getByRole("button", { name: /set password/i }));

    expect(await screen.findByRole("status")).toHaveTextContent(/signed in as friend/i);
    expect(resetPassword).toHaveBeenCalledWith("tok", "brand-new-password");
  });
});
