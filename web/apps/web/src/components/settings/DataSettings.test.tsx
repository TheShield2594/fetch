import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { csvRecords, DataSettings, previewHistoryCsv, previewJsonExport, slugify } from "./DataSettings";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      refreshAllMetadata: vi.fn(),
      exportData: vi.fn(),
      importData: vi.fn(),
      importCsv: vi.fn(),
      importExternal: vi.fn(),
      clearProfileData: vi.fn(),
    },
  };
});
vi.mock("../../hooks/useAccount", () => ({ useAccount: () => ({ account: null, isAdmin: false, refresh: () => {} }) }));
vi.mock("../../hooks/useProfile", () => ({ useProfile: () => ({ profile: { id: "p1", name: "Alex" } }) }));

const { api } = await import("../../api");

const EXPORT = {
  version: 1,
  exportedAt: "2026-11-01T00:00:00Z",
  profile: { name: "Alex" },
  lists: [{ name: "Watchlist", kind: "watchlist", items: [{}, {}, {}] }],
  watchEvents: [{}, {}],
  seriesProgress: [{}],
  ratings: [],
  playback: [{}],
};

const file = (contents: string, name: string, type: string) => new File([contents], name, { type });
const fileInputs = () => document.querySelectorAll<HTMLInputElement>('input[type="file"]');

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.importData).mockResolvedValue({
    status: "ok",
    summary: { lists: 0, listItems: 3, watchEvents: 2, seriesProgress: 1, ratings: 0, playback: 1 },
  });
  vi.mocked(api.importCsv).mockResolvedValue({ status: "ok", summary: { imported: 2, skipped: 0 } });
  vi.mocked(api.clearProfileData).mockResolvedValue({
    cleared: { watchEvents: 12, seriesProgress: 3, playbackProgress: 2, ratings: 0 },
  });
});

describe("previews", () => {
  it("counts a Fetch export's contents", () => {
    expect(previewJsonExport(JSON.stringify(EXPORT))).toMatchObject({
      parts: ["1 list (3 titles)", "2 watch events", "1 series progress entry", "1 resume point"],
    });
  });

  it("refuses something that isn't a Fetch export", () => {
    expect(previewJsonExport("{nope")).toHaveProperty("error");
    expect(previewJsonExport(JSON.stringify({ version: 9 }))).toHaveProperty("error");
  });

  it("counts CSV rows the way the server parses them, quoted line breaks included", () => {
    expect(csvRecords('a,b\n"x\ny",2\n\n3,4\r\n')).toEqual([["a", "b"], ["x\ny", "2"], ["3", "4"]]);
    expect(previewHistoryCsv("imdbId,type,watchedAt\ntt1,movie,2026-01-01\n")).toEqual({ rows: 1 });
    expect(previewHistoryCsv("title,year\nX,2000")).toHaveProperty("error");
  });

  it("makes a file name from a profile name", () => {
    expect(slugify("Alex's Profile!")).toBe("alexs-profile");
  });
});

describe("DataSettings", () => {
  it("names the profile an export covers", () => {
    render(<DataSettings />);
    expect(screen.getByText("Export Alex")).toBeInTheDocument();
    expect(screen.getByText(/covers this profile only/)).toBeInTheDocument();
  });

  it("shows what a JSON export holds and imports only once confirmed", async () => {
    const user = userEvent.setup();
    render(<DataSettings />);

    await user.upload(fileInputs()[0]!, file(JSON.stringify(EXPORT), "backup.json", "application/json"));

    const review = await screen.findByRole("alertdialog", { name: "Import backup.json?" });
    expect(review).toHaveTextContent("2 watch events");
    expect(review).toHaveTextContent(/never deletes or replaces anything/);
    expect(api.importData).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Import" }));
    await waitFor(() => expect(api.importData).toHaveBeenCalled());
    expect(await screen.findByText(/Imported 2 watch events/)).toBeInTheDocument();
  });

  it("discards a previewed file on Cancel, and says when a file can't be read", async () => {
    const user = userEvent.setup();
    render(<DataSettings />);

    await user.upload(fileInputs()[1]!, file("imdbId,type,watchedAt\ntt1,movie,2026-01-01\n", "h.csv", "text/csv"));
    expect(await screen.findByRole("alertdialog")).toHaveTextContent("1 row of watch history");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(api.importCsv).not.toHaveBeenCalled();

    await user.upload(fileInputs()[0]!, file("not json", "bad.json", "application/json"));
    expect(await screen.findByRole("alert")).toHaveTextContent(/isn't valid JSON/);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("clears watch history after confirming, and says how much went", async () => {
    const user = userEvent.setup();
    render(<DataSettings />);

    await user.click(screen.getByRole("button", { name: "Clear watch history" }));
    expect(screen.getByRole("alertdialog", { name: "Clear Alex's watch history?" })).toHaveTextContent(/Lists and settings stay/);
    expect(api.clearProfileData).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Clear watch history" }));

    await waitFor(() => expect(api.clearProfileData).toHaveBeenCalledWith({ history: true }));
    expect(await screen.findByRole("status")).toHaveTextContent("Cleared 12 watch events and 2 resume points.");
  });
});
