import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CalendarFeedSettings } from "./CalendarFeedSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return { ...actual, api: { getCalendarFeed: vi.fn(), enableCalendarFeed: vi.fn(), disableCalendarFeed: vi.fn() } };
});

const { api, runtimeConfig } = await import("../../api");
const getCalendarFeed = vi.mocked(api.getCalendarFeed);
const enableCalendarFeed = vi.mocked(api.enableCalendarFeed);
const disableCalendarFeed = vi.mocked(api.disableCalendarFeed);

const PATH = "/calendar/feed/p1/key1/calendar.ics";

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(runtimeConfig, "getApiBase").mockReturnValue("https://fetch.example/api");
  getCalendarFeed.mockResolvedValue({ enabled: false, path: null });
  disableCalendarFeed.mockResolvedValue(undefined);
});

describe("CalendarFeedSettings", () => {
  it("is off to begin with, and shows the address to subscribe to once turned on", async () => {
    enableCalendarFeed.mockResolvedValue({ enabled: true, path: PATH });
    render(<CalendarFeedSettings />);

    await userEvent.click(await screen.findByRole("button", { name: /turn on the calendar feed/i }));

    expect(await screen.findByLabelText("Calendar feed address")).toHaveValue(`https://fetch.example/api${PATH}`);
  });

  it("turns an enabled feed off", async () => {
    getCalendarFeed.mockResolvedValue({ enabled: true, path: PATH });
    render(<CalendarFeedSettings />);

    await userEvent.click(await screen.findByRole("button", { name: /turn off/i }));

    expect(disableCalendarFeed).toHaveBeenCalled();
    expect(await screen.findByRole("button", { name: /turn on the calendar feed/i })).toBeInTheDocument();
  });

  it("says why when the change fails", async () => {
    enableCalendarFeed.mockRejectedValue(new Error("Profile is locked"));
    render(<CalendarFeedSettings />);

    await userEvent.click(await screen.findByRole("button", { name: /turn on the calendar feed/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Profile is locked");
  });
});
