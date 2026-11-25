import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccessTokensSettings } from "./AccessTokensSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: { getAccessTokens: vi.fn(), createAccessToken: vi.fn(), revokeAccessToken: vi.fn() },
  };
});

const { api } = await import("../../api");
const getAccessTokens = vi.mocked(api.getAccessTokens);
const createAccessToken = vi.mocked(api.createAccessToken);
const revokeAccessToken = vi.mocked(api.revokeAccessToken);

const token = (over: Partial<import("../../api").AccessToken> = {}) => ({
  id: "t1",
  name: "Home Assistant",
  readOnly: true,
  createdAt: "2026-11-01T00:00:00.000Z",
  lastUsedAt: null,
  expiresAt: null,
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  getAccessTokens.mockResolvedValue({ tokens: [] });
  revokeAccessToken.mockResolvedValue(undefined);
});

describe("AccessTokensSettings", () => {
  it("makes a read-only token by default and shows it once", async () => {
    const user = userEvent.setup();
    createAccessToken.mockResolvedValue({ token: "fpat_secret", accessToken: token() });
    render(<AccessTokensSettings />);

    await user.type(await screen.findByLabelText("Name"), "Home Assistant");
    await user.click(screen.getByRole("button", { name: /make a token/i }));

    expect(createAccessToken).toHaveBeenCalledWith({ name: "Home Assistant", readOnly: true, expiresInDays: 90 });
    expect(await screen.findByLabelText("New access token")).toHaveValue("fpat_secret");
    expect(screen.getByText(/read-only · never used · no expiry/i)).toBeInTheDocument();
  });

  it("sends the chosen access and lifetime", async () => {
    const user = userEvent.setup();
    createAccessToken.mockResolvedValue({ token: "fpat_secret", accessToken: token({ readOnly: false }) });
    render(<AccessTokensSettings />);

    await user.type(await screen.findByLabelText("Name"), "Importer");
    await user.click(screen.getByLabelText(/read-only/i));
    await user.selectOptions(screen.getByLabelText("Expires after"), "No expiry");
    await user.click(screen.getByRole("button", { name: /make a token/i }));

    expect(createAccessToken).toHaveBeenCalledWith({ name: "Importer", readOnly: false, expiresInDays: null });
  });

  it("lists existing tokens without their secret, and revokes one", async () => {
    const user = userEvent.setup();
    getAccessTokens.mockResolvedValue({ tokens: [token()] });
    render(<AccessTokensSettings />);

    await user.click(await screen.findByRole("button", { name: "Revoke Home Assistant" }));

    expect(revokeAccessToken).toHaveBeenCalledWith("t1");
    expect(screen.queryByText("Home Assistant")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("New access token")).not.toBeInTheDocument();
  });

  it("says why a token couldn't be made", async () => {
    const user = userEvent.setup();
    createAccessToken.mockRejectedValue(new Error("An account can have at most 25 access tokens. Revoke one first."));
    render(<AccessTokensSettings />);

    await user.type(await screen.findByLabelText("Name"), "One more");
    await user.click(screen.getByRole("button", { name: /make a token/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/at most 25/);
  });
});
