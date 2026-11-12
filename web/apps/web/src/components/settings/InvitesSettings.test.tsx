import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InvitesSettings } from "./InvitesSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      createInvite: vi.fn(),
      getInvites: vi.fn(),
      getAccounts: vi.fn(),
      revokeInvite: vi.fn(),
      deleteAccount: vi.fn(),
      setMemberPassword: vi.fn(),
      makeAdmin: vi.fn(),
    },
  };
});

const refresh = vi.hoisted(() => vi.fn());
const me = vi.hoisted(() => ({ account: { id: "a1", username: "owner", isAdmin: true } }));
vi.mock("../../hooks/useAccount", () => ({
  useAccount: () => ({ account: me.account, isAdmin: me.account.isAdmin, refresh }),
}));

const { api, ApiError } = await import("../../api");

const FUTURE = new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString();

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getInvites).mockResolvedValue({
    invites: [{ id: "i9", createdAt: "2026-11-01T00:00:00Z", expiresAt: FUTURE, usedAt: null, usedBy: null }],
  });
  vi.mocked(api.getAccounts).mockResolvedValue({
    accounts: [
      { id: "a1", username: "owner", isAdmin: true, createdAt: "2026-10-01T00:00:00Z", profileCount: 2 },
      { id: "a2", username: "friend", isAdmin: false, createdAt: "2026-10-02T00:00:00Z", profileCount: 1 },
    ],
  });
  vi.mocked(api.createInvite).mockResolvedValue({
    invite: { id: "i1", code: "the-code", expiresAt: "2026-11-02T00:00:00Z" },
    url: "https://watchfetch.tv/invite/the-code",
  });
  vi.mocked(api.deleteAccount).mockResolvedValue(undefined);
  vi.mocked(api.revokeInvite).mockResolvedValue(undefined);
  vi.mocked(api.setMemberPassword).mockResolvedValue(undefined);
  vi.mocked(api.makeAdmin).mockResolvedValue(undefined);
});

describe("InvitesSettings", () => {
  it("shows a new invite's link, once, ready to copy, for the chosen number of days", async () => {
    const user = userEvent.setup();
    render(<InvitesSettings />);

    await user.selectOptions(screen.getByLabelText("Invite link works for"), "14");
    await user.click(screen.getByRole("button", { name: /create invite link/i }));

    expect(await screen.findByLabelText("Invite link")).toHaveValue("https://watchfetch.tv/invite/the-code");
    expect(api.createInvite).toHaveBeenCalledWith(14);
  });

  it("revokes an invite only after confirming", async () => {
    const user = userEvent.setup();
    render(<InvitesSettings />);

    await user.click(await screen.findByRole("button", { name: "Revoke invite" }));
    expect(api.revokeInvite).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Revoke invite" }));

    await waitFor(() => expect(api.revokeInvite).toHaveBeenCalledWith("i9"));
  });

  it("removes an account after an inline review, never the admin", async () => {
    const user = userEvent.setup();
    render(<InvitesSettings />);

    expect(await screen.findByText("friend")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove owner" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Remove friend" }));
    expect(screen.getByRole("alertdialog", { name: "Remove friend?" })).toHaveTextContent(/1 profile/);
    expect(api.deleteAccount).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Remove account" }));

    await waitFor(() => expect(api.deleteAccount).toHaveBeenCalledWith("a2"));
  });

  it("sets a temporary password, checking the length first", async () => {
    const user = userEvent.setup();
    render(<InvitesSettings />);

    await user.click(await screen.findByRole("button", { name: "Set a temporary password for friend" }));
    await user.type(screen.getByLabelText("Temporary password"), "short");
    await user.type(screen.getByLabelText("Your password"), "owner-password");
    await user.click(screen.getByRole("button", { name: "Set temporary password" }));
    expect(api.setMemberPassword).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Temporary password"), "-but-longer");
    await user.click(screen.getByRole("button", { name: "Set temporary password" }));

    await waitFor(() => expect(api.setMemberPassword).toHaveBeenCalledWith("a2", "short-but-longer", "owner-password"));
    expect(await screen.findByRole("status")).toHaveTextContent(/pass it on/i);
  });

  it("hands admin on with the admin's password, and says when it's wrong", async () => {
    const user = userEvent.setup();
    vi.mocked(api.makeAdmin).mockRejectedValueOnce(new ApiError("Incorrect password", 401));
    render(<InvitesSettings />);

    await user.click(await screen.findByRole("button", { name: "Make friend the admin" }));
    const review = screen.getByRole("alertdialog", { name: "Make friend the admin?" });
    expect(within(review).getByRole("button", { name: "Make admin" })).toBeDisabled();
    await user.type(within(review).getByLabelText("Your password"), "wrong-password");
    await user.click(within(review).getByRole("button", { name: "Make admin" }));
    expect(await within(review).findByRole("alert")).toHaveTextContent("Your password is incorrect.");
    expect(refresh).not.toHaveBeenCalled();

    await user.click(within(review).getByRole("button", { name: "Make admin" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(api.makeAdmin).toHaveBeenLastCalledWith("a2", "wrong-password");
  });
});
