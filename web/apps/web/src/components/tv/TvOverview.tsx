import { useEffect, useState } from "react";
import { AlertCircle, AlertTriangle, Loader2 } from "lucide-react";
import { api, type SyncOverview } from "../../api";
import { StatusBadge } from "../settings/StatusBadge";
import { timeAgo } from "../../utils/timeAgo";
import { errorMessage } from "./reorder";

const COUNT_LABELS: { key: keyof SyncOverview["profiles"][number]["counts"]; label: string }[] = [
  { key: "addons", label: "Addons" },
  { key: "lists", label: "Lists" },
  { key: "resumePoints", label: "Resume points" },
  { key: "watchedItems", label: "Watched" },
];

/**
 * A read-only look at what a TV gets from this server: per profile, how much of
 * each synced thing there is and when its settings and Home layout last
 * changed, and whether the server itself is healthy enough to sync.
 */
export function TvOverview() {
  const [overview, setOverview] = useState<SyncOverview | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getSyncOverview().then(
      (result) => {
        if (!cancelled) setOverview(result);
      },
      (err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Couldn't load the overview."));
      }
    );
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return (
      <p role="alert" className="flex items-center gap-2 text-sm text-danger">
        <AlertCircle size={16} /> {error}
      </p>
    );
  }

  if (!overview) {
    return (
      <div className="flex items-center gap-2 text-sm" style={{ color: "var(--text-dim)" }}>
        <Loader2 size={16} className="animate-spin" /> Loading overview...
      </div>
    );
  }

  const failing = overview.server.jobs.filter((job) => job.status === "failed");

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge
          ok={overview.server.database.ok}
          label={overview.server.database.ok ? `Database OK · ${overview.server.database.latencyMs} ms` : "Database not answering"}
        />
        <StatusBadge
          ok={failing.length === 0}
          label={
            failing.length === 0
              ? "Background jobs healthy"
              : `${failing.length} background job${failing.length === 1 ? "" : "s"} failing`
          }
        />
      </div>

      {failing.length > 0 && (
        <ul className="space-y-1 text-xs" style={{ color: "var(--text-dim)" }}>
          {failing.map((job) => (
            <li key={job.job} className="flex items-start gap-2">
              <AlertTriangle size={14} className="mt-0.5 flex-none text-warning" />
              <span>
                <span className="font-medium" style={{ color: "var(--text)" }}>
                  {job.job}
                </span>{" "}
                · {timeAgo(job.at)}
                {job.message && <span className="block font-mono break-words">{job.message}</span>}
              </span>
            </li>
          ))}
        </ul>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="text-xs" style={{ color: "var(--text-mute)" }}>
              <th scope="col" className="py-2 pr-4 font-medium">
                Profile
              </th>
              {COUNT_LABELS.map((column) => (
                <th key={column.key} scope="col" className="py-2 pr-4 text-right font-medium">
                  {column.label}
                </th>
              ))}
              <th scope="col" className="py-2 pr-4 font-medium">
                TV settings
              </th>
              <th scope="col" className="py-2 font-medium">
                Home layout
              </th>
            </tr>
          </thead>
          <tbody>
            {overview.profiles.map((profile) => (
              <tr key={profile.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                <th scope="row" className="py-2 pr-4 font-medium" style={{ color: "var(--text)" }}>
                  {profile.name}
                </th>
                {COUNT_LABELS.map((column) => (
                  <td key={column.key} className="py-2 pr-4 text-right font-mono tabular-nums" style={{ color: "var(--text-dim)" }}>
                    {column.key === "addons" && profile.counts.addons !== profile.counts.enabledAddons
                      ? `${profile.counts.enabledAddons}/${profile.counts.addons}`
                      : profile.counts[column.key]}
                  </td>
                ))}
                <td className="py-2 pr-4 text-xs" style={{ color: "var(--text-mute)" }}>
                  {profile.tvSettingsUpdatedAt ? timeAgo(profile.tvSettingsUpdatedAt) : "Never"}
                </td>
                <td className="py-2 text-xs" style={{ color: "var(--text-mute)" }}>
                  {profile.homeLayoutUpdatedAt ? timeAgo(profile.homeLayoutUpdatedAt) : "Default"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
