import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PhoneSettingsButton } from "./PhoneSettingsButton";

const health = vi.hoisted(() => ({ value: {} as Record<string, { tone: "ok" | "warn" | "bad" | "idle"; label: string }> }));
vi.mock("../hooks/useSettingsHealth", () => ({
  useSettingsHealth: () => ({ sections: health.value, refresh: () => {} }),
}));

const renderButton = () =>
  render(
    <MemoryRouter>
      <PhoneSettingsButton />
    </MemoryRouter>
  );

beforeEach(() => {
  health.value = {};
});

describe("PhoneSettingsButton", () => {
  it("is one tap to Settings, with nothing to report while every source is fine", () => {
    health.value = { trakt: { tone: "ok", label: "Synced" }, omdb: { tone: "idle", label: "Not set" } };
    renderButton();

    const link = screen.getByRole("link", { name: "Settings" });
    expect(link).toHaveAttribute("href", "/settings");
    expect(link.querySelector(".status-dot")).toBeNull();
  });

  it("carries the worst state on the board, in words as well as a dot", () => {
    health.value = { tmdb: { tone: "warn", label: "No key" }, trakt: { tone: "bad", label: "Token expired" } };
    renderButton();

    const link = screen.getByRole("link", { name: "Settings, a source has a problem" });
    expect(link.querySelector(".status-dot--bad")).not.toBeNull();
  });
});
