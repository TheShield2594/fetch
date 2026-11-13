import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { MOVED_TO_TV, SETTINGS_SECTIONS, SettingsPage, boardTiles, matchesSearch, visibleSections } from "./SettingsPage";
import { setExtrasEnabled } from "../hooks/useExtras";

// Each section's body is its own component's business, and most of them fetch
// on mount. Stub them out: what this file tests is which sections the page
// shows, which is decided entirely by the section list and the search box.
vi.mock("../components/settings/PreferencesSettings", () => ({ PreferencesSettings: () => <p>preferences body</p> }));
vi.mock("../components/settings/ProfileSettings", () => ({ ProfileSettings: () => <p>profile body</p> }));
vi.mock("../components/settings/PushSettings", () => ({ PushSettings: () => <p>notifications body</p> }));
vi.mock("../components/settings/JobStatusSettings", () => ({ JobStatusSettings: () => <p>sync status body</p> }));
vi.mock("../components/settings/AccountSettings", () => ({ AccountSettings: () => <p>account body</p> }));
vi.mock("../components/settings/InvitesSettings", () => ({ InvitesSettings: () => <p>invites body</p> }));
vi.mock("../components/settings/TraktSettings", () => ({ TraktSettings: () => <p>trakt body</p> }));
vi.mock("../components/settings/AddonSettings", () => ({ AddonSettings: () => <p>addon body</p> }));
vi.mock("../components/settings/OmdbSettings", () => ({ OmdbSettings: () => <p>omdb body</p> }));
vi.mock("../components/settings/RpdbSettings", () => ({ RpdbSettings: () => <p>rpdb body</p> }));
vi.mock("../components/settings/MdbListRatingsSettings", () => ({ MdbListRatingsSettings: () => <p>mdblist ratings body</p> }));
vi.mock("../components/settings/AiSettings", () => ({ AiSettings: () => <p>ai body</p> }));
vi.mock("../components/settings/DataSettings", () => ({ DataSettings: () => <p>data body</p> }));

// Health is six status requests and its own mapping rules, both tested
// elsewhere (hooks/useSettingsHealth, components/settings/health.test.ts). Held
// empty by default here so a section header's accessible name is just its
// title; the cases that are about the status rows set it themselves.
//
// The hook reads a provider now — the rail shows the same six answers, and two
// callers on this route would otherwise ask twice — so the mock returns the
// pair, `refresh` included: the page re-asks on mount.
const health = vi.hoisted(() => ({
  value: {} as Record<string, { tone: "ok" | "warn" | "bad" | "idle"; label: string; setByServer?: true }>,
}));
const refresh = vi.hoisted(() => vi.fn());
vi.mock("../hooks/useSettingsHealth", () => ({
  useSettingsHealth: () => ({ sections: health.value, refresh }),
}));

// The admin sees every section; the cases about anyone else say so.
const signedIn = vi.hoisted(() => ({ isAdmin: true }));
vi.mock("../hooks/useAccount", () => ({
  useAccount: () => ({ account: null, isAdmin: signedIn.isAdmin, refresh: () => {} }),
}));

beforeEach(() => {
  setExtrasEnabled(false);
  health.value = {};
  refresh.mockClear();
  signedIn.isAdmin = true;
});

const find = (query: string) => SETTINGS_SECTIONS.filter((s) => matchesSearch(s, query)).map((s) => s.id);

describe("settings sections", () => {
  it("gives every section a unique address", () => {
    const ids = SETTINGS_SECTIONS.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("names a service by its name and nothing else", () => {
    const titles = SETTINGS_SECTIONS.filter((s) => s.source).map((s) => s.title);
    for (const title of titles) expect(title).not.toMatch(/integration| metadata$| posters$|plex, jellyfin/i);
  });

  // #527: everything about the TV is on its own page.
  it("leaves the TV to the TV page", () => {
    for (const id of MOVED_TO_TV) expect(SETTINGS_SECTIONS.map((s) => s.id)).not.toContain(id);
  });

  it("matches everything when the query is empty or blank", () => {
    expect(find("")).toHaveLength(SETTINGS_SECTIONS.length);
    expect(find("   ")).toHaveLength(SETTINGS_SECTIONS.length);
  });

  it("finds a section by a word in its title, ignoring case", () => {
    expect(find("TRAKT")).toContain("trakt");
  });

  it("finds a section by what it does, not only by what it is called", () => {
    expect(find("sign out everywhere")).toEqual(["account"]);
    // Your own password is in Account; a member's temporary one is the admin's, in Invites.
    expect(find("password")).toEqual(["account", "invites"]);
  });

  it("narrows on each extra term rather than widening", () => {
    expect(find("api key").length).toBeGreaterThan(find("api key imdb").length);
  });

  it("matches nothing when a term is absent", () => {
    expect(find("zzzz")).toEqual([]);
  });

  it("leaves out admin-only sections, opt-in ones, and what the server's environment answers", () => {
    const ids = (h: Parameters<typeof visibleSections>[0], o: Parameters<typeof visibleSections>[1]) => visibleSections(h, o).map((s) => s.id);
    expect(ids({}, { isAdmin: false, showExtras: false })).not.toContain("addon");
    expect(ids({}, { isAdmin: true, showExtras: false })).not.toContain("ai");
    expect(ids({ tmdb: { tone: "ok", label: "Key set (env)", setByServer: true } }, { isAdmin: true, showExtras: false })).not.toContain("tmdb");
  });

  it("splits the board into sources in use and sources available, keeping unread ones with the first", () => {
    const sections = visibleSections({}, { isAdmin: true, showExtras: false });
    const tiles = boardTiles(sections, {
      trakt: { tone: "bad", label: "Token expired" },
      simkl: { tone: "idle", label: "Not connected" },
    });
    expect(tiles.live.map((s) => s.id)).toEqual(["trakt"]);
    expect(tiles.available.map((s) => s.id)).toEqual(["simkl"]);
    expect(tiles.unknown.map((s) => s.id)).toContain("tmdb");
  });
});

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{`${location.pathname}${location.search}`}</p>;
}

const renderAt = (url = "/settings") =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <SettingsPage />
    </MemoryRouter>
  );

const nav = () => screen.getAllByRole("navigation", { name: "Settings sections" })[0]!;
const searchBox = () => screen.getAllByLabelText("Search settings")[0]!;

describe("SettingsPage", () => {
  /*
   * The page's half of the contract, and only its half: it asks the shared
   * provider for a fresh read when it opens. Whether that ask reaches the
   * network is the provider's business.
   */
  it("asks for a fresh health read when Settings opens", () => {
    renderAt();

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("titles the page with an h1, so the heading outline starts at the top", () => {
    renderAt();

    expect(screen.getByRole("heading", { level: 1, name: "Settings" })).toBeInTheDocument();
  });

  it("leads with the status board rather than with a form", () => {
    renderAt();

    expect(screen.getByRole("heading", { name: "Status" })).toBeInTheDocument();
    expect(screen.queryByText("account body")).not.toBeInTheDocument();
  });

  it("reads every source's state on the board without opening anything", () => {
    health.value = {
      trakt: { tone: "bad", label: "Token expired" },
      simkl: { tone: "idle", label: "Not connected" },
    };
    renderAt();

    const inUse = screen.getByRole("list", { name: "Sources in use" });
    const trakt = within(inUse).getByRole("link", { name: /Trakt/ });
    expect(trakt).toHaveTextContent("Token expired");
    expect(trakt).toHaveAttribute("href", "/settings?section=trakt");
    expect(trakt.querySelector(".status-dot--bad")).not.toBeNull();
    expect(within(screen.getByRole("list", { name: "Available sources" })).getByRole("link", { name: /Simkl/ })).toHaveTextContent("Not connected");
  });

  // Where Stremio's "Configure" button lands (see the addon's /configure), and
  // where the rail's source rows go.
  it("opens the section a link names, at its own address", () => {
    renderAt("/settings?section=addon");

    expect(screen.getByRole("region", { name: "Stremio addon" })).toHaveTextContent("addon body");
    expect(within(nav()).getByRole("link", { name: "Stremio addon" })).toHaveAttribute("aria-current", "page");
  });

  it("shows one section at a time", () => {
    renderAt("/settings?section=trakt");

    expect(screen.getByText("trakt body")).toBeInTheDocument();
    expect(screen.queryByText("simkl body")).not.toBeInTheDocument();
  });

  it("falls back to the board for a section the account can't see", () => {
    signedIn.isAdmin = false;
    renderAt("/settings?section=addon");

    expect(screen.queryByText("addon body")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Status" })).toBeInTheDocument();
  });

  it("groups the sections by what they are for", () => {
    renderAt();

    for (const group of ["Sources", "Metadata", "This profile", "Your account", "This server (admin)", "About"]) {
      expect(within(nav()).getAllByText(group).length).toBeGreaterThan(0);
    }
  });

  // #538: every section says whose it is.
  it.each([
    ["trakt", "This profile"],
    ["preferences", "Your account: every profile"],
    ["tmdb", "This server: every account. Only the admin can change it."],
    ["extras", "This browser only"],
  ])("says whose the %s section is", (id, scope) => {
    renderAt(`/settings?section=${id}`);

    expect(screen.getByText(/Applies to/).closest("p")).toHaveTextContent(scope);
  });

  it("gives every section but About a scope", () => {
    expect(SETTINGS_SECTIONS.filter((s) => !s.scope).map((s) => s.id)).toEqual(["about"]);
  });

  it("puts a section's state beside its name in the list, in words as well as in colour", () => {
    health.value = { trakt: { tone: "bad", label: "Token expired" } };
    renderAt();

    const row = within(nav()).getByRole("link", { name: /^Trakt/ });
    expect(row).toHaveAccessibleName(/^Trakt\W+Token expired$/);
    expect(row.querySelector(".status-dot--bad")).not.toBeNull();
  });

  it("leaves out TMDB when the key comes from the server's environment", async () => {
    health.value = { tmdb: { tone: "ok", label: "Key set (env)", setByServer: true } };
    renderAt();

    expect(within(nav()).queryByRole("link", { name: /^TMDB/ })).not.toBeInTheDocument();
    expect(within(nav()).getByRole("link", { name: /^OMDb/ })).toBeInTheDocument();

    await userEvent.type(searchBox(), "tmdb");
    expect(within(nav()).queryByRole("link", { name: /^TMDB/ })).not.toBeInTheDocument();
  });

  it("leads with a count of what is healthy, so the page answers before it is read", async () => {
    health.value = {
      trakt: { tone: "ok", label: "Synced 4m ago" },
      tmdb: { tone: "warn", label: "No key — artwork is off" },
      omdb: { tone: "idle", label: "Not set" },
    };
    renderAt();

    expect(await screen.findByText("1 of 2 sources healthy · 1 not set up")).toBeInTheDocument();
  });

  it("says nothing at all until the statuses land, rather than reporting a health it cannot read", () => {
    renderAt();

    expect(screen.queryByText(/sources healthy/)).not.toBeInTheDocument();
  });

  it("filters the list to matching sections, across every group", async () => {
    renderAt();
    await userEvent.type(searchBox(), "stremio");

    expect(within(nav()).getAllByRole("link").map((link) => link.textContent)).toEqual(["Stremio addon"]);
    // No group headings while a result set spans all of them.
    expect(within(nav()).queryByText("Server")).not.toBeInTheDocument();
  });

  it("says so when nothing matches", async () => {
    renderAt();
    await userEvent.type(searchBox(), "zzzz");

    expect(screen.getAllByText('No settings match "zzzz".')[0]).toBeInTheDocument();
  });

  it("clears the search on Escape and from the clear button", async () => {
    renderAt();
    await userEvent.type(searchBox(), "trakt");
    await userEvent.keyboard("{Escape}");
    expect(searchBox()).toHaveValue("");

    await userEvent.type(searchBox(), "trakt");
    await userEvent.click(screen.getAllByRole("button", { name: "Clear search" })[0]!);
    expect(searchBox()).toHaveValue("");
  });

  it("keeps AI recommendations out of Settings until they are turned on", async () => {
    renderAt();
    await userEvent.type(searchBox(), "ollama");
    expect(within(nav()).queryAllByRole("link")).toHaveLength(0);

    await userEvent.clear(searchBox());
    setExtrasEnabled(true);
    await userEvent.type(searchBox(), "ollama");
    expect(within(nav()).getByRole("link", { name: "AI recommendations" })).toBeInTheDocument();
  });

  it.each([
    ["/settings?section=home-folders", "/tv?section=home-folders"],
    ["/settings?section=tv-settings", "/tv?section=tv-settings"],
    ["/settings?section=devices", "/tv"],
  ])("sends %s, an old TV link, to %s", (from, to) => {
    render(
      <MemoryRouter initialEntries={[from]}>
        <Routes>
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/tv" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByTestId("location")).toHaveTextContent(to);
  });
});

describe("SettingsPage for an account that isn't the admin", () => {
  it("keeps their own sections and hides the install-wide ones", () => {
    signedIn.isAdmin = false;
    renderAt();

    for (const title of ["Account", "Language & region", "Profiles", "Notifications", "About", "Data", "Trakt", "Webhooks", "Media servers", "Simkl", "MDBList", "MDBList ratings"]) {
      expect(within(nav()).getByRole("link", { name: new RegExp(`^${title}($|\\W*,)`) })).toBeInTheDocument();
    }
    for (const title of ["Sync status", "TMDB", "Invites & accounts", "Stremio addon"]) {
      expect(within(nav()).queryByRole("link", { name: new RegExp(`^${title}($|\\W*,)`) })).not.toBeInTheDocument();
    }
  });

  it("can't find a hidden section by searching for it", async () => {
    signedIn.isAdmin = false;
    renderAt();

    await userEvent.type(searchBox(), "stremio");

    expect(within(nav()).queryByRole("link", { name: /Stremio/ })).not.toBeInTheDocument();
  });
});
