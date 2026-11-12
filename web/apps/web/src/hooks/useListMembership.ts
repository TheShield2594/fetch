import { useCallback, useRef, useState } from "react";
import { api, ApiError, type CatalogList, type MediaType } from "../api";
import type { ShowToast } from "./useToast";

/**
 * "Put this title in that list" and "take it out again" — the one
 * implementation of it.
 *
 * There used to be three: Search's quick-add disclosure, the title panel's
 * ListsSection and the Lists page's add dialog, each with its own pending map,
 * its own toast copy and its own idea of what a failed add left behind. They
 * had drifted — Search couldn't take a title back out, the panel said "Added
 * to X" where Search said `Added "Y" to X`, and only one of the three knew
 * that creating a list and adding to it are two calls that can fail apart.
 *
 * The hook doesn't own membership. Each caller already keeps it somewhere of
 * its own (a search result's `lists`, a panel's member IDs, the items of the
 * list on screen), so it hands in `apply`, which is called optimistically and
 * called again with the opposite value if the request fails. What the hook
 * owns is everything around that: the request, the pending flag that stops a
 * double tap sending two, the toast, and an Undo on a removal.
 */

export type ListTitle = { type: MediaType; imdbId: string; name: string };

/** What a list needs to be for this: its ID to call, its name to say. */
export type ListRef = Pick<CatalogList, "id" | "name">;

type Apply = (member: boolean) => void;

const pendingKey = (listId: string, title: Pick<ListTitle, "type" | "imdbId">) =>
  `${listId}:${title.type}:${title.imdbId}`;

const reasonOf = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback;

// A 409 on an add means the title is already there and a 404 on a removal
// means it is already gone: either way the list ended up where the user asked
// for it to be — another tab or the TV got there first — and saying "failed"
// would be telling them something untrue.
const alreadyThere = (error: unknown, member: boolean) =>
  error instanceof ApiError && error.status === (member ? 409 : 404);

export function useListMembership(notify: ShowToast) {
  const [pending, setPending] = useState<Record<string, true>>({});
  // The state above draws the spinner; this is what actually guards, since two
  // taps inside one render would both read the same `pending`.
  const inflight = useRef(new Set<string>());

  /** Adds (`member: true`) or removes the title. Resolves to whether it stuck. */
  const setMembership = useCallback(
    (list: ListRef, title: ListTitle, member: boolean, apply: Apply, options?: { quiet?: boolean }) => {
      // Recursive so that the Undo a removal's toast carries goes through the
      // same path — pending guard, optimistic apply, toast — as the removal.
      const run = async (want: boolean, quiet: boolean): Promise<boolean> => {
        const key = pendingKey(list.id, title);
        if (inflight.current.has(key)) return false;
        inflight.current.add(key);
        setPending((current) => ({ ...current, [key]: true }));
        apply(want);
        try {
          try {
            if (want) await api.addToList(list.id, { type: title.type, imdbId: title.imdbId, title: title.name });
            else await api.removeFromList(list.id, { type: title.type, imdbId: title.imdbId });
          } catch (error) {
            if (!alreadyThere(error, want)) throw error;
          }
          if (!quiet) {
            if (want) {
              notify(`Added "${title.name}" to ${list.name}`, "success");
            } else {
              notify(`Removed "${title.name}" from ${list.name}`, "info", {
                action: { label: "Undo", onAction: () => void run(true, false) },
              });
            }
          }
          return true;
        } catch (error) {
          apply(!want);
          const reason = reasonOf(error, "unknown error");
          notify(want ? `Couldn't add "${title.name}" (${reason})` : `Couldn't remove "${title.name}" (${reason})`, "error");
          return false;
        } finally {
          inflight.current.delete(key);
          setPending((current) => {
            const next = { ...current };
            delete next[key];
            return next;
          });
        }
      };
      return run(member, options?.quiet ?? false);
    },
    [notify]
  );

  /**
   * Makes a list and puts the title in it. Two calls, and they can fail apart:
   * a list that was created is handed to `onCreated` even when the add into it
   * then fails, so it stays on offer and a retry adds to it rather than making
   * a second one. Resolves to the list, or null if it couldn't be made.
   */
  const createListWithTitle = useCallback(
    async (
      rawName: string,
      title: ListTitle,
      { onCreated, apply }: { onCreated: (list: CatalogList) => void; apply: (listId: string, member: boolean) => void }
    ): Promise<CatalogList | null> => {
      const name = rawName.trim();
      if (!name) return null;
      let list: CatalogList;
      try {
        ({ list } = await api.createList(name));
      } catch (error) {
        notify(reasonOf(error, "Unable to create list"), "error");
        return null;
      }
      onCreated(list);
      try {
        await api.addToList(list.id, { type: title.type, imdbId: title.imdbId, title: title.name });
      } catch (error) {
        notify(`Created "${list.name}", but couldn't add "${title.name}" (${reasonOf(error, "unknown error")})`, "error");
        return list;
      }
      apply(list.id, true);
      notify(`Created "${list.name}" and added "${title.name}"`, "success");
      return list;
    },
    [notify]
  );

  const isPending = useCallback(
    (listId: string, title: Pick<ListTitle, "type" | "imdbId">) => Boolean(pending[pendingKey(listId, title)]),
    [pending]
  );

  return { setMembership, createListWithTitle, isPending };
}

export type ListMembership = ReturnType<typeof useListMembership>;
