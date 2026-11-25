import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ListSharePanel } from "./ListSharePanel";
import type { CatalogList } from "../api";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, api: { shareList: vi.fn(), unshareList: vi.fn() } };
});

const { api } = await import("../api");
const shareList = vi.mocked(api.shareList);
const unshareList = vi.mocked(api.unshareList);

const ID = "22222222-2222-4222-8222-222222222222";
const path = (key: string) => `/shared/lists/${ID}/${key.repeat(43)}`;
const list = (over: Partial<CatalogList> = {}): CatalogList => ({ id: ID, name: "Together", kind: "custom", itemCount: 2, ...over });
const onMessage = vi.fn();
const onChanged = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  unshareList.mockResolvedValue(undefined);
});

describe("ListSharePanel", () => {
  it("makes a link for a list that isn't shared and shows the page to send", async () => {
    shareList.mockResolvedValue({ sharePath: path("a") });
    render(<ListSharePanel list={list()} onMessage={onMessage} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole("button", { name: /make a share link/i }));

    expect(shareList).toHaveBeenCalledWith(ID);
    expect(await screen.findByLabelText("Share link")).toHaveValue(`${window.location.origin}/shared/list/${ID}/${"a".repeat(43)}`);
    expect(onChanged).toHaveBeenCalled();
  });

  it("shows an already-shared list's link, and stops sharing it", async () => {
    render(<ListSharePanel list={list({ sharePath: path("a") })} onMessage={onMessage} onChanged={onChanged} />);

    expect(screen.getByLabelText("Share link")).toHaveValue(`${window.location.origin}/shared/list/${ID}/${"a".repeat(43)}`);
    await userEvent.click(screen.getByRole("button", { name: /stop sharing/i }));

    expect(unshareList).toHaveBeenCalledWith(ID);
    expect(await screen.findByRole("button", { name: /make a share link/i })).toBeInTheDocument();
  });

  it("replaces the link with a new one", async () => {
    shareList.mockResolvedValue({ sharePath: path("b") });
    render(<ListSharePanel list={list({ sharePath: path("a") })} onMessage={onMessage} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole("button", { name: /replace link/i }));

    expect(await screen.findByDisplayValue(new RegExp(`${"b".repeat(43)}$`))).toBeInTheDocument();
  });

  it("reports a failure and leaves the state as it was", async () => {
    shareList.mockRejectedValue(new Error("List not found"));
    render(<ListSharePanel list={list()} onMessage={onMessage} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole("button", { name: /make a share link/i }));

    expect(onMessage).toHaveBeenCalledWith("List not found", "error");
    expect(screen.queryByLabelText("Share link")).not.toBeInTheDocument();
  });
});
