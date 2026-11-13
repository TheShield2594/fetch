import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { TvSettingSectionId } from "@fetch/shared/tv-settings";
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

async function renderForm(section: TvSettingSectionId = "playback") {
  render(<TvSettingsForm section={section} />);
  await waitFor(() => expect(screen.queryByText("Loading TV settings…")).not.toBeInTheDocument());
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
  });

  it("saves the edited keys and carries everything else through", async () => {
    await renderForm();
    await userEvent.selectOptions(screen.getByLabelText("When you pick a title"), "BEST_MATCH");
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));

    expect(saveTvSettings).toHaveBeenCalledTimes(1);
    const [profileId, settings, version] = saveTvSettings.mock.calls[0]!;
    expect(profileId).toBe(PROFILE.id);
    expect(version).toBe(5);
    const player = settings.features?.player_settings as Record<string, { value: unknown }>;
    expect(player.stream_auto_play_mode?.value).toBe("BEST_MATCH");
    expect(player.a_setting_from_a_newer_tv).toEqual({ type: "int", value: 7 });
    expect(player.stream_ranking_rules).toEqual(STORED.features.player_settings.stream_ranking_rules);
    expect(await screen.findByText(/Saved\./)).toBeInTheDocument();
  });

  it("edits the ranking rules on the Streams page, keeping fields it doesn't show", async () => {
    await renderForm("streams");
    await userEvent.click(screen.getByRole("switch", { name: "Prefer HDR" }));
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));
    const player = saveTvSettings.mock.calls[0]![1].features?.player_settings as Record<string, { value: unknown }>;
    const rules = JSON.parse(player.stream_ranking_rules?.value as string) as Record<string, unknown>;
    expect(rules.preferHdr).toBe(false);
    expect(rules.maxPerSource).toBe(2);
  });

  it("changes the theme, and shows pure-black surfaces only with AMOLED on", async () => {
    await renderForm("appearance");
    expect(screen.getByLabelText("Theme")).toHaveValue("FETCH");
    expect(screen.queryByRole("switch", { name: /Pure black surfaces/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Custom colours" })).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Theme"), "CUSTOM");
    expect(screen.getByRole("group", { name: "Custom colours" })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("App font"), "INTER");
    await userEvent.click(screen.getByRole("switch", { name: /AMOLED mode/ }));
    await userEvent.click(screen.getByRole("switch", { name: /Pure black surfaces/ }));
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));

    const theme = saveTvSettings.mock.calls[0]![1].features?.theme_settings as Record<string, { value: unknown }>;
    expect(theme.selected_theme).toEqual({ type: "string", value: "CUSTOM" });
    expect(theme.selected_font).toEqual({ type: "string", value: "INTER" });
    expect(theme.amoled_mode).toEqual({ type: "boolean", value: true });
    expect(theme.amoled_surfaces_mode).toEqual({ type: "boolean", value: true });
  });

  it("hides Advanced-mode settings until asked, and opens with them when the TV is in Advanced mode", async () => {
    await renderForm("subtitles");
    expect(screen.queryByRole("switch", { name: /Strip SDH/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("switch", { name: /Show advanced settings/ }));
    expect(screen.getByRole("switch", { name: /Strip SDH/ })).toBeInTheDocument();
  });

  it("shows advanced settings from the start for a TV in Advanced mode", async () => {
    getTvSettings.mockResolvedValue({
      settings: { version: 1, features: { experience_settings: { mode: { type: "string", value: "ADVANCED" } } } },
      version: 2,
      updatedAt: null,
    });
    await renderForm("subtitles");
    expect(screen.getByRole("switch", { name: /Strip SDH/ })).toBeInTheDocument();
  });

  it("saves the seek steps as ints and the auto-skip segments as a set", async () => {
    await renderForm();
    await userEvent.selectOptions(screen.getByLabelText("Skip forward"), "60");
    await userEvent.click(screen.getByRole("checkbox", { name: "Recap" }));
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));
    const player = saveTvSettings.mock.calls[0]![1].features?.player_settings as Record<string, { value: unknown }>;
    expect(player.seek_forward_seconds).toEqual({ type: "int", value: 60 });
    expect(player.auto_skip_segment_types).toEqual({ type: "string_set", value: ["recap"] });
  });

  it("starts from the TV's defaults when nothing has synced", async () => {
    getTvSettings.mockResolvedValue({ settings: {}, version: 0, updatedAt: null });
    await renderForm();
    expect(screen.getByText(/Nothing has been synced from a TV yet/)).toBeInTheDocument();
    expect(screen.getByLabelText("When you pick a title")).toHaveValue("BEST_MATCH");
  });

  it("lets a number be typed digit by digit and applies it on blur", async () => {
    await renderForm("subtitles");
    const size = screen.getByLabelText("Size (%)");
    await userEvent.clear(size);
    await userEvent.type(size, "120");
    expect(size).toHaveValue(120);
    await userEvent.tab();
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));
    const player = saveTvSettings.mock.calls[0]![1].features?.player_settings as Record<string, { value: unknown }>;
    expect(player.subtitle_size).toEqual({ type: "int", value: 120 });
  });

  it("takes a file-size limit below 1 GB, and empty as no limit", async () => {
    await renderForm("streams");
    const limit = screen.getByLabelText("Largest file (GB, empty for no limit)");
    await userEvent.type(limit, "0.5");
    expect(limit).toHaveValue(0.5);
    await userEvent.tab();
    await userEvent.click(screen.getByRole("button", { name: /Save settings/ }));
    const rules = (saveTvSettings.mock.calls[0]![1].features?.player_settings as Record<string, { value: string }>)
      .stream_ranking_rules!;
    expect(JSON.parse(rules.value).maxSizeGb).toBe(0.5);
  });
});
