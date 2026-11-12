import { useEffect } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsHealthProvider, useSettingsHealth } from "./useSettingsHealth";

/*
 * The five status reads the report is built from. Each resolves to a shape its
 * own mapper accepts; the mapping itself is settings/health.test.ts's subject,
 * so this file is only about how many times they are asked and who hears back.
 */
vi.mock("../api", () => ({
  api: {
    getJobStatus: vi.fn(async () => ({ failures: [], runs: [] })),
    getTraktStatus: vi.fn(async () => ({ connected: true, configured: true, expiresAt: null })),
    getTmdbStatus: vi.fn(async () => ({ configured: true, source: "db" })),
    getOmdbStatus: vi.fn(async () => ({ configured: false })),
    getRpdbStatus: vi.fn(async () => ({ configured: false, hasKey: false })),
    getSimklStatus: vi.fn(async () => ({ configured: true, connected: true, lastSyncedAt: null, username: null, pending: null })),
    getMdbListStatus: vi.fn(async () => ({ configured: true, connected: false, lastSyncedAt: null, username: null, pending: null })),
    getMediaServers: vi.fn(async () => ({ providers: [], servers: [], plexLink: null })),
  },
}));

const signedIn = vi.hoisted(() => ({ isAdmin: true }));
vi.mock("./useAccount", () => ({
  useAccount: () => ({ account: null, isAdmin: signedIn.isAdmin, refresh: () => {} }),
}));

const { api } = await import("../api");
const loadCount = () => vi.mocked(api.getTraktStatus).mock.calls.length;

beforeEach(() => {
  vi.clearAllMocks();
  signedIn.isAdmin = true;
});

function Report() {
  const { sections } = useSettingsHealth();
  return <p>trakt: {sections.trakt?.label ?? "—"}</p>;
}

function RefreshButton() {
  const { refresh } = useSettingsHealth();
  return (
    <button type="button" onClick={refresh}>
      Refresh
    </button>
  );
}

/**
 * Mirrors SettingsPage exactly, ordering included: a consumer that asks for a
 * fresh read on mount, from *inside* the provider — where React runs its effect
 * before the provider's own.
 */
function RefreshesOnMount() {
  const { refresh } = useSettingsHealth();
  useEffect(() => {
    refresh();
  }, [refresh]);
  return null;
}

describe("SettingsHealthProvider", () => {
  // The install-wide integrations are the admin's to set up, and their status
  // reads are admin-only on the server. A profile's own sign-ins are asked for
  // everyone (see below).
  it("asks none of the admin's reads for an account that isn't the admin", async () => {
    signedIn.isAdmin = false;
    render(
      <SettingsHealthProvider>
        <Report />
      </SettingsHealthProvider>
    );

    expect(await screen.findByText("trakt: —")).toBeInTheDocument();
    expect(loadCount()).toBe(0);
    expect(api.getTmdbStatus).not.toHaveBeenCalled();
  });

  it("reads a profile's own sign-ins for every account, admin or not", async () => {
    signedIn.isAdmin = false;
    function Own() {
      const { sections } = useSettingsHealth();
      return <p>simkl: {sections.simkl?.label ?? "—"} · mdblist: {sections.mdblist?.label ?? "—"} · servers: {sections["media-servers"]?.label ?? "—"}</p>;
    }
    render(
      <SettingsHealthProvider>
        <Own />
      </SettingsHealthProvider>
    );

    expect(await screen.findByText("simkl: Connected · mdblist: Not connected · servers: None linked")).toBeInTheDocument();
  });

  it("reads every source once and hands the mapped report to its consumers", async () => {
    render(
      <SettingsHealthProvider>
        <Report />
      </SettingsHealthProvider>
    );

    await screen.findByText("trakt: Connected");
    expect(loadCount()).toBe(1);
  });

  /*
   * Opening /settings directly mounts the page inside this provider, and React
   * runs a child's effect before its parent's — so the page's mount-time
   * refresh arrives before the provider has started its first load. Honouring
   * it sent six requests and then six more, both sets on the wire at once,
   * for the same six answers.
   */
  it("folds a refresh asked for before the first load has landed into that load", async () => {
    render(
      <SettingsHealthProvider>
        <Report />
        <RefreshesOnMount />
      </SettingsHealthProvider>
    );

    await screen.findByText("trakt: Connected");
    await waitFor(() => expect(loadCount()).toBe(1));
  });

  // The case the refresh exists for: a reader coming back to the status board
  // later in the session, when what is on screen may be minutes old.
  it("re-asks once the first load has landed", async () => {
    const user = userEvent.setup();
    render(
      <SettingsHealthProvider>
        <Report />
        <RefreshButton />
      </SettingsHealthProvider>
    );

    await screen.findByText("trakt: Connected");
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(loadCount()).toBe(2));
  });
});

describe("useSettingsHealth outside a provider", () => {
  /*
   * An empty report rather than a throw, unlike `useProfile`: a missing profile
   * makes a component wrong, a missing health report only makes it quiet — and
   * quiet is exactly what it renders while the first six requests are in
   * flight, and forever if they all fail.
   */
  it("reports nothing rather than throwing", () => {
    function Bare() {
      const { sections } = useSettingsHealth();
      return <p>sections: {Object.keys(sections).length}</p>;
    }

    render(<Bare />);

    expect(screen.getByText("sections: 0")).toBeInTheDocument();
  });
});
