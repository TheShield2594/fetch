import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountSettings } from "./AccountSettings";
import { InvitesSettings } from "./InvitesSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      logout: vi.fn(),
      changePassword: vi.fn(),
      createInvite: vi.fn(),
      getInvites: vi.fn(),
      getAccounts: vi.fn(),
      revokeInvite: vi.fn(),
      deleteAccount: vi.fn(),
    },
  };
});

const me = vi.hoisted(() => ({ account: { id: "a1", username: "owner", isAdmin: true } }));
vi.mock("../../hooks/useAccount", () => ({
  useAccount: () => ({ account: me.account, isAdmin: me.account.isAdmin, refresh: () => {} }),
}));

const { api } = await import("../../api");

beforeEach(() => {
  vi.mocked(api.logout).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.changePassword).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.getInvites).mockReset().mockResolvedValue({ invites: [] });
  vi.mocked(api.getAccounts).mockReset().mockResolvedValue({
    accounts: [
      { id: "a1", username: "owner", isAdmin: true, createdAt: "2026-10-01T00:00:00Z", profileCount: 2 },
      { id: "a2", username: "friend", isAdmin: false, createdAt: "2026-10-02T00:00:00Z", profileCount: 1 },
    ],
  });
  vi.mocked(api.createInvite).mockReset().mockResolvedValue({
    invite: { id: "i1", code: "the-code", expiresAt: "2026-11-02T00:00:00Z" },
    url: "https://watchfetch.tv/invite/the-code",
  });
  vi.mocked(api.deleteAccount).mockReset().mockResolvedValue(undefined);
});

describe("AccountSettings", () => {
  it("says who is signed in", () => {
    render(<AccountSettings />);
    expect(screen.getByText("owner")).toBeInTheDocument();
  });

  it("signs out and sends the app back to sign-in", async () => {
    const unauthorized = vi.fn();
    window.addEventListener("fetch:unauthorized", unauthorized);
    const user = userEvent.setup();
    render(<AccountSettings />);

    await user.click(screen.getByRole("button", { name: /sign out/i }));

    await waitFor(() => expect(unauthorized).toHaveBeenCalled());
    expect(api.logout).toHaveBeenCalled();
    window.removeEventListener("fetch:unauthorized", unauthorized);
  });

  it("changes the password, and refuses new passwords that don't match", async () => {
    const user = userEvent.setup();
    render(<AccountSettings />);
    await user.type(screen.getByLabelText("Current password"), "old-password");
    await user.type(screen.getByLabelText("New password"), "new-password");
    await user.type(screen.getByLabelText("Confirm new password"), "typo-password");
    await user.click(screen.getByRole("button", { name: /change password/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/don't match/i);
    expect(api.changePassword).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText("Confirm new password"));
    await user.type(screen.getByLabelText("Confirm new password"), "new-password");
    await user.click(screen.getByRole("button", { name: /change password/i }));

    expect(await screen.findByRole("status")).toHaveTextContent(/password changed/i);
    expect(api.changePassword).toHaveBeenCalledWith("old-password", "new-password");
  });
});

describe("InvitesSettings", () => {
  it("shows a new invite's link, once, ready to copy", async () => {
    const user = userEvent.setup();
    render(<InvitesSettings />);

    await user.click(screen.getByRole("button", { name: /create invite link/i }));

    expect(await screen.findByLabelText("Invite link")).toHaveValue("https://watchfetch.tv/invite/the-code");
  });

  it("lists the accounts and removes one after confirming, never the admin", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<InvitesSettings />);

    expect(await screen.findByText("friend")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove owner" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Remove friend" }));

    expect(confirm).toHaveBeenCalled();
    await waitFor(() => expect(api.deleteAccount).toHaveBeenCalledWith("a2"));
    confirm.mockRestore();
  });
});
