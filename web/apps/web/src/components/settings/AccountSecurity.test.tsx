import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountSettings } from "./AccountSettings";

// Settings → Account beyond signing out: the username, the password with its
// option to sign TVs out, where the account is signed in, recent activity and
// deleting the account (#540, #541, #542).

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      logout: vi.fn(),
      changePassword: vi.fn(),
      changeUsername: vi.fn(),
      deleteMyAccount: vi.fn(),
      getSignIns: vi.fn(),
      revokeSession: vi.fn(),
      revokeDevice: vi.fn(),
      revokeOtherSessions: vi.fn(),
      getActivity: vi.fn(),
    },
  };
});

const me = vi.hoisted(() => ({
  account: { id: "a2", username: "friend", isAdmin: false, mustChangePassword: false },
  refresh: vi.fn(),
}));
vi.mock("../../hooks/useAccount", () => ({
  useAccount: () => ({ account: me.account, isAdmin: me.account.isAdmin, refresh: me.refresh }),
}));
vi.mock("../../hooks/useToast", () => ({ useToast: () => ({ showToast: vi.fn() }) }));

const { api, ApiError } = await import("../../api");

const FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0";
const SAFARI =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1";

beforeEach(() => {
  me.account = { id: "a2", username: "friend", isAdmin: false, mustChangePassword: false };
  me.refresh.mockReset();
  vi.mocked(api.changePassword).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.changeUsername).mockReset().mockResolvedValue({ account: { ...me.account, username: "pal" } });
  vi.mocked(api.deleteMyAccount).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.revokeSession).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.revokeDevice).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.revokeOtherSessions).mockReset().mockResolvedValue({ browsers: 1, tvs: 1 });
  vi.mocked(api.getSignIns).mockReset().mockResolvedValue({
    sessions: [
      {
        kind: "browser",
        id: "s1",
        current: true,
        userAgent: FIREFOX,
        ipAddress: "10.0.0.2",
        createdAt: "2026-11-01T00:00:00Z",
        lastUsedAt: new Date().toISOString(),
        expiresAt: "2026-12-01T00:00:00Z",
      },
      {
        kind: "browser",
        id: "s2",
        current: false,
        userAgent: SAFARI,
        ipAddress: "10.0.0.9",
        createdAt: "2026-11-02T00:00:00Z",
        lastUsedAt: "2026-11-03T00:00:00Z",
        expiresAt: "2026-12-03T00:00:00Z",
      },
      {
        kind: "tv",
        id: "d1",
        current: false,
        name: "Living room",
        appVersion: "2.1.0",
        createdAt: "2026-10-01T00:00:00Z",
        lastUsedAt: null,
      },
    ],
  });
  vi.mocked(api.getActivity).mockReset().mockResolvedValue({
    events: [
      { id: "e2", kind: "sign_in_failed", detail: null, ipAddress: "203.0.113.4", userAgent: null, createdAt: "2026-11-05T00:00:00Z" },
      { id: "e1", kind: "tv_paired", detail: "Living room", ipAddress: null, userAgent: null, createdAt: "2026-11-04T00:00:00Z" },
    ],
  });
});

describe("where you're signed in", () => {
  it("lists each browser by name and each TV, and marks this browser", async () => {
    render(<AccountSettings />);
    const list = await screen.findByRole("list", { name: /where you're signed in/i });

    expect(within(list).getByText(/Firefox on Linux/)).toBeInTheDocument();
    expect(within(list).getByText("This browser")).toBeInTheDocument();
    expect(within(list).getByText(/Safari on iPhone/)).toBeInTheDocument();
    expect(within(list).getByText("Living room")).toBeInTheDocument();
    // This browser is signed out with "Sign out" at the top, not from the list.
    expect(within(list).queryByRole("button", { name: /sign out firefox/i })).not.toBeInTheDocument();
  });

  it("signs out one browser after confirming, and a TV through its own route", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(await screen.findByRole("button", { name: "Sign out Safari on iPhone" }));
    expect(api.revokeSession).not.toHaveBeenCalled();
    const review = screen.getByRole("alertdialog", { name: /sign out safari on iphone/i });
    await user.click(within(review).getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(api.revokeSession).toHaveBeenCalledWith("s2"));

    await user.click(screen.getByRole("button", { name: "Sign out Living room" }));
    await user.click(within(screen.getByRole("alertdialog", { name: /living room/i })).getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(api.revokeDevice).toHaveBeenCalledWith("d1"));
  });

  it("signs out everywhere else, and TVs only when ticked", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(await screen.findByRole("button", { name: /sign out everywhere else/i }));
    const review = screen.getByRole("alertdialog", { name: /sign out everywhere else/i });
    await user.click(within(review).getByRole("checkbox", { name: /sign out the tv too/i }));
    await user.click(within(review).getByRole("button", { name: /sign out everywhere else/i }));

    await waitFor(() => expect(api.revokeOtherSessions).toHaveBeenCalledWith(true));
  });
});

describe("recent activity", () => {
  it("lists what happened, with failed attempts called out", async () => {
    render(<AccountSettings />);
    const list = await screen.findByRole("list", { name: /recent activity/i });

    expect(within(list).getByText("Failed sign-in attempt")).toHaveClass("text-danger");
    expect(within(list).getByText(/203\.0\.113\.4/)).toBeInTheDocument();
    expect(within(list).getByText("TV signed in")).toBeInTheDocument();
    expect(within(list).getByText(/Living room/)).toBeInTheDocument();
  });
});

describe("changing the password", () => {
  it("labels every field, states the rule, and can sign TVs out too", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.type(screen.getByLabelText("Current password"), "old-password");
    await user.type(screen.getByLabelText("New password"), "new-password-1");
    await user.type(screen.getByLabelText("Confirm new password"), "new-password-1");
    await user.click(screen.getByRole("checkbox", { name: /also sign out every tv/i }));
    await user.click(screen.getByRole("button", { name: "Change password" }));

    await waitFor(() =>
      expect(api.changePassword).toHaveBeenCalledWith("old-password", "new-password-1", { signOutTvs: true })
    );
    expect(await screen.findByText(/every tv have been signed out/i)).toBeInTheDocument();
  });

  it("refuses a short password before asking the server", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.type(screen.getByLabelText("Current password"), "old-password");
    await user.type(screen.getByLabelText("New password"), "short");
    await user.type(screen.getByLabelText("Confirm new password"), "short");
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText(/use at least 8 characters/i)).toBeInTheDocument();
    expect(api.changePassword).not.toHaveBeenCalled();
  });

  it("asks for a new password when someone else set this one", () => {
    me.account = { ...me.account, mustChangePassword: true };
    render(<AccountSettings />);
    expect(screen.getByText(/your password was set by someone else/i)).toBeInTheDocument();
  });

  it("shows the eye toggle on each password field", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);
    const field = screen.getByLabelText("New password");
    expect(field).toHaveAttribute("type", "password");
    await user.click(screen.getByRole("button", { name: "Show new password" }));
    expect(field).toHaveAttribute("type", "text");
  });
});

describe("the username", () => {
  it("changes with the current password", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(screen.getByRole("button", { name: /change username/i }));
    const field = screen.getByLabelText("New username");
    await user.clear(field);
    await user.type(field, "Pal");
    const form = field.closest("form")!;
    await user.type(within(form).getByLabelText("Current password"), "friend-password");
    await user.click(screen.getByRole("button", { name: /save username/i }));

    await waitFor(() => expect(api.changeUsername).toHaveBeenCalledWith("pal", "friend-password"));
    expect(me.refresh).toHaveBeenCalled();
  });
});

describe("deleting the account", () => {
  it("asks for the password, then deletes and signs out", async () => {
    const unauthorized = vi.fn();
    window.addEventListener("fetch:unauthorized", unauthorized);
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(screen.getByRole("button", { name: /delete my account/i }));
    const review = screen.getByRole("alertdialog", { name: /delete your account/i });
    const confirm = within(review).getByRole("button", { name: /delete my account/i });
    expect(confirm).toBeDisabled();
    await user.type(within(review).getByLabelText("Your password"), "friend-password");
    await user.click(confirm);

    await waitFor(() => expect(api.deleteMyAccount).toHaveBeenCalledWith("friend-password"));
    await waitFor(() => expect(unauthorized).toHaveBeenCalled());
    window.removeEventListener("fetch:unauthorized", unauthorized);
  });

  it("says the password was wrong", async () => {
    vi.mocked(api.deleteMyAccount).mockRejectedValue(new ApiError("Incorrect password", 401));
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(screen.getByRole("button", { name: /delete my account/i }));
    const review = screen.getByRole("alertdialog", { name: /delete your account/i });
    await user.type(within(review).getByLabelText("Your password"), "nope");
    await user.click(within(review).getByRole("button", { name: /delete my account/i }));

    expect(await within(review).findByRole("alert")).toHaveTextContent("That password is incorrect.");
  });

  it("isn't offered to the admin, who hands admin on first", () => {
    me.account = { ...me.account, isAdmin: true };
    render(<AccountSettings />);
    expect(screen.queryByRole("button", { name: /delete my account/i })).not.toBeInTheDocument();
    expect(screen.getByText(/make another account the admin/i)).toBeInTheDocument();
  });
});
