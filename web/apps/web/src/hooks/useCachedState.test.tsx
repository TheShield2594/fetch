import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useCachedState } from "./useCachedState";
import {
  invalidate,
  readCache,
  resetDataCacheForTests,
  setCacheScope,
  writeCache,
} from "../utils/dataCache";

afterEach(() => resetDataCacheForTests());

function Counter({ cacheKey = "k" }: { cacheKey?: string }) {
  const [items, setItems, meta] = useCachedState<string[]>(cacheKey, []);
  return (
    <div>
      <span data-testid="items">{items.join(",") || "empty"}</span>
      <span data-testid="seeded">{meta.hadCachedValue ? "seeded" : "cold"}</span>
      <button type="button" onClick={() => setItems([...items, "direct"])}>direct</button>
      <button type="button" onClick={() => setItems((prev) => [...prev, "updater"])}>updater</button>
    </div>
  );
}

describe("useCachedState", () => {
  it("starts cold with the fallback when nothing is cached", () => {
    render(<Counter />);
    expect(screen.getByTestId("items")).toHaveTextContent("empty");
    expect(screen.getByTestId("seeded")).toHaveTextContent("cold");
  });

  it("paints a cached value in the very first render", () => {
    writeCache("k", ["from-cache"]);
    render(<Counter />);
    // Not after an effect, not a tick later — in the first commit. That frame is
    // the spinner this hook exists to remove.
    expect(screen.getByTestId("items")).toHaveTextContent("from-cache");
    expect(screen.getByTestId("seeded")).toHaveTextContent("seeded");
  });

  it("writes through, so unmounting and remounting keeps the data", async () => {
    const user = userEvent.setup();
    const first = render(<Counter />);
    await user.click(screen.getByRole("button", { name: "direct" }));
    expect(readCache<string[]>("k")).toEqual(["direct"]);

    first.unmount();
    render(<Counter />);
    expect(screen.getByTestId("items")).toHaveTextContent("direct");
  });

  it("accepts an updater function, the form the pages actually use", async () => {
    const user = userEvent.setup();
    render(<Counter />);
    await user.click(screen.getByRole("button", { name: "updater" }));
    await user.click(screen.getByRole("button", { name: "updater" }));
    expect(screen.getByTestId("items")).toHaveTextContent("updater,updater");
    expect(readCache<string[]>("k")).toEqual(["updater", "updater"]);
  });

  it("rehydrates when the key changes without a remount", async () => {
    // The calendar keys on its day range and the history page on its type filter,
    // so the key moves while the component stays mounted. `useState`'s
    // initialiser only runs once, so without an explicit re-read the hook would
    // keep serving the previous key's value under the new key's name.
    writeCache("history:events:all", ["every event"]);
    writeCache("history:events:movie", ["film events"]);

    function Switcher() {
      const [filter, setFilter] = useState("all");
      const [items] = useCachedState<string[]>(`history:events:${filter}`, []);
      return (
        <div>
          <span data-testid="items">{items.join(",") || "empty"}</span>
          <button type="button" onClick={() => setFilter("movie")}>films</button>
          <button type="button" onClick={() => setFilter("all")}>all</button>
        </div>
      );
    }

    const user = userEvent.setup();
    render(<Switcher />);
    expect(screen.getByTestId("items")).toHaveTextContent("every event");

    await user.click(screen.getByRole("button", { name: "films" }));
    expect(screen.getByTestId("items")).toHaveTextContent("film events");

    await user.click(screen.getByRole("button", { name: "all" }));
    expect(screen.getByTestId("items")).toHaveTextContent("every event");
  });

  it("falls back rather than carrying the old key's value into an uncached one", async () => {
    writeCache("history:events:all", ["every event"]);

    function Switcher() {
      const [filter, setFilter] = useState("all");
      const [items] = useCachedState<string[]>(`history:events:${filter}`, []);
      return (
        <div>
          <span data-testid="items">{items.join(",") || "empty"}</span>
          <button type="button" onClick={() => setFilter("episode")}>episodes</button>
        </div>
      );
    }

    const user = userEvent.setup();
    render(<Switcher />);
    await user.click(screen.getByRole("button", { name: "episodes" }));

    // Nothing cached for this filter — showing the previous filter's list here would
    // be worse than an empty one, because it looks like a real answer.
    expect(screen.getByTestId("items")).toHaveTextContent("empty");
  });

  it("writes each key separately when a switching component saves", async () => {
    function Switcher() {
      const [filter, setFilter] = useState("all");
      const [items, setItems] = useCachedState<string[]>(`history:events:${filter}`, []);
      return (
        <div>
          <span data-testid="items">{items.join(",") || "empty"}</span>
          <button type="button" onClick={() => setItems([`${filter} result`])}>save</button>
          <button type="button" onClick={() => setFilter("movie")}>films</button>
        </div>
      );
    }

    const user = userEvent.setup();
    render(<Switcher />);
    await user.click(screen.getByRole("button", { name: "save" }));
    await user.click(screen.getByRole("button", { name: "films" }));
    await user.click(screen.getByRole("button", { name: "save" }));

    // A setter bound to the first key would have filed both under `history:events:all`.
    expect(readCache<string[]>("history:events:all")).toEqual(["all result"]);
    expect(readCache<string[]>("history:events:movie")).toEqual(["movie result"]);
  });

  it("does not resurrect another profile's value after a scope change", () => {
    setCacheScope("profile-a");
    writeCache("k", ["a's list"]);
    setCacheScope("profile-b");

    render(<Counter />);
    expect(screen.getByTestId("items")).toHaveTextContent("empty");
  });

  it("drops a response that lands after the profile it was fetched for", () => {
    // The dashboard issues five requests with no idea a profile switch is
    // coming. When one lands late, `setValue` on the unmounted page is a no-op —
    // but the write-through is not, and an unguarded one files the adult
    // profile's Continue Watching under the kid profile's scope, where the
    // freshly mounted dashboard is already subscribed and paints it.
    let save: ((next: string[]) => void) | undefined;
    function Dashboard() {
      const [progress, setProgress] = useCachedState<string[]>("dash:progress", []);
      save = setProgress;
      return <span data-testid="items">{progress.join(",") || "empty"}</span>;
    }

    setCacheScope("adult#0");
    const adult = render(<Dashboard />);
    const lateResponse = save!;

    // The switch clears the cache first and unmounts the page a render later,
    // so the setter outlives the scope it belongs to.
    act(() => setCacheScope("kid#0"));
    adult.unmount();
    act(() => lateResponse(["The Sopranos"]));

    expect(readCache("dash:progress")).toBeUndefined();
  });

  it("blanks instead of painting on with the previous profile's data", () => {
    setCacheScope("adult#0");
    writeCache("k", ["The Sopranos"]);
    render(<Counter />);
    expect(screen.getByTestId("items")).toHaveTextContent("The Sopranos");

    act(() => setCacheScope("kid#0"));

    // A cleared scope is not the same as a mutation's invalidate-then-refetch:
    // there is no refetch coming that will replace this, and what is on screen
    // belongs to someone who is no longer signed in.
    expect(screen.getByTestId("items")).toHaveTextContent("empty");
  });

  it("still hears about its key after a scope change", () => {
    // Subscriptions used to be registered under the scope in force when they
    // were made, so a switch left them filed under a name nothing writes to
    // again — and every later refresh went silently missing.
    setCacheScope("adult#0");
    render(<Counter />);

    act(() => setCacheScope("kid#0"));
    act(() => writeCache("k", ["kid's list"]));

    expect(screen.getByTestId("items")).toHaveTextContent("kid's list");
  });

  it("holds its last value when a mutation invalidates the key", async () => {
    const user = userEvent.setup();
    render(<Counter />);
    await user.click(screen.getByRole("button", { name: "direct" }));

    // Invalidation clears the cache so the next read refetches; what is already
    // on screen stays, because blanking a page mid-mutation is worse than
    // showing data that is about to be replaced.
    invalidate("k");
    expect(readCache("k")).toBeUndefined();
    expect(screen.getByTestId("items")).toHaveTextContent("direct");
  });
});
