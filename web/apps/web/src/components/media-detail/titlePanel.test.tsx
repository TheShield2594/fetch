import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation, useNavigate } from "react-router";
import type { SearchResult } from "../../api";
import { ToastProvider } from "../../hooks/useToast";
import { parseTitleParam, TitlePanelProvider, titleHref, useOnTitleClose, useTitlePanel } from "./titlePanel";

// The panel and its loader have suites of their own. What is under test here is
// the address: which title the URL names, and what Back, Close and a reload do.
vi.mock("../MediaDetailPanel", async () => {
  const { useState } = await import("react");
  return {
    DetailPanel: ({
      item,
      onClose,
      onSelectItem,
    }: {
      item: SearchResult;
      onClose: () => void;
      onSelectItem: (item: SearchResult) => void;
    }) => {
      if (item.name === "Explodes") throw new Error("a title whose data breaks the panel");
      return (
      <div>
        <p>Panel: {item.name}</p>
        <button type="button" onClick={onClose}>close panel</button>
        <button type="button" onClick={() => onSelectItem(title("tt-next", "Stalker"))}>more like this</button>
      </div>
      );
    },
    useDetailPanel: () => {
      const [selectedItem, setSelectedItem] = useState<SearchResult | null>(null);
      return {
        selectedItem,
        setSelectedItem,
        panelHistory: [],
        setPanelHistory: () => {},
        panelHistoryLoading: false,
        detail: null,
        detailLoading: false,
      };
    },
  };
});

function title(imdbId: string, name: string, type: "movie" | "series" = "movie"): SearchResult {
  return {
    imdbId,
    type,
    name,
    year: null,
    poster: null,
    description: null,
    genres: [],
    rating: null,
    inWatchlist: false,
    inCollection: false,
    lists: [],
  };
}

const onClosed = vi.fn();

function Page() {
  const { openTitle } = useTitlePanel();
  const location = useLocation();
  const navigate = useNavigate();
  useOnTitleClose(onClosed);
  return (
    <div>
      <p data-testid="url">{location.pathname + location.search}</p>
      <button type="button" onClick={() => openTitle(title("tt-solaris", "Solaris"))}>open Solaris</button>
      <button type="button" onClick={() => openTitle(title("tt-bad", "Explodes"))}>open broken</button>
      <button type="button" onClick={() => void navigate(-1)}>back</button>
      <button type="button" onClick={() => void navigate(1)}>forward</button>
    </div>
  );
}

function renderAt(...entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <ToastProvider>
        <TitlePanelProvider pageTitle="Lists · Fetch" appName="Fetch">
          <Page />
        </TitlePanelProvider>
      </ToastProvider>
    </MemoryRouter>
  );
}

const url = () => screen.getByTestId("url").textContent;

describe("title panel address", () => {
  it("parses only a type and an id it recognises", () => {
    expect(parseTitleParam("series:tt0903747")).toEqual({ type: "series", imdbId: "tt0903747" });
    expect(parseTitleParam("movie:tmdb:603")).toEqual({ type: "movie", imdbId: "tmdb:603" });
    expect(parseTitleParam("episode:tt1")).toBeNull();
    expect(parseTitleParam("tt0903747")).toBeNull();
    expect(parseTitleParam("movie:")).toBeNull();
    expect(parseTitleParam(null)).toBeNull();
  });

  it("builds a link that round-trips", () => {
    const href = titleHref("/calendar", { type: "series", imdbId: "tt1" });
    expect(parseTitleParam(new URL(href, "https://x.test").searchParams.get("title"))).toEqual({
      type: "series",
      imdbId: "tt1",
    });
  });

  it("puts the title in the URL when one is opened, keeping the page's own parameters", async () => {
    const user = userEvent.setup();
    renderAt("/lists?list=abc");

    await user.click(screen.getByRole("button", { name: "open Solaris" }));

    expect(await screen.findByText("Panel: Solaris")).toBeInTheDocument();
    expect(url()).toBe("/lists?list=abc&title=movie%3Att-solaris");
    expect(document.title).toBe("Solaris · Fetch");
  });

  it("closes on Back, and reopens on Forward", async () => {
    const user = userEvent.setup();
    renderAt("/lists");

    await user.click(screen.getByRole("button", { name: "open Solaris" }));
    await screen.findByText("Panel: Solaris");

    await user.click(screen.getByRole("button", { name: "back" }));
    await waitFor(() => expect(screen.queryByText("Panel: Solaris")).toBeNull());
    expect(url()).toBe("/lists");
    expect(document.title).toBe("Lists · Fetch");

    await user.click(screen.getByRole("button", { name: "forward" }));
    expect(await screen.findByText("Panel: Solaris")).toBeInTheDocument();
  });

  it("walks recommendation hops with Back, and leaves all of them with Close", async () => {
    const user = userEvent.setup();
    renderAt("/lists");

    await user.click(screen.getByRole("button", { name: "open Solaris" }));
    await user.click(await screen.findByRole("button", { name: "more like this" }));
    expect(await screen.findByText("Panel: Stalker")).toBeInTheDocument();
    expect(url()).toBe("/lists?title=movie%3Att-next");

    // Back is one hop: the title that was open before.
    await user.click(screen.getByRole("button", { name: "back" }));
    expect(await screen.findByText("Panel: Solaris")).toBeInTheDocument();

    // Close from two deep goes all the way out, not to the previous title.
    await user.click(screen.getByRole("button", { name: "more like this" }));
    await screen.findByText("Panel: Stalker");
    onClosed.mockClear();
    await user.click(screen.getByRole("button", { name: "close panel" }));

    await waitFor(() => expect(url()).toBe("/lists"));
    expect(screen.queryByText(/^Panel:/)).toBeNull();
    expect(onClosed).toHaveBeenCalledTimes(1);
  });

  it("shows the title a cold URL names, and closes it in place", async () => {
    const user = userEvent.setup();
    renderAt("/calendar?title=series%3Att-severance");

    // Nothing is known about it yet but its id, which stands in for the name
    // until the bundle arrives — and is not a name fit for the tab.
    expect(await screen.findByText("Panel: tt-severance")).toBeInTheDocument();
    expect(document.title).toBe("Lists · Fetch");

    // No entry behind it that the app pushed, so Close rewrites this one.
    await user.click(screen.getByRole("button", { name: "close panel" }));
    await waitFor(() => expect(url()).toBe("/calendar"));
  });

  // The panel sits outside every page's error boundary now. A title that
  // breaks it must cost that title, not the navigation around it.
  it("closes a panel that throws, and leaves the page standing", async () => {
    const user = userEvent.setup();
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      renderAt("/lists");

      await user.click(screen.getByRole("button", { name: "open broken" }));

      await waitFor(() => expect(url()).toBe("/lists"));
      expect(screen.getByRole("button", { name: "open Solaris" })).toBeInTheDocument();
      expect(await screen.findByText("That title could not be shown")).toBeInTheDocument();

      // …and the next title opens as if nothing had happened.
      await user.click(screen.getByRole("button", { name: "open Solaris" }));
      expect(await screen.findByText("Panel: Solaris")).toBeInTheDocument();
    } finally {
      consoleError.mockRestore();
    }
  });

  it("ignores a title parameter it cannot parse", () => {
    renderAt("/lists?title=nonsense");
    expect(screen.queryByText(/^Panel:/)).toBeNull();
  });
});
