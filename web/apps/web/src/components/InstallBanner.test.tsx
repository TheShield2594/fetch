import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { InstallBanner } from "./InstallBanner";
import { resetInstallOffer } from "../hooks/useInstallOffer";

type ModeListener = () => void;
const listeners: ModeListener[] = [];

/** Stub matchMedia so that only the given display modes report a match. */
function mockDisplayModes(...modes: string[]) {
  vi.spyOn(window, "matchMedia").mockImplementation(
    (query: string) =>
      ({
        matches: modes.some((mode) => query === `(display-mode: ${mode})`),
        media: query,
        onchange: null,
        addListener() {},
        removeListener() {},
        addEventListener: (_type: string, listener: ModeListener) => listeners.push(listener),
        removeEventListener: (_type: string, listener: ModeListener) => {
          const index = listeners.indexOf(listener);
          if (index >= 0) listeners.splice(index, 1);
        },
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList
  );
}

/** Chrome's install offer, with the two members the banner calls. */
function offerInstall() {
  const event = Object.assign(new Event("beforeinstallprompt", { cancelable: true }), {
    prompt: vi.fn().mockResolvedValue(undefined),
    userChoice: Promise.resolve({ outcome: "accepted", platform: "web" }),
  });
  act(() => {
    window.dispatchEvent(event);
  });
  return event;
}

const setSecureContext = (value: boolean) =>
  Object.defineProperty(window, "isSecureContext", { value, configurable: true });

const banner = () => screen.queryByRole("complementary", { name: "Install Fetch" });

afterEach(() => {
  listeners.length = 0;
  setSecureContext(false);
  localStorage.clear();
  resetInstallOffer();
  vi.restoreAllMocks();
});

describe("InstallBanner", () => {
  it("offers to install once the browser has an offer, and opens the browser's dialog", async () => {
    const user = userEvent.setup();
    mockDisplayModes("browser");
    setSecureContext(true);
    render(<InstallBanner />);
    expect(banner()).not.toBeInTheDocument();

    const event = offerInstall();
    await user.click(screen.getByRole("button", { name: "Install" }));

    expect(event.prompt).toHaveBeenCalled();
  });

  it("hears an offer that fired before it mounted", () => {
    mockDisplayModes("browser");
    setSecureContext(true);
    offerInstall();
    render(<InstallBanner />);
    expect(banner()).toBeInTheDocument();
  });

  it("goes away when an open tab becomes the installed app", () => {
    mockDisplayModes("browser");
    setSecureContext(true);
    render(<InstallBanner />);
    offerInstall();
    expect(banner()).toBeInTheDocument();

    mockDisplayModes("standalone");
    act(() => {
      for (const listener of [...listeners]) listener();
    });

    expect(banner()).not.toBeInTheDocument();
  });

  it("never offers from inside the installed app", () => {
    mockDisplayModes("standalone");
    render(<InstallBanner />);
    offerInstall();
    expect(banner()).not.toBeInTheDocument();
  });

  it("says why on an insecure origin rather than rendering nothing", () => {
    mockDisplayModes("browser");
    setSecureContext(false);
    render(<InstallBanner />);
    expect(screen.getByText(/can’t be installed from this address/)).toBeInTheDocument();
    expect(screen.getByText(/https:\/\//)).toBeInTheDocument();
  });

  it("is asked once: dismissed, it stays gone", async () => {
    const user = userEvent.setup();
    mockDisplayModes("browser");
    setSecureContext(true);
    offerInstall();
    const first = render(<InstallBanner />);

    await user.click(screen.getByRole("button", { name: "Dismiss install offer" }));
    expect(banner()).not.toBeInTheDocument();

    first.unmount();
    render(<InstallBanner />);
    expect(banner()).not.toBeInTheDocument();
  });

  it("keeps quiet when the origin is secure and the browser hasn't offered", () => {
    mockDisplayModes("browser");
    setSecureContext(true);
    render(<InstallBanner />);
    expect(banner()).not.toBeInTheDocument();
  });
});
