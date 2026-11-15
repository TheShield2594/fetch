import { useState } from "react";
import { AlertCircle, Check, Copy, Loader2, Merge } from "lucide-react";
import { api, type DuplicateWatchGroup } from "../../api";
import { ConfirmReview } from "../ConfirmReview";

const plural = (count: number, one: string, many: string) => `${count.toLocaleString()} ${count === 1 ? one : many}`;

const groupKey = (group: DuplicateWatchGroup) => group.keepId;

/** "The Sopranos · S1 E3", or the film's name. */
export function duplicateGroupTitle(group: DuplicateWatchGroup): string {
  const name = group.title ?? group.imdbId;
  return group.type === "episode" && group.season != null && group.episode != null
    ? `${name} · S${group.season} E${group.episode}`
    : name;
}

const formatWatch = (event: DuplicateWatchGroup["events"][number]) =>
  event.dateUnknown
    ? "Date unknown"
    : new Date(event.watchedAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

/**
 * Settings → Data: find watches recorded more than once and fold them into one.
 *
 * Same-day copies are already refused by the server, and re-importing a file
 * skips what is already here, so what this finds is what no rule can decide on
 * its own: the same evening dated to two days by two sources, a "date unknown"
 * mark beside the real watch. Every group starts selected; untick any that are
 * genuine rewatches.
 */
export function DuplicateWatchesSection({ profileName }: { profileName: string }) {
  const [groups, setGroups] = useState<DuplicateWatchGroup[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [scanning, setScanning] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [merging, setMerging] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const scan = async () => {
    // A new scan replaces the list and selects every group again, so a review
    // still open for the old selection must not survive it.
    setConfirming(false);
    setScanning(true);
    setError(null);
    setResult(null);
    try {
      const res = await api.findDuplicateWatches();
      setGroups(res.groups);
      setTruncated(res.truncated);
      setSelected(new Set(res.groups.map(groupKey)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not look for duplicates.");
    } finally {
      setScanning(false);
    }
  };

  const merge = async () => {
    if (!groups) return;
    const chosen = groups.filter((group) => selected.has(groupKey(group)));
    setMerging(true);
    setError(null);
    try {
      const res = await api.mergeDuplicateWatches(chosen.map((group) => group.events.map((event) => event.id)));
      setResult(
        `Merged ${plural(res.merged, "duplicate", "duplicates")}, removing ${plural(res.removed, "extra watch", "extra watches")}.`
      );
      setConfirming(false);
      const left = groups.filter((group) => !selected.has(groupKey(group)));
      setGroups(left);
      setSelected(new Set());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not merge the duplicates.");
    } finally {
      setMerging(false);
    }
  };

  const toggle = (key: string) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const selectedCount = groups?.filter((group) => selected.has(groupKey(group))).length ?? 0;

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-semibold" style={{ color: "var(--text-dim)" }}>Duplicate watches</p>
        <p className="text-sm leading-relaxed" style={{ color: "var(--text-dim)" }}>
          Imports and connected services skip a watch {profileName} already has on the same day, and importing the
          same file twice adds nothing. Look here for copies that slipped past that: the same watch dated a day apart
          by two sources, or a &ldquo;date unknown&rdquo; mark next to the real one.
        </p>
      </div>

      <button type="button" onClick={() => void scan()} disabled={scanning || merging} className="btn-secondary">
        {scanning ? (
          <>
            <Loader2 size={16} className="animate-spin" /> Looking…
          </>
        ) : (
          <>
            <Copy size={16} /> {groups ? "Look again" : "Find duplicates"}
          </>
        )}
      </button>

      {groups && groups.length === 0 && !result && (
        <p role="status" className="flex items-center gap-2 text-sm text-success">
          <Check size={16} /> No duplicate watches found.
        </p>
      )}

      {groups && groups.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm" style={{ color: "var(--text-dim)" }}>
            {plural(groups.length, "possible duplicate", "possible duplicates")}
            {truncated ? " (not all of them; merge these, then look again for the rest)" : ""}. Untick any that were
            really watched twice.
          </p>
          <ul className="space-y-2" aria-label="Possible duplicate watches">
            {groups.map((group) => {
              const key = groupKey(group);
              const title = duplicateGroupTitle(group);
              return (
                <li key={key} className="rounded-xl border p-3" style={{ borderColor: "var(--border)" }}>
                  <label className="flex items-start gap-3">
                    <input
                      type="checkbox"
                      checked={selected.has(key)}
                      onChange={() => toggle(key)}
                      aria-label={`Merge ${title}`}
                      className="mt-1"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-medium" style={{ color: "var(--text)" }}>
                        {title}
                      </span>
                      <span className="mt-1 block space-y-0.5">
                        {group.events.map((event) => (
                          <span key={event.id} className="block text-xs" style={{ color: "var(--text-dim)" }}>
                            {formatWatch(event)}
                            {event.plays > 1 ? ` · ${plural(event.plays, "play", "plays")}` : ""}
                            {event.fromTrakt ? " · from Trakt" : ""}
                            {event.id === group.keepId ? " · kept" : ""}
                          </span>
                        ))}
                      </span>
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>

          {confirming ? (
            <ConfirmReview
              label={`Merge ${plural(selectedCount, "duplicate", "duplicates")}?`}
              message={
                <>
                  Each selected title keeps one watch, the one marked &ldquo;kept&rdquo;, with the most plays any copy
                  had and any note. The other copies are removed from <strong>{profileName}</strong>. This can&apos;t
                  be undone. Nothing changes on Trakt or other services.
                </>
              }
              confirmLabel="Merge"
              icon={<Merge size={14} />}
              tone="primary"
              onConfirm={() => void merge()}
              disabled={scanning}
              onCancel={() => setConfirming(false)}
              busy={merging}
              error={error}
              className="space-y-3 rounded-xl border p-3"
            />
          ) : (
            <button
              type="button"
              onClick={() => {
                setResult(null);
                setError(null);
                setConfirming(true);
              }}
              disabled={selectedCount === 0 || scanning}
              className="btn-primary"
            >
              <Merge size={16} /> Merge {plural(selectedCount, "selected", "selected")}
            </button>
          )}
        </div>
      )}

      {result && (
        <p role="status" className="flex items-center gap-2 text-sm text-success">
          <Check size={16} /> {result}
        </p>
      )}
      {error && !confirming && (
        <p role="alert" className="flex items-center gap-2 text-sm text-danger">
          <AlertCircle size={16} /> {error}
        </p>
      )}
    </div>
  );
}
