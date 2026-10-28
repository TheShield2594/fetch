import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CatalogList } from "../api";
import { describeListLinkSync, ListLinkPanel } from "./ListLinkPanel";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return {
    ...actual,
    api: {
      getListLinkTargets: vi.fn(),
      linkList: vi.fn(),
      unlinkList: vi.fn(),
      syncListLink: vi.fn(),
    },
  };
});

const { api } = await import("../api");

const LIST: CatalogList = { id: "l-weekend", name: "Weekend", kind: "custom", itemCount: 2 };
const SYNC = { status: "ok" as const, addedLocally: 2, removedLocally: 0, pushedAdds: 1, pushedRemovals: 0, kept: 0, error: null };

describe("ListLinkPanel", () => {
  beforeEach(() => vi.clearAllMocks());

  it("links a list to one of the account's lists on the chosen service", async () => {
    vi.mocked(api.getListLinkTargets).mockResolvedValue({ lists: [{ id: "77", name: "Kids", itemCount: 4 }] });
    vi.mocked(api.linkList).mockResolvedValue({ link: null, sync: SYNC });
    const onChanged = vi.fn();
    const onMessage = vi.fn();
    render(<ListLinkPanel list={LIST} onChanged={onChanged} onMessage={onMessage} />);

    await userEvent.click(screen.getByRole("button", { name: /link to trakt or mdblist/i }));
    await userEvent.selectOptions(screen.getByLabelText("Service"), "mdblist");
    await waitFor(() => expect(api.getListLinkTargets).toHaveBeenLastCalledWith("mdblist", expect.anything()));
    await userEvent.selectOptions(await screen.findByLabelText("MDBList list"), "77");
    await userEvent.click(screen.getByRole("button", { name: "Link" }));

    await waitFor(() => expect(api.linkList).toHaveBeenCalledWith("l-weekend", "mdblist", "77"));
    expect(onChanged).toHaveBeenCalled();
    expect(onMessage).toHaveBeenCalledWith(expect.stringMatching(/Linked to MDBList/), "success");
  });

  it("says when the first sync after linking failed, and still reloads the list", async () => {
    vi.mocked(api.getListLinkTargets).mockResolvedValue({ lists: [] });
    vi.mocked(api.linkList).mockResolvedValue({
      link: null,
      sync: { ...SYNC, status: "disconnected", addedLocally: 0, pushedAdds: 0, error: "Trakt is not connected" },
    });
    const onChanged = vi.fn();
    const onMessage = vi.fn();
    render(<ListLinkPanel list={LIST} onChanged={onChanged} onMessage={onMessage} />);

    await userEvent.click(screen.getByRole("button", { name: /link to trakt or mdblist/i }));
    await userEvent.click(screen.getByRole("button", { name: "Link" }));

    await waitFor(() => expect(onMessage).toHaveBeenCalledWith(expect.stringMatching(/first sync failed: Trakt is not connected/), "error"));
    expect(onChanged).toHaveBeenCalled();
  });

  it("reloads the list after a sync that recorded an error", async () => {
    vi.mocked(api.syncListLink).mockResolvedValue({ link: null, sync: { ...SYNC, status: "partial", error: "Trakt request failed (503)" } });
    const linked: CatalogList = {
      ...LIST,
      link: { provider: "trakt", remoteId: "1", remoteName: "Weekend", lastSyncedAt: null, lastError: null },
    };
    const onChanged = vi.fn();
    const onMessage = vi.fn();
    render(<ListLinkPanel list={linked} onChanged={onChanged} onMessage={onMessage} />);

    await userEvent.click(screen.getByRole("button", { name: /sync now/i }));

    await waitFor(() => expect(onMessage).toHaveBeenCalledWith("Trakt request failed (503)", "error"));
    expect(onChanged).toHaveBeenCalled();
  });

  it("says why the service's lists can't be listed", async () => {
    vi.mocked(api.getListLinkTargets).mockRejectedValue(new Error("Trakt is not connected for this profile"));
    render(<ListLinkPanel list={LIST} onChanged={vi.fn()} onMessage={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: /link to trakt or mdblist/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/not connected/);
  });

  it("shows a linked list's state and unlinks it", async () => {
    vi.mocked(api.unlinkList).mockResolvedValue(undefined);
    const linked: CatalogList = {
      ...LIST,
      link: { provider: "trakt", remoteId: "1", remoteName: "Weekend", lastSyncedAt: null, lastError: "The linked Trakt list no longer exists." },
    };
    const onMessage = vi.fn();
    render(<ListLinkPanel list={linked} onChanged={vi.fn()} onMessage={onMessage} />);

    expect(screen.getByText(/Kept in step with/)).toHaveTextContent("Weekend on Trakt");
    expect(screen.getByRole("alert")).toHaveTextContent(/no longer exists/);

    await userEvent.click(screen.getByRole("button", { name: /unlink/i }));
    await waitFor(() => expect(api.unlinkList).toHaveBeenCalledWith("l-weekend"));
    expect(onMessage).toHaveBeenCalledWith(expect.stringMatching(/Both lists are kept/), "success");
  });

  it("describes what a sync did", () => {
    expect(describeListLinkSync(SYNC)).toBe("2 added here, 1 added there");
    expect(describeListLinkSync({ ...SYNC, addedLocally: 0, pushedAdds: 0 })).toBe("Already in step");
  });
});
