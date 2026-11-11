import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LINK_SITE_KEY, LinkPage } from "./LinkPage";
import { runtimeConfig } from "../api";

const renderAt = (url: string) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/link" element={<LinkPage />} />
        <Route path="/pair" element={<p>pair page</p>} />
      </Routes>
    </MemoryRouter>
  );

describe("LinkPage", () => {
  const assign = vi.fn();

  beforeEach(() => {
    window.localStorage.clear();
    vi.spyOn(runtimeConfig, "hasSession").mockReturnValue(false);
    vi.stubGlobal("location", { ...window.location, assign, origin: "https://watchfetch.tv" });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    assign.mockReset();
  });

  it("asks for the website once, then opens its /pair page with the code and remembers it", async () => {
    const user = userEvent.setup();
    renderAt("/link?code=wxyz2345");

    expect(screen.getByText("WXYZ-2345")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Your Fetch website"), "fetch.example.com");
    await user.click(screen.getByRole("button", { name: /Continue/ }));

    expect(assign).toHaveBeenCalledWith("https://fetch.example.com/pair?code=WXYZ-2345");
    expect(window.localStorage.getItem(LINK_SITE_KEY)).toBe("https://fetch.example.com");
  });

  it("uses http:// for an address on the home network", async () => {
    const user = userEvent.setup();
    renderAt("/link?code=WXYZ-2345");

    await user.type(screen.getByLabelText("Your Fetch website"), "192.168.1.25:7002");
    await user.click(screen.getByRole("button", { name: /Continue/ }));

    expect(assign).toHaveBeenCalledWith("http://192.168.1.25:7002/pair?code=WXYZ-2345");
  });

  it("offers the remembered website as one tap", async () => {
    window.localStorage.setItem(LINK_SITE_KEY, "https://fetch.example.com");
    const user = userEvent.setup();
    renderAt("/link?code=WXYZ-2345");

    await user.click(screen.getByRole("button", { name: /Continue to fetch.example.com/ }));

    expect(assign).toHaveBeenCalledWith("https://fetch.example.com/pair?code=WXYZ-2345");
  });

  it("goes straight to /pair when signed in here", () => {
    vi.mocked(runtimeConfig.hasSession).mockReturnValue(true);
    renderAt("/link?code=WXYZ-2345");

    expect(screen.getByText("pair page")).toBeInTheDocument();
  });
});
