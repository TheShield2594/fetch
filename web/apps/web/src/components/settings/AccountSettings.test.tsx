import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountSettings } from "./AccountSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      logout: vi.fn(),
      changePassword: vi.fn(),
      getSignIns: vi.fn(),
      getActivity: vi.fn(),
      getTotpStatus: vi.fn(),
    },
  };
});

const me = vi.hoisted(() => ({ account: { id: "a1", username: "owner", isAdmin: true } }));
vi.mock("../../hooks/useAccount", () => ({
  useAccount: () => ({ account: me.account, isAdmin: me.account.isAdmin, refresh: () => {} }),
}));
vi.mock("../../hooks/useToast", () => ({ useToast: () => ({ showToast: vi.fn() }) }));

const { api } = await import("../../api");

beforeEach(() => {
  vi.mocked(api.logout).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.changePassword).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.getSignIns).mockReset().mockResolvedValue({ sessions: [] });
  vi.mocked(api.getActivity).mockReset().mockResolvedValue({ events: [] });
  vi.mocked(api.getTotpStatus).mockReset().mockResolvedValue({ enabled: false, enabledAt: null, recoveryCodesLeft: 0 });
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

    await user.click(screen.getByRole("button", { name: /^sign out$/i }));

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
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText(/don't match/i)).toBeInTheDocument();
    expect(api.changePassword).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText("Confirm new password"));
    await user.type(screen.getByLabelText("Confirm new password"), "new-password");
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText(/password changed/i)).toBeInTheDocument();
    expect(api.changePassword).toHaveBeenCalledWith("old-password", "new-password", { signOutTvs: false });
  });
});
