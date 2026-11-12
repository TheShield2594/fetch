import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CalendarEntry } from "../api";
import { ToastProvider } from "../hooks/useToast";
import { CalendarPage } from "./CalendarPage";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, api: { getCalendar: vi.fn() } };
});

vi.mock("../components/MediaDetailPanel", () => ({
  DetailPanel: () => null,
  useDetailPanel: () => ({
    selectedItem: null,
    setSelectedItem: vi.fn(),
    panelHistory: [],
    setPanelHistory: vi.fn(),
    panelHistoryLoading: false,
  }),
}));

const { api } = await import("../api");
const getCalendar = vi.mocked(api.getCalendar);

// The 15th is inside whichever month the grid opens on, whatever today is.
const MID_MONTH = (() => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-15`;
})();

function episode(name: string, episodeNumber: number, airDate = MID_MONTH): CalendarEntry {
  return {
    kind: episodeNumber === 1 ? "premiere" : "episode",
    seriesImdbId: `tt-${name.toLowerCase()}`,
    seriesName: name,
    poster: null,
    still: `https://image.tmdb.org/t/p/w500/${name}.jpg`,
    backdrop: null,
    season: 2,
    episode: episodeNumber,
    episodeName: `${name} episode`,
    airDate,
    airsAt: null,
    watched: false,
    overview: `What happens in ${name}.`,
  };
}

function film(name: string, airDate: string): CalendarEntry {
  return {
    kind: "movie",
    seriesImdbId: `tt-${name.toLowerCase()}`,
    seriesName: name,
    poster: null,
    still: null,
    backdrop: `https://image.tmdb.org/t/p/w1280/${name}.jpg`,
    season: null,
    episode: null,
    episodeName: null,
    airDate,
    airsAt: null,
    watched: false,
    overview: "A film.",
  };
}

/** `YYYY-MM-DD`, `days` from today in local time. */
function daysFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const CROWDED_DAY = [episode("Severance", 1), episode("Andor", 2), episode("Silo", 3), episode("Foundation", 4), episode("Slow Horses", 5)];

/** Makes `matchMedia` report a phone-width viewport. */
function mockCompactViewport(matches: boolean) {
  vi.spyOn(window, "matchMedia").mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  }) as MediaQueryList);
}

function LocationProbe() {
  return <output data-testid="location">{useLocation().search}</output>;
}
const currentSearch = () => screen.getByTestId("location").textContent;

const renderPage = (url = "/calendar") =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <ToastProvider>
        <CalendarPage />
        <LocationProbe />
      </ToastProvider>
    </MemoryRouter>
  );

/** Renders and switches to the month grid. */
async function renderMonthView(user: ReturnType<typeof userEvent.setup>) {
  renderPage();
  await user.click(await screen.findByRole("button", { name: /^month$/i }));
}

beforeEach(() => {
  vi.restoreAllMocks();
  getCalendar.mockResolvedValue({ calendar: CROWDED_DAY });
});

describe("CalendarPage agenda", () => {
  it("draws a week of four episodes as four pieces of artwork", async () => {
    getCalendar.mockResolvedValue({
      calendar: [
        episode("Severance", 3, daysFromToday(0)),
        episode("Andor", 4, daysFromToday(1)),
        episode("Silo", 5, daysFromToday(3)),
        episode("Foundation", 6, daysFromToday(5)),
      ],
    });
    const { container } = renderPage();

    await screen.findByRole("heading", { name: /^today/i });
    expect(screen.getByRole("heading", { name: /^tomorrow/i })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /^this week/i })).toBeInTheDocument();
    const art = new Set([...container.querySelectorAll("img.poster-image")].map((img) => img.getAttribute("src")));
    expect(art.size).toBe(4);
    expect(screen.getAllByRole("button", { name: /, S2 E\d/ })).toHaveLength(4);
  });

  it("puts a watchlisted film on its release date", async () => {
    getCalendar.mockResolvedValue({ calendar: [film("Arrival", daysFromToday(10))] });
    renderPage();

    const card = await screen.findByRole("button", { name: /arrival, film release/i });
    expect(card).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /^later/i })).toBeInTheDocument();
  });

  it("shows the time an episode airs, and files it under the local day of that time", async () => {
    // 01:30 local, tomorrow — the dated day is today, as TMDB would have it.
    const at = new Date();
    at.setDate(at.getDate() + 1);
    at.setHours(1, 30, 0, 0);
    getCalendar.mockResolvedValue({
      calendar: [{ ...episode("Severance", 3, daysFromToday(0)), airsAt: at.toISOString() }],
    });
    renderPage();

    const time = at.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    expect(await screen.findByText(`${time} · S2 E3 · Severance episode`)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /^tomorrow/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^today/i })).not.toBeInTheDocument();
  });

  it("leaves out an entry that aired earlier than today here, though it is dated today", async () => {
    const lastNight = new Date();
    lastNight.setDate(lastNight.getDate() - 1);
    lastNight.setHours(23, 30, 0, 0);
    getCalendar.mockResolvedValue({
      calendar: [
        { ...episode("Severance", 3, daysFromToday(0)), airsAt: lastNight.toISOString() },
        episode("Andor", 4, daysFromToday(0)),
      ],
    });
    renderPage();

    expect(await screen.findByText("Andor")).toBeInTheDocument();
    expect(screen.queryByText("Severance")).not.toBeInTheDocument();
  });

  it("calls an episode 1 a premiere", async () => {
    getCalendar.mockResolvedValue({ calendar: [episode("Severance", 1, daysFromToday(2))] });
    renderPage();

    expect(await screen.findByText("Season 2 premiere")).toBeInTheDocument();
  });

  it("keeps unaired episodes' synopses off the page", async () => {
    getCalendar.mockResolvedValue({ calendar: [episode("Severance", 3, daysFromToday(2))] });
    renderPage();

    await screen.findByText("Severance");
    expect(screen.queryByText(/what happens in severance/i)).not.toBeInTheDocument();
  });

  it("keeps the range in the URL", async () => {
    const user = userEvent.setup();
    renderPage("/calendar?days=60");

    expect(await screen.findByRole("button", { name: "60 days" })).toHaveAttribute("aria-pressed", "true");
    expect(getCalendar).toHaveBeenCalledWith(60, undefined);

    await user.click(screen.getByRole("button", { name: "14 days" }));
    expect(currentSearch()).toBe("?days=14");
  });

  it("says what the calendar follows when nothing is coming", async () => {
    getCalendar.mockResolvedValue({ calendar: [] });
    renderPage();

    expect(await screen.findByText("Nothing coming up")).toBeInTheDocument();
    expect(screen.getByText(/films and shows on your watchlist/i)).toBeInTheDocument();
  });
});

describe("CalendarPage month view", () => {
  it("draws posters in a day and opens the whole day on a tap", async () => {
    const user = userEvent.setup();
    await renderMonthView(user);
    expect(currentSearch()).toBe("?view=month");

    const day = await screen.findByRole("button", { name: /5 episodes/i });
    // Three posters fit in the cell; the rest are counted.
    expect(day).toHaveTextContent("+2");

    await user.click(day);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("5 episodes")).toBeInTheDocument();
    for (const entry of CROWDED_DAY) {
      expect(within(dialog).getByText(entry.seriesName)).toBeInTheDocument();
    }

    // Closing is a two-step now: the dialog marks itself exiting and unmounts
    // on the animationend — which jsdom never raises, so the wait rides the
    // hook's fallback timer.
    await user.click(within(dialog).getByRole("button", { name: /close dialog/i }));
    expect(dialog).toHaveClass("overlay-exit");
    expect(dialog).toHaveAttribute("inert");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument(), { timeout: 1000 });
  });

  it("closes the day list when the month changes under it", async () => {
    const user = userEvent.setup();
    await renderMonthView(user);

    await user.click(await screen.findByRole("button", { name: /5 episodes/i }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /next month/i }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("pages back into months that have aired, asking for that month with a day of margin either side", async () => {
    const user = userEvent.setup();
    await renderMonthView(user);

    getCalendar.mockClear();
    await user.click(screen.getByRole("button", { name: /previous month/i }));

    const prev = new Date();
    prev.setDate(1);
    prev.setMonth(prev.getMonth() - 1);
    const key = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, "0")}`;
    const length = new Date(prev.getFullYear(), prev.getMonth() + 1, 0).getDate();
    const dayBefore = new Date(prev.getFullYear(), prev.getMonth(), 0);
    const startKey = `${dayBefore.getFullYear()}-${String(dayBefore.getMonth() + 1).padStart(2, "0")}-${String(dayBefore.getDate()).padStart(2, "0")}`;
    expect(currentSearch()).toBe(`?view=month&month=${key}`);
    await waitFor(() => expect(getCalendar).toHaveBeenCalledWith(length + 2, startKey));
  });

  /*
   * The API windows by dated day; an episode dated the last day of the month
   * before can air, by the clock here, on this month's first.
   */
  it("shows an entry dated the day before the month on the month's first, where it airs here", async () => {
    const user = userEvent.setup();
    const first = new Date();
    first.setDate(1);
    first.setHours(1, 0, 0, 0);
    const dayBefore = new Date(first.getFullYear(), first.getMonth(), 0);
    const dated = `${dayBefore.getFullYear()}-${String(dayBefore.getMonth() + 1).padStart(2, "0")}-${String(dayBefore.getDate()).padStart(2, "0")}`;
    getCalendar.mockResolvedValue({ calendar: [{ ...episode("Andor", 3, dated), airsAt: first.toISOString() }] });
    await renderMonthView(user);

    const label = first.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" });
    expect(await screen.findByRole("button", { name: new RegExp(`^${label}.*1 episode`) })).toBeInTheDocument();
  });

  it("keeps only the newest window's answer when two land out of order", async () => {
    const user = userEvent.setup();
    let resolveSlow: (value: { calendar: CalendarEntry[] }) => void = () => {};
    getCalendar.mockImplementation((days?: number) =>
      days === 30
        ? new Promise((resolve) => { resolveSlow = resolve; })
        : Promise.resolve({ calendar: [episode("Silo", 4, daysFromToday(2))] })
    );
    renderPage();

    await user.click(await screen.findByRole("button", { name: "14 days" }));
    expect(await screen.findByText("Silo")).toBeInTheDocument();

    resolveSlow({ calendar: [episode("Severance", 4, daysFromToday(2))] });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByText("Silo")).toBeInTheDocument();
    expect(screen.queryByText("Severance")).not.toBeInTheDocument();
  });

  it("stops a year back, and says why", async () => {
    const yearAgo = new Date();
    yearAgo.setDate(1);
    yearAgo.setMonth(yearAgo.getMonth() - 12);
    const key = `${yearAgo.getFullYear()}-${String(yearAgo.getMonth() + 1).padStart(2, "0")}`;
    renderPage(`/calendar?view=month&month=${key}`);

    expect(await screen.findByRole("button", { name: /previous month/i })).toBeDisabled();
    expect(screen.getByText(/a year either way/i)).toBeInTheDocument();
  });

  it("marks what has been watched in a past day", async () => {
    const user = userEvent.setup();
    getCalendar.mockResolvedValue({
      calendar: [{ ...episode("Severance", 2), watched: true }, episode("Andor", 3)],
    });
    await renderMonthView(user);

    const day = await screen.findByRole("button", { name: /2 episodes, 1 watched/i });
    await user.click(day);
    expect(await within(await screen.findByRole("dialog")).findByText(/^Watched · S2 E2/)).toBeInTheDocument();
  });

  it("says when a past month had nothing in it", async () => {
    getCalendar.mockResolvedValue({ calendar: [] });
    const prev = new Date();
    prev.setDate(1);
    prev.setMonth(prev.getMonth() - 2);
    const key = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, "0")}`;
    renderPage(`/calendar?view=month&month=${key}`);

    expect(await screen.findByText(/^Nothing aired in/)).toBeInTheDocument();
  });
});

describe("CalendarPage on a phone", () => {
  it("offers the month view, one poster to a day", async () => {
    mockCompactViewport(true);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: /^month$/i }));
    const day = await screen.findByRole("button", { name: /5 episodes/i });
    expect(day).toHaveTextContent("+4");
  });
});
