import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ThemeContext } from "../../hooks/useTheme";
import { AppearanceSettings } from "./AppearanceSettings";

describe("AppearanceSettings", () => {
  it("shows each theme as a swatch with a reason, and picks one", async () => {
    const user = userEvent.setup();
    const chooseTheme = vi.fn();
    render(
      <ThemeContext.Provider value={{ theme: "fetch", chooseTheme }}>
        <AppearanceSettings />
      </ThemeContext.Provider>
    );

    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(5);
    expect(screen.getByRole("radio", { name: "Fetch" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Midnight Cinema" })).toHaveAccessibleDescription("Deep navy and marquee gold");

    await user.click(screen.getByRole("radio", { name: "Matinee" }));
    expect(chooseTheme).toHaveBeenCalledWith("matinee");
    expect(screen.getByText(/Saved to this profile/)).toBeInTheDocument();
  });
});
