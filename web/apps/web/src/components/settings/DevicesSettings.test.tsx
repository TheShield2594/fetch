import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../../hooks/useToast";
import { DevicesSettings, formatPairingCodeInput } from "./DevicesSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: { getDevices: vi.fn(), confirmPairing: vi.fn(), revokeDevice: vi.fn() },
  };
});

const { api, ApiError } = await import("../../api");
const getDevices = vi.mocked(api.getDevices);
const confirmPairing = vi.mocked(api.confirmPairing);
const revokeDevice = vi.mocked(api.revokeDevice);

const LIVING_ROOM = { id: "d1", name: "Living room", createdAt: "2026-10-01T00:00:00Z", lastSeenAt: null };

const renderDevices = async () => {
  render(
    <ToastProvider>
      <DevicesSettings />
    </ToastProvider>
  );
  await waitFor(() => expect(screen.queryByText("Loading devices...")).not.toBeInTheDocument());
};

beforeEach(() => {
  vi.clearAllMocks();
  getDevices.mockResolvedValue({ devices: [LIVING_ROOM] });
});

describe("formatPairingCodeInput", () => {
  it.each([
    ["abcd", "ABCD"],
    ["abcde", "ABCD-E"],
    ["ab cd-ef gh", "ABCD-EFGH"],
    ["ABCD-EFGH-IJ", "ABCD-EFGH"],
  ])("formats %j as %j", (input, expected) => {
    expect(formatPairingCodeInput(input)).toBe(expected);
  });
});

describe("DevicesSettings", () => {
  it("lists signed-in TVs", async () => {
    await renderDevices();
    const list = screen.getByRole("list", { name: "Signed-in devices" });
    expect(within(list).getByText("Living room")).toBeInTheDocument();
    expect(within(list).getByText(/Not seen since pairing/)).toBeInTheDocument();
  });

  it("confirms a code typed loosely, then refreshes the list", async () => {
    const user = userEvent.setup();
    confirmPairing.mockResolvedValue({ deviceName: "Bedroom" });
    await renderDevices();

    await user.type(screen.getByLabelText("Code shown on the TV"), "abcdefgh");
    await user.type(screen.getByLabelText("Name for this TV"), "Bedroom");
    await user.click(screen.getByRole("button", { name: "Sign in TV" }));

    await waitFor(() => expect(confirmPairing).toHaveBeenCalledWith("ABCD-EFGH", "Bedroom"));
    await waitFor(() => expect(getDevices).toHaveBeenCalledTimes(2));
  });

  it("says when a code is wrong or expired", async () => {
    const user = userEvent.setup();
    confirmPairing.mockRejectedValue(new ApiError("nope", 404));
    await renderDevices();

    await user.type(screen.getByLabelText("Code shown on the TV"), "ABCDEFGH");
    await user.click(screen.getByRole("button", { name: "Sign in TV" }));

    expect(await screen.findByText(/wrong or has expired/)).toBeInTheDocument();
  });

  it("won't submit half a code", async () => {
    const user = userEvent.setup();
    await renderDevices();
    await user.type(screen.getByLabelText("Code shown on the TV"), "ABC");
    expect(screen.getByRole("button", { name: "Sign in TV" })).toBeDisabled();
  });

  it("revokes a TV after confirming", async () => {
    const user = userEvent.setup();
    revokeDevice.mockResolvedValue(undefined as never);
    await renderDevices();

    await user.click(screen.getByRole("button", { name: "Revoke Living room" }));
    expect(revokeDevice).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(revokeDevice).toHaveBeenCalledWith("d1"));
    await waitFor(() => expect(screen.queryByRole("list", { name: "Signed-in devices" })).not.toBeInTheDocument());
  });
});
