import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TwoFactorSettings } from "./TwoFactorSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getTotpStatus: vi.fn(),
      startTotpSetup: vi.fn(),
      enableTotp: vi.fn(),
      disableTotp: vi.fn(),
      regenerateRecoveryCodes: vi.fn(),
    },
  };
});

const { api } = await import("../../api");
const { ApiError } = await import("../../api/client");

const CODES = Array.from({ length: 10 }, (_, i) => `aaaa${i}-bbbbb`);

beforeEach(() => {
  vi.mocked(api.getTotpStatus).mockReset().mockResolvedValue({ enabled: false, enabledAt: null, recoveryCodesLeft: 0 });
  vi.mocked(api.startTotpSetup).mockReset().mockResolvedValue({
    secret: "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
    uri: "otpauth://totp/Fetch:owner?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Fetch",
  });
  vi.mocked(api.enableTotp).mockReset().mockResolvedValue({ recoveryCodes: CODES });
  vi.mocked(api.disableTotp).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.regenerateRecoveryCodes).mockReset().mockResolvedValue({ recoveryCodes: CODES });
});

describe("TwoFactorSettings", () => {
  it("turns on with the password, a QR code and a code, then shows the recovery codes once", async () => {
    const onChanged = vi.fn();
    const user = userEvent.setup();
    render(<TwoFactorSettings username="owner" onChanged={onChanged} />);

    await user.click(await screen.findByRole("button", { name: /turn on two-factor sign-in/i }));
    await user.type(screen.getByLabelText("Current password"), "owner-password");
    await user.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByRole("img", { name: /qr code/i })).toBeInTheDocument();
    expect(screen.getByText("JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP")).toBeInTheDocument();
    expect(api.startTotpSetup).toHaveBeenCalledWith("owner-password");

    await user.type(screen.getByLabelText("Code from the app"), "123456");
    await user.click(screen.getByRole("button", { name: /turn on/i }));

    expect(await screen.findByText("Save your recovery codes")).toBeInTheDocument();
    for (const code of CODES) expect(screen.getByText(code)).toBeInTheDocument();
    expect(api.enableTotp).toHaveBeenCalledWith("123456");
    expect(onChanged).toHaveBeenCalled();

    vi.mocked(api.getTotpStatus).mockResolvedValue({ enabled: true, enabledAt: "2026-11-13T00:00:00Z", recoveryCodesLeft: 10 });
    await user.click(screen.getByRole("button", { name: /i've saved them/i }));
    expect(await screen.findByText("10 recovery codes left.")).toBeInTheDocument();
    expect(screen.queryByText(CODES[0]!)).not.toBeInTheDocument();
  });

  it("says a wrong confirmation code is wrong and keeps two-factor off", async () => {
    vi.mocked(api.enableTotp).mockRejectedValue(new ApiError("That code isn't right", 400, "totp_incorrect"));
    const user = userEvent.setup();
    render(<TwoFactorSettings username="owner" onChanged={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: /turn on two-factor sign-in/i }));
    await user.type(screen.getByLabelText("Current password"), "owner-password");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    await user.type(await screen.findByLabelText("Code from the app"), "000000");
    await user.click(screen.getByRole("button", { name: /turn on/i }));

    expect(await screen.findByText(/that code isn't right\. check the time/i)).toBeInTheDocument();
    expect(screen.queryByText("Save your recovery codes")).not.toBeInTheDocument();
  });

  it("turns off with the password and a recovery code", async () => {
    vi.mocked(api.getTotpStatus).mockResolvedValue({ enabled: true, enabledAt: "2026-11-13T00:00:00Z", recoveryCodesLeft: 2 });
    const user = userEvent.setup();
    render(<TwoFactorSettings username="owner" onChanged={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: /turn off/i }));
    await user.type(screen.getByLabelText("Current password"), "owner-password");
    await user.click(screen.getByRole("button", { name: /use a recovery code instead/i }));
    await user.type(screen.getByLabelText("Recovery code"), "abcde-fghjk");
    vi.mocked(api.getTotpStatus).mockResolvedValue({ enabled: false, enabledAt: null, recoveryCodesLeft: 0 });
    await user.click(screen.getByRole("button", { name: /^turn off$/i }));

    await waitFor(() =>
      expect(api.disableTotp).toHaveBeenCalledWith("owner-password", { recoveryCode: "abcde-fghjk" })
    );
    expect(await screen.findByText("Two-factor sign-in is off.")).toBeInTheDocument();
  });
});
