import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type CatalogList } from "../api";
import { AddToListControl } from "./AddToListControl";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: { addToList: vi.fn(), removeFromList: vi.fn(), createList: vi.fn() },
  };
});

const { api } = await import("../api");
const addToList = vi.mocked(api.addToList);
const removeFromList = vi.mocked(api.removeFromList);
const createList = vi.mocked(api.createList);

const WATCHLIST: CatalogList = { id: "l-watch", name: "Watchlist", kind: "watchlist", itemCount: 0 };
const SCIFI: CatalogList = { id: "l-scifi", name: "Sci-Fi Night", kind: "custom", itemCount: 0 };
const TITLE = { type: "movie" as const, imdbId: "tt-solaris", name: "Solaris" };

// The control keeps no membership of its own; this harness plays the caller.
function Harness({ initial = [] as string[], notify = vi.fn() }) {
  const [memberIds, setMemberIds] = useState<string[]>(initial);
  const [lists, setLists] = useState<CatalogList[]>([WATCHLIST, SCIFI]);
  return (
    <>
      <AddToListControl
        title={TITLE}
        lists={lists}
        memberIds={memberIds}
        onMemberChange={(listId, member) =>
          setMemberIds((ids) => (member ? [...ids, listId] : ids.filter((id) => id !== listId)))
        }
        onListCreated={(list) => setLists((prev) => [...prev, list])}
        notify={notify}
      />
      <p data-testid="members">{memberIds.join(",")}</p>
    </>
  );
}

const members = () => screen.getByTestId("members").textContent;

const openPanel = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole("button", { name: "Add Solaris to a list" }));
  return within(screen.getByRole("group", { name: "Add Solaris to a list" }));
};

beforeEach(() => {
  vi.clearAllMocks();
  addToList.mockResolvedValue(undefined as never);
  removeFromList.mockResolvedValue(undefined as never);
});

describe("AddToListControl", () => {
  it("ticks the lists the title is in and toggles membership either way", async () => {
    const user = userEvent.setup();
    const notify = vi.fn();
    render(<Harness initial={[SCIFI.id]} notify={notify} />);

    let panel = await openPanel(user);
    expect(panel.getByRole("button", { name: "Sci-Fi Night" })).toHaveAttribute("aria-pressed", "true");
    expect(panel.getByRole("button", { name: "Watchlist" })).toHaveAttribute("aria-pressed", "false");

    await user.click(panel.getByRole("button", { name: "Watchlist" }));
    expect(addToList).toHaveBeenCalledWith(WATCHLIST.id, { type: "movie", imdbId: "tt-solaris", title: "Solaris" });
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Added "Solaris" to Watchlist', "success"));

    panel = await openPanel(user);
    await user.click(panel.getByRole("button", { name: "Sci-Fi Night" }));
    expect(removeFromList).toHaveBeenCalledWith(SCIFI.id, { type: "movie", imdbId: "tt-solaris" });
    await waitFor(() => expect(members()).toBe(WATCHLIST.id));
    // A removal carries the way back.
    expect(notify).toHaveBeenCalledWith('Removed "Solaris" from Sci-Fi Night', "info", {
      action: expect.objectContaining({ label: "Undo" }),
    });
  });

  it("shows the change at once and takes it back when the request fails", async () => {
    const user = userEvent.setup();
    const notify = vi.fn();
    let reject: (error: Error) => void = () => {};
    addToList.mockImplementation(() => new Promise((_, r) => { reject = r; }));
    render(<Harness notify={notify} />);

    const panel = await openPanel(user);
    await user.click(panel.getByRole("button", { name: "Sci-Fi Night" }));
    expect(members()).toBe(SCIFI.id);

    reject(new Error("list is full"));
    await waitFor(() => expect(members()).toBe(""));
    expect(notify).toHaveBeenCalledWith('Couldn\'t add "Solaris" (list is full)', "error");
  });

  it("treats a title that was already there as added", async () => {
    const user = userEvent.setup();
    const notify = vi.fn();
    addToList.mockRejectedValue(new ApiError("Item already exists in this list", 409));
    render(<Harness notify={notify} />);

    const panel = await openPanel(user);
    await user.click(panel.getByRole("button", { name: "Sci-Fi Night" }));

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Added "Solaris" to Sci-Fi Night', "success"));
    expect(members()).toBe(SCIFI.id);
  });

  it("makes a list and adds the title to it", async () => {
    const user = userEvent.setup();
    const notify = vi.fn();
    createList.mockResolvedValue({ list: { id: "l-new", name: "Tarkovsky", kind: "custom", itemCount: 0 } });
    render(<Harness notify={notify} />);

    const panel = await openPanel(user);
    await user.click(panel.getByRole("button", { name: /new list/i }));
    await user.type(panel.getByLabelText("New list name"), "Tarkovsky{Enter}");

    expect(createList).toHaveBeenCalledWith("Tarkovsky");
    await waitFor(() => expect(members()).toBe("l-new"));
    expect(notify).toHaveBeenCalledWith('Created "Tarkovsky" and added "Solaris"', "success");
  });

  it("closes on Escape and gives focus back to the trigger", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await openPanel(user);
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("group", { name: "Add Solaris to a list" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add Solaris to a list" })).toHaveFocus();
  });
});
