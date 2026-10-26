import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TvSettingsForm } from "./TvSettingsForm";

const PROFILE = { id: "11111111-1111-4111-8111-111111111111", name: "Alex", hasPin: false };

vi.mock("../../hooks/useProfile", () => ({ useProfile: () => ({ profile: PROFILE }) }));

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return { ...actual, api: { getTvSettings: vi.fn(), saveTvSettings: vi.fn() } };
});

const { api } = await import("../../api");
const getTvSettings = vi.mocked(api.getTvSettings);
const saveTvSettings = vi.mocked(api.saveTvSettings);

const STORED = {
  version: 1,
  features: {
    player_settings: {
      stream_auto_play_mode: { type: "string", value: "MANUAL" },
      a_setting_from_a_newer_tv: { type: "int", value: 7 },
      stream_ranking_rules: { type: "string", value: JSON.stringify({ maxPerSource: 2 }) },
    },
  },
};

async function renderForm() {
  render(<TvSettingsForm />);
  await waitFor(() => expect(screen.queryByText("Loading TV settings...")).not.toBeInTheDocument());
}

describe("TvSettingsForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getTvSettings.mockResolvedValue({ settings: STORED, version: 5, updatedAt: null });
    saveTvSettings.mockImplementation(async (_id, settings) => ({ settings, version: 6, updatedAt: null }));
  });

  it("shows the stored values", async () => {
    await renderForm();
    expect(screen.getByLabelText("When you pick a title")).toHaveValue("MANUAL");
    expect(screen.getByLabelText("Size (%)")).toHaveValue(100);
  });

  it("saves the edited keys and carries everything else through", async () => {
    await renderForm();
    await userEvent.selectOptions(screen.getByLabelText("When you pick a title"), "BEST_MATCH");
    await userEvent.click(screen.getByRole("switch", { name: "Prefer HDR" }));
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));

    expect(saveTvSettings).toHaveBeenCalledTimes(1);
    const [profileId, settings, version] = saveTvSettings.mock.calls[0]!;
    expect(profileId).toBe(PROFILE.id);
    expect(version).toBe(5);
    const player = settings.features?.player_settings as Record<string, { value: unknown }>;
    expect(player.stream_auto_play_mode?.value).toBe("BEST_MATCH");
    expect(player.a_setting_from_a_newer_tv).toEqual({ type: "int", value: 7 });
    const rules = JSON.parse(player.stream_ranking_rules?.value as string) as Record<string, unknown>;
    expect(rules.preferHdr).toBe(false);
    expect(rules.maxPerSource).toBe(2);
    expect(await screen.findByText(/Saved\./)).toBeInTheDocument();
  });

  it("starts from the TV's defaults when nothing has synced", async () => {
    getTvSettings.mockResolvedValue({ settings: {}, version: 0, updatedAt: null });
    await renderForm();
    expect(screen.getByText(/Nothing has been synced from a TV yet/)).toBeInTheDocument();
    expect(screen.getByLabelText("When you pick a title")).toHaveValue("BEST_MATCH");
  });
});
