import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AddonSettings } from "./AddonSettings";

const PROFILE_ID = "11111111-1111-4111-8111-111111111111";
const KEY = "a".repeat(64);

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getAddonConfig: vi.fn(),
      getAiConfig: vi.fn(),
      getProfiles: vi.fn(),
      updateAddonConfig: vi.fn(),
    },
  };
});

const { api, runtimeConfig } = await import("../../api");
const getAddonConfig = vi.mocked(api.getAddonConfig);

const config = (stremioProfileKey: string | null) => ({
  config: { enabledCatalogs: [] },
  availableCatalogs: [],
  availableLists: [],
  aiConfigured: false,
  stremioProfileKey,
});

beforeEach(() => {
  vi.spyOn(runtimeConfig, "getProfileId").mockReturnValue(PROFILE_ID);
  vi.mocked(api.getAiConfig).mockResolvedValue({ configured: false, config: null, lastGeneratedAt: null });
  vi.mocked(api.getProfiles).mockResolvedValue({ profiles: [{ id: PROFILE_ID, name: "Alex", hasPin: false }] } as never);
});

describe("AddonSettings", () => {
  it("shows the profile's signed install URL", async () => {
    getAddonConfig.mockResolvedValue(config(KEY));
    render(<AddonSettings />);
    expect(await screen.findByText(`${runtimeConfig.getAddonBase()}/u/${PROFILE_ID}/${KEY}/manifest.json`)).toBeInTheDocument();
  });

  it("falls back to the unsigned profile URL when the API has no key", async () => {
    getAddonConfig.mockResolvedValue(config(null));
    render(<AddonSettings />);
    expect(await screen.findByText(`${runtimeConfig.getAddonBase()}/p/${PROFILE_ID}/manifest.json`)).toBeInTheDocument();
  });
});
