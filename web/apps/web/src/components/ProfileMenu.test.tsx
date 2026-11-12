import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { ProfileContext } from "../hooks/useProfile";
import { ThemeContext } from "../hooks/useTheme";
import { ProfileMenu } from "./ProfileMenu";

function renderMenu(openSwitcher = vi.fn(), chooseTheme = vi.fn(), hasPin = false) {
  render(
    <MemoryRouter>
      <ProfileContext.Provider
        value={{
          profile: { id: "p1", name: "Sam", hasPin, avatarColorHex: "#22c55e" },
          setProfile: vi.fn(),
          patchProfile: vi.fn(),
          switcherOpen: false,
          openSwitcher,
          closeSwitcher: vi.fn(),
        }}
      >
        <ThemeContext.Provider value={{ theme: "fetch", chooseTheme }}>
          <ProfileMenu variant="header" />
        </ThemeContext.Provider>
      </ProfileContext.Provider>
    </MemoryRouter>
  );
  return { openSwitcher, chooseTheme };
}

describe("ProfileMenu", () => {
  it("opens on the avatar with the profile's own options, focused on the first", async () => {
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Profile menu, Sam" }));

    const menu = screen.getByRole("dialog", { name: "Profile" });
    expect(screen.getByRole("button", { name: "Switch profile" })).toHaveFocus();
    expect(menu).toHaveTextContent("Sam");
    expect(screen.getByRole("link", { name: "Manage profiles" })).toHaveAttribute("href", "/settings?section=profile");
    expect(screen.getByRole("link", { name: "Appearance" })).toHaveAttribute("href", "/settings?section=appearance");
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });

  it("locks a PIN profile without signing out, and offers nothing to lock without a PIN", async () => {
    const user = userEvent.setup();
    const locked = vi.fn();
    window.addEventListener("fetch:profile-locked", locked);
    renderMenu(vi.fn(), vi.fn(), true);
    await user.click(screen.getByRole("button", { name: "Profile menu, Sam" }));
    await user.click(screen.getByRole("button", { name: "Lock Sam" }));

    expect(locked).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog", { name: "Profile" })).not.toBeInTheDocument();
    window.removeEventListener("fetch:profile-locked", locked);
  });

  it("has no lock for a profile without a PIN", async () => {
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Profile menu, Sam" }));
    expect(screen.queryByRole("button", { name: /^Lock/ })).not.toBeInTheDocument();
  });

  it("switches profile from the menu, closing it", async () => {
    const user = userEvent.setup();
    const { openSwitcher } = renderMenu();
    await user.click(screen.getByRole("button", { name: "Profile menu, Sam" }));
    await user.click(screen.getByRole("button", { name: "Switch profile" }));

    expect(openSwitcher).toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { name: "Profile" })).not.toBeInTheDocument();
  });

  it("picks a theme without closing, and closes on Escape back to the avatar", async () => {
    const user = userEvent.setup();
    const { chooseTheme } = renderMenu();
    const trigger = screen.getByRole("button", { name: "Profile menu, Sam" });
    await user.click(trigger);
    await user.click(screen.getByRole("radio", { name: "Midnight Cinema" }));
    expect(chooseTheme).toHaveBeenCalledWith("midnight");
    expect(screen.getByRole("dialog", { name: "Profile" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Profile" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });
});
