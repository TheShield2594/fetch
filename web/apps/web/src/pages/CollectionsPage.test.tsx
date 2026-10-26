import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../hooks/useToast";
import { CollectionsPage } from "./CollectionsPage";
import type { Collection } from "../api";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getCollections: vi.fn(),
      saveCollections: vi.fn(),
      previewCollectionSource: vi.fn(),
      getLists: vi.fn(),
    },
  };
});

const { api, ApiError } = await import("../api");
const getCollections = vi.mocked(api.getCollections);
const saveCollections = vi.mocked(api.saveCollections);
const previewCollectionSource = vi.mocked(api.previewCollectionSource);

const LIST_ID = "44444444-4444-4444-8444-444444444444";
// Made on the TV, with a field this version of the website doesn't know.
const FROM_TV: Collection = {
  id: "c1",
  title: "Weekend",
  viewMode: "ROWS",
  folders: [{ id: "f1", title: "Picks", sources: [], focusGifEnabled: true, tvOnlyField: 3 }],
};

const renderPage = async () => {
  render(
    <ToastProvider>
      <CollectionsPage />
    </ToastProvider>
  );
  await screen.findByRole("navigation", { name: "Collections" });
};

beforeEach(() => {
  vi.clearAllMocks();
  getCollections.mockResolvedValue({ collections: [FROM_TV], updatedAt: "2026-10-26T10:00:00.000Z" });
  vi.mocked(api.getLists).mockResolvedValue({
    lists: [
      { id: "w", name: "Watchlist", kind: "watchlist", itemCount: 3 },
      { id: LIST_ID, name: "Family night", kind: "custom", itemCount: 12 },
    ],
  });
  saveCollections.mockImplementation(async (collections) => ({ collections, updatedAt: "2026-10-26T11:00:00.000Z" }));
});

describe("CollectionsPage", () => {
  it("adds a website list as a source and saves the TV's fields untouched", async () => {
    const user = userEvent.setup();
    await renderPage();

    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Add source" }));
    // Website lists are the first tab, and only custom lists are offered.
    const list = screen.getByRole("combobox", { name: "List" });
    expect(within(list).queryByText(/Watchlist/)).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Show" }), "MOVIE");
    await user.click(screen.getAllByRole("button", { name: "Add source" })[0]!);
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(saveCollections).toHaveBeenCalled());
    const [saved, base] = saveCollections.mock.calls[0]!;
    expect(base).toBe("2026-10-26T10:00:00.000Z");
    expect(saved[0]!.folders[0]).toEqual({
      ...FROM_TV.folders[0],
      sources: [{ provider: "fetch", listId: LIST_ID, title: "Family night", mediaType: "MOVIE" }],
    });
  });

  it("previews a source before it is added", async () => {
    const user = userEvent.setup();
    previewCollectionSource.mockResolvedValue({ metas: [{ id: "tt1", type: "movie", name: "Up" }] });
    await renderPage();

    await user.click(screen.getByRole("button", { name: "Add source" }));
    await user.click(screen.getByRole("button", { name: "Preview" }));

    const preview = await screen.findByRole("region", { name: "Preview" });
    expect(await within(preview).findByText("Up")).toBeInTheDocument();
    expect(previewCollectionSource).toHaveBeenCalledWith({ provider: "fetch", listId: LIST_ID, title: "Family night" });
  });

  it("offers a reload instead of overwriting a newer save from the TV", async () => {
    const user = userEvent.setup();
    saveCollections.mockRejectedValue(new ApiError("changed", 409, "collections_conflict"));
    await renderPage();

    await user.clear(screen.getByLabelText("Collection title"));
    await user.type(screen.getByLabelText("Collection title"), "Renamed");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/changed on a TV since you opened this page/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(getCollections).toHaveBeenCalledTimes(2));
  });

  it("starts a new collection with a folder", async () => {
    const user = userEvent.setup();
    getCollections.mockResolvedValue({ collections: [], updatedAt: null });
    await renderPage();

    expect(screen.getByText("No collections yet")).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "New collection" })[0]!);

    expect(screen.getByLabelText("Collection title")).toHaveValue("New collection");
    expect(screen.getByLabelText("Folder title")).toHaveValue("New folder");
  });
});
