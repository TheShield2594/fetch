import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { App } from "./App";
import { api, runtimeConfig } from "./api";
import { first } from "./test/present";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getProfiles: vi.fn().mockResolvedValue({ profiles: [] }),
      updateProfile: vi.fn().mockResolvedValue({ profile: {} }),
    },
  };
});

// The Shelf is the landing route and fires a page's worth of requests on
// mount; this suite is only about what the top bar renders around it.
vi.mock("./pages/ShelfPage", () => ({ ShelfPage: () => <p>shelf</p> }));

vi.mock("./utils/routePrefetch", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./utils/routePrefetch")>();
  return {
    ...actual,
    // Stands in for the real page's query field, which carries `autoFocus` —
    // the one bit of SearchPage the shell's focus move has to reckon with.
    loadSearchPage: () =>
      Promise.resolve({
        SearchPage: () => (
          <>
            <p>search page</p>
            {/* eslint-disable-next-line jsx-a11y/no-autofocus -- the point of the stand-in is that it claims focus, as the real field does */}
            <input aria-label="Search movies and TV shows" autoFocus />
          </>
        ),
      }),
    schedulePrefetchOnIdle: () => {},
  };
});

beforeEach(() => {
  // Past the setup wizard and the profile picker, which otherwise render
  // instead of the shell.
  window.localStorage.setItem(runtimeConfig.tokenKey, "fs1_test-token");
  window.localStorage.setItem(runtimeConfig.profileIdKey, "profile-1");
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>
  );
}

const headerSearch = () => screen.queryByRole("button", { name: "Search" });

describe("top bar search", () => {
  it("offers the palette trigger on a route with no search field of its own", async () => {
    renderAt("/");

    expect(await screen.findByText("shelf")).toBeInTheDocument();
    expect(headerSearch()).toBeInTheDocument();
  });

  it("stands down on the search page, which has its own field", async () => {
    renderAt("/search");

    expect(await screen.findByText("search page")).toBeInTheDocument();
    expect(headerSearch()).not.toBeInTheDocument();
  });
});

/*
 * Client-side navigation leaves focus on whatever was clicked, so the shell
 * moves it into `<main>` to give a screen reader somewhere to start reading.
 * The exception is a route that focuses its own content — SearchPage's query
 * field — which used to race the shell and lose or win depending on whether its
 * chunk was warm.
 */
describe("focus on route change", () => {
  const mainEl = () => screen.getByRole("main", { name: "Main content" });
  const goToSearch = async (user: ReturnType<typeof userEvent.setup>) => {
    // The sidebar's own link, so focus starts where a real navigation leaves it.
    await user.click(first(screen.getAllByRole("link", { name: "Search" }), "Search link"));
  };

  it("moves focus into main on a route that claims none", async () => {
    const user = userEvent.setup();
    renderAt("/search");
    await screen.findByText("search page");

    await user.click(first(screen.getAllByRole("link", { name: "Shelf" }), "Shelf link"));

    await waitFor(() => expect(mainEl()).toHaveFocus());
  });

  it("leaves the landing route's focus alone on first render", async () => {
    renderAt("/");
    await screen.findByText("shelf");

    expect(mainEl()).not.toHaveFocus();
    expect(document.body).toHaveFocus();
  });

  it("yields to a route that focuses its own content", async () => {
    const user = userEvent.setup();
    renderAt("/");
    await screen.findByText("shelf");

    await goToSearch(user);

    const field = await screen.findByLabelText("Search movies and TV shows");
    await waitFor(() => expect(field).toHaveFocus());
    expect(mainEl()).not.toHaveFocus();
  });

  // The shell must not confuse "focus is still parked on `<main>` from the last
  // navigation" with "the new page claimed focus" — that would skip the move and
  // leave a screen reader with no announcement for the second page.
  it("re-focuses main when focus is already sitting on it", async () => {
    const user = userEvent.setup();
    renderAt("/search");
    await screen.findByText("search page");

    await user.click(first(screen.getAllByRole("link", { name: "Shelf" }), "Shelf link"));
    await waitFor(() => expect(mainEl()).toHaveFocus());

    const focusSpy = vi.spyOn(mainEl(), "focus");
    await user.click(first(screen.getAllByRole("link", { name: "Calendar" }), "Calendar link"));

    await waitFor(() => expect(focusSpy).toHaveBeenCalled());
  });
});

describe("the profile gate", () => {
  it("lets a TV's QR code sign the TV in without picking a profile first", async () => {
    window.localStorage.removeItem(runtimeConfig.profileIdKey);
    renderAt("/pair?code=ABCD-EFGH");

    expect(await screen.findByRole("heading", { name: "Sign in a TV" })).toBeInTheDocument();
    expect(screen.getByLabelText("Code shown on the TV")).toHaveValue("ABCD-EFGH");
    expect(screen.queryByText(/who's watching/i)).not.toBeInTheDocument();
  });

  it("goes back to choosing a new password after a reload on a page that makes no protected request", async () => {
    window.localStorage.removeItem(runtimeConfig.profileIdKey);
    const getMe = vi.spyOn(api, "getMe").mockResolvedValue({
      account: { id: "a2", username: "friend", isAdmin: false, mustChangePassword: true },
      isAdmin: false,
    });
    renderAt("/pair?code=ABCD-EFGH");

    expect(await screen.findByRole("heading", { name: /choose a new password/i })).toBeInTheDocument();
    expect(screen.getByLabelText("Temporary password")).toBeInTheDocument();
    getMe.mockRestore();
  });

  it("still asks for a profile everywhere else", async () => {
    window.localStorage.removeItem(runtimeConfig.profileIdKey);
    renderAt("/settings");

    expect(await screen.findByText(/create your first profile/i)).toBeInTheDocument();
  });
});

/*
 * The shell's profile and theme: the avatar the person chose is the one drawn
 * at the top, the header carries no theme or install control, and the theme is
 * the profile's — applied when it arrives, saved to it when picked.
 */
describe("the profile in the shell", () => {
  const alice = {
    id: "profile-1",
    name: "Alice",
    hasPin: false,
    avatarColorHex: "#1e88e5",
    avatarId: null,
    webTheme: "matinee",
  };

  beforeEach(() => {
    vi.mocked(api.getProfiles).mockResolvedValue({ profiles: [alice] });
    vi.mocked(api.updateProfile).mockClear();
    document.documentElement.removeAttribute("data-theme");
  });

  it("puts no theme or install control in the top bar", async () => {
    renderAt("/");
    await screen.findByText("shelf");
    const header = first(document.querySelectorAll("header"), "header");
    expect(header.querySelector('[aria-label="Choose theme"]')).toBeNull();
    expect(header.querySelector('[aria-label="Install Fetch"]')).toBeNull();
  });

  // The rail's footer and the phone header each carry one; CSS shows one.
  const headerTrigger = async () => {
    await screen.findAllByRole("button", { name: "Profile menu, Alice" });
    const header = first(document.querySelectorAll("header"), "header");
    return within(header).getByRole("button", { name: "Profile menu, Alice" });
  };

  it("draws the profile's own avatar in the header and the rail, not an initial on the accent", async () => {
    renderAt("/");
    const trigger = await headerTrigger();
    // ProfileAvatar paints the profile's colour behind its initials.
    const avatar = first(trigger.querySelectorAll("span"), "avatar");
    expect(avatar).toHaveStyle({ backgroundColor: "#1e88e5" });
    expect(avatar).toHaveTextContent("A");
    const rail = within(first(document.querySelectorAll("aside"), "rail")).getByRole("button", {
      name: "Profile menu, Alice",
    });
    expect(first(rail.querySelectorAll("span"), "rail avatar")).toHaveStyle({ backgroundColor: "#1e88e5" });
  });

  it("applies the profile's saved theme when the profile arrives", async () => {
    renderAt("/");
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-theme", "matinee"));
  });

  it("saves a theme picked in the profile menu to the profile", async () => {
    const user = userEvent.setup();
    renderAt("/");
    await user.click(await headerTrigger());
    const menu = screen.getByRole("dialog", { name: "Profile" });

    await user.click(within(menu).getByRole("radio", { name: "Glass" }));

    expect(document.documentElement).toHaveAttribute("data-theme", "glass");
    expect(api.updateProfile).toHaveBeenCalledWith("profile-1", { webTheme: "glass" });
  });

  it("sends the old profiles address to Settings → Profile", async () => {
    renderAt("/profiles");
    expect(await screen.findByRole("heading", { name: "Profiles" })).toBeInTheDocument();
  });
});

/*
 * #539: a kids profile is kept out of the account's settings and device
 * pairing on the website, and the shell says it is a kids profile.
 */
describe("a kids profile in the shell", () => {
  const kid = {
    id: "profile-1",
    name: "Robin",
    hasPin: false,
    avatarColorHex: "#43a047",
    parental: { isKidsProfile: true, maxRating: "PARENTAL_GUIDANCE" as const, allowUnrated: false },
  };

  beforeEach(() => {
    vi.mocked(api.getProfiles).mockResolvedValue({ profiles: [kid] });
  });

  it.each([
    ["/settings", "Settings are for a grown-up's profile."],
    ["/pair", "Signing in a TV is for a grown-up's profile."],
  ])("keeps %s behind a grown-up's profile", async (path, reason) => {
    renderAt(path);

    expect(await screen.findByText("Ask a grown-up")).toBeInTheDocument();
    expect(screen.getByText(reason, { exact: false })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Switch profile" }).length).toBeGreaterThan(0);
  });

  it("drops Settings from the rail and the phone header, and marks the profile as a kids one", async () => {
    renderAt("/");
    await screen.findAllByRole("button", { name: "Profile menu, Robin" });

    const rail = first(document.querySelectorAll("aside"), "rail");
    await waitFor(() => expect(within(rail).queryByRole("link", { name: /^Settings/ })).not.toBeInTheDocument());
    const header = first(document.querySelectorAll("header"), "header");
    expect(within(header).queryByRole("link", { name: /^Settings/ })).not.toBeInTheDocument();
    expect(within(rail).getByText("Kids")).toBeInTheDocument();
  });
});
