import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Heart, X } from "lucide-react";
import { api, type CatalogList, type MediaType } from "../../api";
import { useListMembership } from "../../hooks/useListMembership";
import type { ShowToast } from "../../hooks/useToast";
import { AddToListControl } from "../AddToListControl";

export function ListsSection({
  imdbId, type, name, initialListIds, onError, onToast,
}: {
  imdbId: string;
  type: MediaType;
  name: string;
  initialListIds: string[];
  onError?: (message: string) => void;
  onToast?: (message: string, type: "success" | "info") => void;
}) {
  const [lists, setLists] = useState<CatalogList[]>([]);
  const [listsLoading, setListsLoading] = useState(true);
  const [memberIds, setMemberIds] = useState<string[]>(initialListIds);

  // Membership resets when the panel moves to a different title, and only then.
  // `initialListIds` is a fresh array on each parent render, so depending on it
  // would discard the optimistic add/remove this component applies locally
  // every time the parent re-rendered for an unrelated reason. The ref lets the
  // reset read the current value without becoming a trigger for it.
  const initialListIdsRef = useRef(initialListIds);
  initialListIdsRef.current = initialListIds;

  useEffect(() => {
    setMemberIds(initialListIdsRef.current);
    // What the opener knew is only a starting point, and often nothing: a
    // title reached by URL, a recommendation hop or a Stats poster arrives
    // with no lists at all, which drew a title already on the Watchlist as
    // one that could be added to it. Ask, and keep what was passed in until
    // the answer lands — or for good, if it never does.
    let cancelled = false;
    api.getItemLists(imdbId)
      .then((res) => {
        if (!cancelled) setMemberIds(res.lists.filter((entry) => entry.type === type).map((entry) => entry.listId));
      })
      .catch(() => { /* best-effort: the passed-in membership stands */ });
    return () => { cancelled = true; };
  }, [imdbId, type]);

  useEffect(() => {
    let cancelled = false;
    api.getLists()
      .then((res) => { if (!cancelled) setLists(res.lists); })
      .catch(() => { /* best-effort */ })
      .finally(() => { if (!cancelled) setListsLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // The panel's toasts come in as two callbacks; the shared hook speaks the
  // app's one toast signature. An Undo can't ride through `onToast`, so a
  // removal made here says what happened and the chip is the way back.
  const notify = useCallback<ShowToast>(
    (message, kind = "success") => {
      if (kind === "error") onError?.(message);
      else onToast?.(message, kind);
    },
    [onError, onToast]
  );
  const membership = useListMembership(notify);
  const title = { type, imdbId, name };

  const applyMember = (listId: string, member: boolean) =>
    setMemberIds((ids) => (member ? (ids.includes(listId) ? ids : [...ids, listId]) : ids.filter((id) => id !== listId)));

  const toggle = (list: CatalogList) => {
    if (membership.isPending(list.id, title)) return;
    void membership.setMembership(list, title, !memberIds.includes(list.id), (member) => applyMember(list.id, member));
  };

  if (listsLoading) return <div className="skeleton h-8 w-40 rounded-full" />;

  const watchlist = lists.find((l) => l.kind === "watchlist");
  const inWatchlist = watchlist ? memberIds.includes(watchlist.id) : false;
  const memberLists = lists.filter((l) => memberIds.includes(l.id));

  return (
    <div className="flex flex-wrap items-center gap-2">
      {memberLists.map((list) => (
        <button
          key={list.id}
          type="button"
          onClick={() => toggle(list)}
          disabled={membership.isPending(list.id, title)}
          aria-label={`Remove from ${list.name}`}
          className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-success ring-1 ring-emerald-500/20 transition-colors hover:bg-emerald-500/20 disabled:opacity-50"
        >
          <Check className="h-3 w-3" />{list.name}
          <X className="h-3 w-3" />
        </button>
      ))}

      {watchlist && !inWatchlist && (
        <button
          type="button"
          onClick={() => toggle(watchlist)}
          disabled={membership.isPending(watchlist.id, title)}
          aria-label={`Add ${name} to Watchlist`}
          className="inline-flex items-center gap-1.5 rounded-full bg-accent-500 px-3 py-1.5 text-xs font-semibold text-accent-on transition-colors hover:bg-accent-600 disabled:opacity-50"
        >
          <Heart className="h-3 w-3" /> Watchlist
        </button>
      )}

      {/* The same control Search's cards use. The watchlist already has its
          own button above, so it isn't repeated in the panel. */}
      <AddToListControl
        variant="pill"
        title={title}
        lists={lists}
        memberIds={memberIds}
        hiddenListIds={watchlist ? [watchlist.id] : undefined}
        onMemberChange={applyMember}
        onListCreated={(list) => setLists((prev) => (prev.some((l) => l.id === list.id) ? prev : [...prev, list]))}
        notify={notify}
        membership={membership}
        panelClassName="absolute left-0 top-full z-30 mt-1 w-56"
      />
    </div>
  );
}
