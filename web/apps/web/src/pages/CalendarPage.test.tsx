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
    expect(getCalendar).toHaveBeenCalledWith(60);

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

  it("explains why there is no going back past the current month", async () => {
    const user = userEvent.setup();
    await renderMonthView(user);

    expect(screen.getByRole("button", { name: /previous month/i })).toBeDisabled();
    expect(screen.getByText(/forward-looking/i)).toBeInTheDocument();

    // A month ahead, stepping back is how you return.
    await user.click(screen.getByRole("button", { name: /next month/i }));
    expect(currentSearch()).toMatch(/month=\d{4}-\d{2}/);
    expect(screen.getByRole("button", { name: /previous month/i })).toBeEnabled();
    expect(screen.queryByText(/forward-looking/i)).not.toBeInTheDocument();
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
