import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WebhookSettings, webhookUrl } from "./WebhookSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: { getWebhookKey: vi.fn(), createWebhookKey: vi.fn(), removeWebhookKey: vi.fn() },
  };
});

const { api, runtimeConfig } = await import("../../api");
const getWebhookKey = vi.mocked(api.getWebhookKey);
const createWebhookKey = vi.mocked(api.createWebhookKey);
const removeWebhookKey = vi.mocked(api.removeWebhookKey);

beforeEach(() => {
  vi.clearAllMocks();
  getWebhookKey.mockResolvedValue({ configured: false, createdAt: null });
  createWebhookKey.mockResolvedValue({ key: "fwh_abc", createdAt: "2026-10-27T00:00:00.000Z" });
  removeWebhookKey.mockResolvedValue(undefined);
});

describe("webhookUrl", () => {
  it("is absolute even when the API is a path on this origin", () => {
    vi.spyOn(runtimeConfig, "getApiBase").mockReturnValueOnce("/api");
    expect(webhookUrl("plex", "fwh_abc")).toBe(`${window.location.origin}/api/webhooks/plex?token=fwh_abc`);
  });

  it("keeps a full API address as it is", () => {
    vi.spyOn(runtimeConfig, "getApiBase").mockReturnValueOnce("https://watchfetch.tv/api/");
    expect(webhookUrl("jellyfin", "fwh_abc")).toBe("https://watchfetch.tv/api/webhooks/jellyfin?token=fwh_abc");
  });
});

describe("WebhookSettings", () => {
  it("makes a link and shows it for each media server, once", async () => {
    const user = userEvent.setup();
    render(<WebhookSettings />);

    await user.click(await screen.findByRole("button", { name: /make a webhook link/i }));

    for (const server of ["Plex", "Jellyfin", "Emby"]) {
      expect(screen.getByLabelText(`${server} webhook URL`)).toHaveValue(
        webhookUrl(server.toLowerCase(), "fwh_abc")
      );
    }
    expect(screen.getByRole("button", { name: /make a new link/i })).toBeInTheDocument();
  });

  it("says a link exists without showing it, and removes it", async () => {
    getWebhookKey.mockResolvedValue({ configured: true, createdAt: "2026-10-01T00:00:00.000Z" });
    const user = userEvent.setup();
    render(<WebhookSettings />);

    expect(await screen.findByRole("status")).toHaveTextContent(/you have a webhook link/i);
    expect(screen.queryByLabelText("Plex webhook URL")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /remove link/i }));

    expect(removeWebhookKey).toHaveBeenCalled();
    expect(await screen.findByRole("button", { name: /make a webhook link/i })).toBeInTheDocument();
  });
});
