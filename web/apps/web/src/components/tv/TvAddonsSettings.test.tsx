import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TvAddonsSettings } from "./TvAddonsSettings";
import type { TvAddon } from "../../api";

const PROFILE = { id: "11111111-1111-4111-8111-111111111111", name: "Alex", hasPin: false };

vi.mock("../../hooks/useProfile", () => ({ useProfile: () => ({ profile: PROFILE }) }));

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getTvAddons: vi.fn(),
      addTvAddon: vi.fn(),
      updateTvAddon: vi.fn(),
      refreshTvAddon: vi.fn(),
      removeTvAddon: vi.fn(),
      reorderTvAddons: vi.fn(),
    },
  };
});

const { api, ApiError } = await import("../../api");
const getTvAddons = vi.mocked(api.getTvAddons);
const addTvAddon = vi.mocked(api.addTvAddon);
const reorderTvAddons = vi.mocked(api.reorderTvAddons);
const updateTvAddon = vi.mocked(api.updateTvAddon);

const addon = (over: Partial<TvAddon> = {}): TvAddon => ({
  id: "a1",
  manifestUrl: "https://torrentio.example/debrid=SECRET/manifest.json",
  position: 0,
  enabled: true,
  addonId: "com.torrentio",
  name: "Torrentio",
  version: "0.0.14",
  description: null,
  logo: null,
  types: ["movie"],
  resources: ["stream"],
  catalogs: [],
  configurable: true,
  configureUrl: "https://torrentio.example/debrid=SECRET/configure",
  reachable: true,
  manifestFetchedAt: null,
  ...over,
});

async function renderAddons() {
  render(<TvAddonsSettings />);
  await waitFor(() => expect(screen.queryByText("Loading addons...")).not.toBeInTheDocument());
}

describe("TvAddonsSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getTvAddons.mockResolvedValue({ addons: [], updatedAt: null });
  });

  it("lists addons by host, never showing the configured URL", async () => {
    getTvAddons.mockResolvedValue({ addons: [addon()], updatedAt: null });
    await renderAddons();
    expect(getTvAddons).toHaveBeenCalledWith(PROFILE.id);
    expect(screen.getByText("Torrentio")).toBeInTheDocument();
    expect(screen.getByText(/torrentio\.example/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("SECRET");
    expect(screen.getByRole("link", { name: "Configure Torrentio" })).toHaveAttribute("target", "_blank");
  });

  it("adds an addon by manifest address", async () => {
    addTvAddon.mockResolvedValue({ addon: addon({ id: "a2", name: "Cinemeta" }) });
    await renderAddons();
    await userEvent.type(screen.getByLabelText("Manifest address"), "https://cinemeta.example/manifest.json");
    await userEvent.click(screen.getByRole("button", { name: /Add addon/ }));
    expect(addTvAddon).toHaveBeenCalledWith(PROFILE.id, "https://cinemeta.example/manifest.json", undefined);
    expect(await screen.findByText("Cinemeta")).toBeInTheDocument();
  });

  it("shows why a manifest was rejected", async () => {
    addTvAddon.mockRejectedValue(new ApiError("The addon didn't answer.", 400, "manifest_invalid"));
    await renderAddons();
    await userEvent.type(screen.getByLabelText("Manifest address"), "https://down.example/manifest.json");
    await userEvent.click(screen.getByRole("button", { name: /Add addon/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The addon didn't answer.");
  });

  it("moves an addon with the buttons and saves the new order", async () => {
    const first = addon({ id: "a1", name: "Torrentio" });
    const second = addon({ id: "a2", name: "Cinemeta", configureUrl: null });
    getTvAddons.mockResolvedValue({ addons: [first, second], updatedAt: null });
    reorderTvAddons.mockResolvedValue({ addons: [second, first] });
    await renderAddons();

    expect(screen.getByRole("button", { name: "Move Torrentio up" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Move Cinemeta up" }));
    expect(reorderTvAddons).toHaveBeenCalledWith(PROFILE.id, ["a2", "a1"]);
    const names = screen.getAllByRole("listitem").map((item) => item.textContent ?? "");
    expect(names[0]).toContain("Cinemeta");
  });

  it("holds further moves until the order being saved lands", async () => {
    getTvAddons.mockResolvedValue({
      addons: [addon({ id: "a1", name: "Torrentio" }), addon({ id: "a2", name: "Cinemeta" }), addon({ id: "a3", name: "Comet" })],
      updatedAt: null,
    });
    let finish: (value: { addons: TvAddon[] }) => void = () => {};
    reorderTvAddons.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    await renderAddons();

    await userEvent.click(screen.getByRole("button", { name: "Move Comet up" }));
    expect(screen.getByRole("button", { name: "Move Torrentio down" })).toBeDisabled();
    expect(reorderTvAddons).toHaveBeenCalledTimes(1);

    finish({ addons: [] });
    await waitFor(() => expect(screen.queryAllByRole("button", { name: /^Move/ }).length).toBe(0));
  });

  it("puts the order back when saving it fails", async () => {
    getTvAddons.mockResolvedValue({ addons: [addon({ id: "a1", name: "Torrentio" }), addon({ id: "a2", name: "Cinemeta" })], updatedAt: null });
    reorderTvAddons.mockRejectedValue(new Error("offline"));
    await renderAddons();
    await userEvent.click(screen.getByRole("button", { name: "Move Cinemeta up" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("offline");
    expect(screen.getAllByRole("listitem")[0]?.textContent).toContain("Torrentio");
  });

  it("turns an addon off", async () => {
    getTvAddons.mockResolvedValue({ addons: [addon()], updatedAt: null });
    updateTvAddon.mockResolvedValue({ addon: addon({ enabled: false }) });
    await renderAddons();
    await userEvent.click(screen.getByRole("switch", { name: "Torrentio enabled" }));
    expect(updateTvAddon).toHaveBeenCalledWith(PROFILE.id, "a1", { enabled: false });
    await waitFor(() => expect(screen.getByRole("switch", { name: "Torrentio enabled" })).not.toBeChecked());
  });

  it("offers this server's addon until it is installed", async () => {
    await renderAddons();
    expect(screen.getByRole("button", { name: /Add this server's addon/ })).toBeInTheDocument();
  });
});
