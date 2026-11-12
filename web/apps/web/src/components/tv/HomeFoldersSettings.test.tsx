import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../../hooks/useToast";
import { ProfileProvider } from "../../hooks/useProfile";
import { HomeFoldersSettings } from "./HomeFoldersSettings";
import type { Collection } from "../../api";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      getCollections: vi.fn(),
      saveCollections: vi.fn(),
      previewCollectionSource: vi.fn(),
      searchTmdbEntities: vi.fn(),
      getLists: vi.fn(),
    },
  };
});

const { api, ApiError } = await import("../../api");
const getCollections = vi.mocked(api.getCollections);
const saveCollections = vi.mocked(api.saveCollections);
const previewCollectionSource = vi.mocked(api.previewCollectionSource);
const searchTmdbEntities = vi.mocked(api.searchTmdbEntities);

const LIST_ID = "44444444-4444-4444-8444-444444444444";
// Made on the TV, with a field this version of the website doesn't know.
const FROM_TV: Collection = {
  id: "c1",
  title: "Weekend",
  viewMode: "ROWS",
  folders: [{ id: "f1", title: "Picks", sources: [], focusGifEnabled: true, tvOnlyField: 3 }],
};

const renderSection = async () => {
  render(
    <ProfileProvider initialProfile={null}>
      <ToastProvider>
        <HomeFoldersSettings />
      </ToastProvider>
    </ProfileProvider>
  );
  await screen.findByRole("list", { name: "Folder rows" });
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
  previewCollectionSource.mockResolvedValue({ metas: [] });
});

describe("HomeFoldersSettings", () => {
  it("adds one of your lists as a source and saves the TV's fields untouched", async () => {
    const user = userEvent.setup();
    await renderSection();

    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Add source" }));
    // Your lists are the first choice, and only lists you made are offered.
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
    await renderSection();

    await user.click(screen.getByRole("button", { name: "Add source" }));
    await user.click(screen.getByRole("button", { name: "Preview" }));

    const preview = await screen.findByRole("list", { name: /^Preview of/ });
    expect(await within(preview).findByText("Up")).toBeInTheDocument();
    expect(previewCollectionSource).toHaveBeenCalledWith({ provider: "fetch", listId: LIST_ID, title: "Family night" });
  });

  it("picks a TMDB person by searching for them rather than by typing an id", async () => {
    const user = userEvent.setup();
    searchTmdbEntities.mockResolvedValue({ results: [{ id: 525, name: "Christopher Nolan", image: null, detail: "Directing" }] });
    await renderSection();

    await user.click(screen.getByRole("button", { name: "Add source" }));
    await user.click(screen.getByRole("button", { name: "TMDB" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "From" }), "DIRECTOR");
    await user.type(screen.getByLabelText("Search TMDB for a person"), "nolan");
    await user.click(await screen.findByRole("button", { name: /Christopher Nolan/ }));
    await user.click(screen.getAllByRole("button", { name: "Add source" })[0]!);

    expect(searchTmdbEntities).toHaveBeenCalledWith("person", "nolan", expect.any(AbortSignal));
    expect(screen.getByRole("list", { name: "Sources in Picks" })).toHaveTextContent("TMDB director: Christopher Nolan");
  });

  it("offers a reload instead of overwriting a newer save from the TV", async () => {
    const user = userEvent.setup();
    saveCollections.mockRejectedValue(new ApiError("changed", 409, "collections_conflict"));
    await renderSection();

    await user.clear(screen.getByLabelText("Folder row title"));
    await user.type(screen.getByLabelText("Folder row title"), "Renamed");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/changed on a TV since you opened them/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reload" }));
    await waitFor(() => expect(getCollections).toHaveBeenCalledTimes(2));
  });

  it("starts a new folder row with a folder", async () => {
    const user = userEvent.setup();
    getCollections.mockResolvedValue({ collections: [], updatedAt: null });
    await renderSection();

    expect(screen.getByText("No folder rows yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "New folder row" }));

    expect(screen.getByLabelText("Folder row title")).toHaveValue("New folder row");
    expect(screen.getByLabelText("Folder title")).toHaveValue("New folder");
  });

  it("goes back to a saved row when Discard drops the unsaved one that was open", async () => {
    const user = userEvent.setup();
    await renderSection();

    await user.click(screen.getByRole("button", { name: "New folder row" }));
    expect(screen.getByLabelText("Folder row title")).toHaveValue("New folder row");
    await user.click(screen.getByRole("button", { name: /discard/i }));

    expect(screen.getByLabelText("Folder row title")).toHaveValue("Weekend");
    expect(screen.queryByText("No folder rows yet")).not.toBeInTheDocument();
  });

  it("puts a removed folder back with Undo", async () => {
    const user = userEvent.setup();
    await renderSection();

    await user.click(screen.getByRole("button", { name: "Remove folder Picks" }));
    expect(screen.queryByLabelText("Folder title")).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Undo" }));

    expect(screen.getByLabelText("Folder title")).toHaveValue("Picks");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("reorders folders without a drag, from the keyboard", async () => {
    const user = userEvent.setup();
    getCollections.mockResolvedValue({
      collections: [{ ...FROM_TV, folders: [...FROM_TV.folders, { id: "f2", title: "Classics", sources: [] }] }],
      updatedAt: "2026-10-26T10:00:00.000Z",
    });
    await renderSection();

    await user.click(screen.getByRole("button", { name: "Move Classics up" }));

    const titles = screen.getAllByLabelText("Folder title").map((input) => (input as HTMLInputElement).value);
    expect(titles).toEqual(["Classics", "Picks"]);
  });

  it("previews each folder's first source live, through the server, from the address it carries", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    previewCollectionSource.mockResolvedValue({ metas: [{ id: "tt9", type: "movie", name: "Alien" }] });
    getCollections.mockResolvedValue({
      collections: [
        {
          ...FROM_TV,
          folders: [
            {
              id: "f1",
              title: "Picks",
              sources: [
                { provider: "addon", addonId: "org.example", addonUrl: "https://addon.example", type: "movie", catalogId: "top", title: "Top" },
              ],
            },
          ],
        },
      ],
      updatedAt: "2026-10-26T10:00:00.000Z",
    });
    await renderSection();

    const preview = await screen.findByRole("list", { name: /^Preview of Top/ });
    expect(within(preview).getByTitle("Alien")).toBeInTheDocument();
    // The site's CSP only lets the browser reach this deployment, so the server asks the addon.
    expect(previewCollectionSource).toHaveBeenCalledWith(
      expect.objectContaining({ provider: "addon", addonUrl: "https://addon.example", catalogId: "top" })
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("Addon manifest URL")).not.toBeInTheDocument();
    fetchMock.mockRestore();
  });
});
