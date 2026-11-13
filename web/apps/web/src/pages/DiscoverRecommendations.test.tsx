import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { StrictMode } from "react";
import { MemoryRouter } from "react-router";
import type { TrendingMeta } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { resetDataCacheForTests } from "../utils/dataCache";
import { DiscoverPage } from "./DiscoverPage";

/*
 * The two AI rails, and specifically their staleness guards.
 *
 * `DiscoverPage.test.ts` covers this file's exported pure helpers, which left
 * the component itself — the token guards, the Retry buttons — with no render
 * test at all, and that is where the guards were wrong: `loadRecs` read its
 * token without advancing it, so a request never invalidated the one it
 * replaced. The rails are what these cases pin, because the two ways of getting
 * the counter wrong show up here and nowhere else — one shared counter that
 * nobody advances lets a superseded request through, and one shared counter
 * that everybody advances has `loadAiSection`'s second call throw away the
 * answer to its first.
 */

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getSeriesProgress: vi.fn(),
      getWatchHistory: vi.fn(),
      getWatchedTitles: vi.fn(async () => ({ movies: [], series: [] })),
      getCheckin: vi.fn(),
      getNowPlaying: vi.fn(),
      getTrending: vi.fn(),
      getLists: vi.fn(async () => ({ lists: [] })),
      getListItems: vi.fn(async () => ({ items: [] })),
      getRecommendationsFor: vi.fn(async () => ({ metas: [] })),
      unmarkEpisodeWatched: vi.fn(async () => undefined),
      getAiConfig: vi.fn(),
      getAiRecommendations: vi.fn(),
      getCalendar: vi.fn(),
      endCheckin: vi.fn(),
      markNextEpisodeWatched: vi.fn(),
    },
  };
});

vi.mock("../components/MediaDetailPanel", () => ({
  DetailPanel: () => null,
  useDetailPanel: () => ({
    selectedItem: null,
    setSelectedItem: vi.fn(),
    panelHistory: [],
    setPanelHistory: vi.fn(),
    panelHistoryLoading: false,
    detail: null,
    detailLoading: false,
  }),
}));

const { api, runtimeConfig } = await import("../api");
const getAiRecommendations = vi.mocked(api.getAiRecommendations);

function meta(name: string): TrendingMeta {
  return { id: `tt-${name.toLowerCase()}`, name, type: "movie" };
}

/** A promise this test settles by hand, standing in for a slow request. */
function deferred<T>() {
  let settle: (value: T) => void = () => {};
  let fail: (reason: Error) => void = () => {};
  const promise = new Promise<T>((resolve, reject) => {
    settle = resolve;
    fail = reject;
  });
  // Nothing is listening yet at construction time; a rejection settled later is
  // always awaited by the code under test.
  promise.catch(() => {});
  return { promise, settle, fail };
}

const recs = (...names: string[]) => ({ metas: names.map(meta), reasons: {} });

// A card writes its title twice — once over the poster, once beneath it — so
// the accessible name of its single button is what identifies one card.
const card = (name: string) => screen.findByRole("button", { name: `View details for ${name}` });
const cardIsGone = (name: string) =>
  expect(screen.queryByRole("button", { name: `View details for ${name}` })).not.toBeInTheDocument();

// Scoped to the rail that failed: "Retry" is the label every section's error
// card uses.
async function clickRetryOnMovies() {
  const message = await screen.findByText("Couldn't load films picked for you.");
  await userEvent.click(within(message.parentElement as HTMLElement).getByRole("button", { name: "Retry" }));
}

// A fresh element each call: React bails out of re-rendering a subtree handed
// back the identical element, so a rerender that reuses one would never reach
// the component whose render reads the profile.
const discover = () => (
  <MemoryRouter>
    <ToastProvider>
      <DiscoverPage />
    </ToastProvider>
  </MemoryRouter>
);

const renderDiscover = () => render(discover());

beforeEach(() => {
  resetDataCacheForTests();
  // The profile is an effect dependency: switching it re-runs every loader on
  // the same mount, which is the one way a rail's request is superseded rather
  // than simply replaced by a retry.
  runtimeConfig.setProfileId("first-profile");
  vi.mocked(api.getSeriesProgress).mockResolvedValue([]);
  vi.mocked(api.getWatchHistory).mockResolvedValue([]);
  vi.mocked(api.getCheckin).mockResolvedValue({ checkin: null } as never);
  vi.mocked(api.getNowPlaying).mockResolvedValue({ sessions: [] } as never);
  vi.mocked(api.getTrending).mockResolvedValue({ metas: [] } as never);
  vi.mocked(api.getCalendar).mockResolvedValue({ calendar: [] } as never);
  vi.mocked(api.getAiConfig).mockResolvedValue({
    configured: true,
    lastGeneratedAt: null,
  } as never);
  getAiRecommendations.mockResolvedValue(recs("Arrival"));
});

describe("Discover AI rails", () => {
  it("loads both rails from one AI config check", async () => {
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? recs("Arrival") : recs("Severance")
    );
    renderDiscover();

    // The shared token used to make the second call invalidate the first, so
    // whichever rail was started first never got to paint.
    expect(await card("Arrival")).toBeInTheDocument();
    expect(await card("Severance")).toBeInTheDocument();
  });

  // The whole section reloads under StrictMode's second effect pass, which the
  // app runs in development (`main.tsx`). One rail, one request: the abandoned
  // first pass must not leave a second one behind to answer late.
  it("asks each rail once when the section loads twice over", async () => {
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? recs("Arrival") : recs("Severance")
    );

    render(<StrictMode>{discover()}</StrictMode>);

    expect(await card("Arrival")).toBeInTheDocument();
    expect(getAiRecommendations.mock.calls.map(([kind]) => kind)).toEqual(["movie", "series"]);
  });

  it("replaces the error with the results a retry brings back", async () => {
    const first = deferred<Awaited<ReturnType<typeof api.getAiRecommendations>>>();
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? first.promise : recs("Severance")
    );
    renderDiscover();

    // The movie rail is still waiting on `first`; fail it so a Retry appears.
    await card("Severance");
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? recs("Arrival") : recs("Severance")
    );
    await act(async () => {
      first.fail(new Error("AI provider timed out"));
    });

    await clickRetryOnMovies();

    expect(await card("Arrival")).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load films picked for you.")).not.toBeInTheDocument();
  });

  it("retries one rail without disturbing the other", async () => {
    const movies = deferred<Awaited<ReturnType<typeof api.getAiRecommendations>>>();
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? movies.promise : recs("Severance")
    );
    renderDiscover();
    await card("Severance");

    await act(async () => {
      movies.fail(new Error("AI provider timed out"));
    });
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? recs("Arrival") : recs("Severance")
    );
    await clickRetryOnMovies();

    await waitFor(async () => expect(await card("Arrival")).toBeInTheDocument());
    // The series rail was never asked to reload and is still showing its own.
    expect(await card("Severance")).toBeInTheDocument();
  });

  // The other half of the same guard: a rail that *is* superseded has to stay
  // quiet. Switching profile reloads the whole AI section on the same mount, so
  // the request the previous profile left in flight is answering for a library
  // nobody is looking at.
  it("drops a rail's answer once a profile switch has reloaded the section", async () => {
    const stale = deferred<Awaited<ReturnType<typeof api.getAiRecommendations>>>();
    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? stale.promise : recs("Severance")
    );
    const { rerender } = renderDiscover();
    await card("Severance");

    getAiRecommendations.mockImplementation(async (kind) =>
      kind === "movie" ? recs("Solaris") : recs("Severance")
    );
    act(() => runtimeConfig.setProfileId("second-profile"));
    rerender(discover());
    await card("Solaris");

    await act(async () => {
      stale.settle(recs("Ghost of a previous profile"));
    });

    cardIsGone("Ghost of a previous profile");
    expect(await card("Solaris")).toBeInTheDocument();
  });
});
