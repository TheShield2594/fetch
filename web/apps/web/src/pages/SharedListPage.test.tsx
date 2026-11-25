import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SharedListPage } from "./SharedListPage";

const ID = "22222222-2222-4222-8222-222222222222";
const KEY = "k".repeat(43);
const fetchMock = vi.fn();

const answer = (status: number, body: unknown = {}) =>
  fetchMock.mockResolvedValue({ status, ok: status >= 200 && status < 300, json: async () => body });

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe("SharedListPage", () => {
  it("shows the list's name and titles, asking without credentials", async () => {
    answer(200, {
      list: { name: "Films to watch together", description: "For the holidays", itemCount: 2 },
      items: [
        { type: "movie", imdbId: "tt1", name: "Dune", year: 2021, poster: null },
        { type: "series", imdbId: "tt2", name: "Severance", year: 2022, poster: null },
      ],
    });

    render(<SharedListPage listId={ID} shareKey={KEY} />);

    expect(await screen.findByRole("heading", { name: "Films to watch together" })).toBeInTheDocument();
    expect(screen.getByText("2 titles")).toBeInTheDocument();
    expect(screen.getByText("For the holidays")).toBeInTheDocument();
    expect(screen.getByText("Film · 2021")).toBeInTheDocument();
    expect(screen.getByText("Series · 2022")).toBeInTheDocument();

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain(`/shared/lists/${ID}/${KEY}`);
    expect(init.credentials).toBe("omit");
  });

  it("says so when the link no longer works", async () => {
    answer(404);

    render(<SharedListPage listId={ID} shareKey={KEY} />);

    expect(await screen.findByRole("heading", { name: "This list isn't shared" })).toBeInTheDocument();
  });

  it("tells a failed load apart from a dead link", async () => {
    fetchMock.mockRejectedValue(new Error("offline"));

    render(<SharedListPage listId={ID} shareKey={KEY} />);

    expect(await screen.findByRole("heading", { name: "The list couldn't be loaded" })).toBeInTheDocument();
  });
});
