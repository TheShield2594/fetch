import { useEffect, useState } from "react";
import { Link } from "react-router";
import { BarChart3, Bell, ChevronRight, Download, List as ListIcon, Loader2, Pencil, Users } from "lucide-react";
import {
  api,
  type CatalogList,
  type DetailedWatchStats,
  type Profile,
  type ProfileSummary,
  type SearchResult,
  type WatchStats,
  type YearInReviewStats,
} from "../api";
import { useProfile, useIsKidsProfile } from "../hooks/useProfile";
import { useSettingsHealth } from "../hooks/useSettingsHealth";
import { healthDotClass } from "../components/settings/health";
import { PageFrame } from "../components/PageFrame";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { ProfileAvatar } from "../components/ProfileAvatar";
import { ProfileBadges } from "../components/profile/ProfileBadges";
import { ProfileSheet } from "../components/profile/ProfileSheet";
import { PosterGrid } from "../components/PosterGrid";
import { kindWord, PosterCard } from "../components/PosterCard";
import { useTitlePanel } from "../components/media-detail/titlePanel";
import { downloadProfileExport } from "../utils/exportDownload";

const PANEL = "rounded-2xl border p-5";
const PANEL_STYLE = { borderColor: "var(--border)", background: "var(--bg-1)" } as const;

/** The sources a profile signs in to itself, each with its own row on the status board. */
const PROFILE_SOURCES = [
  { id: "trakt", title: "Trakt" },
  { id: "simkl", title: "Simkl" },
  { id: "mdblist", title: "MDBList" },
  { id: "media-servers", title: "Media servers" },
] as const;

const toSearchResult = (item: { imdbId: string; type: string; name: string | null; poster: string | null }): SearchResult => ({
  imdbId: item.imdbId,
  type: item.type === "movie" ? "movie" : "series",
  name: item.name ?? item.imdbId,
  year: null,
  poster: item.poster,
  description: null,
  genres: [],
  rating: null,
  inWatchlist: false,
  inCollection: false,
  lists: [],
});

/** Settles to the value, or null when the request failed: each block stands alone. */
const settle = <T,>(promise: Promise<T>) => promise.then((value) => value, () => null);

function Figure({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <dt className="eyebrow">{label}</dt>
      <dd className="mt-1 text-2xl font-semibold tabular-nums" style={{ color: "var(--text)" }}>
        {value ?? "–"}
      </dd>
    </div>
  );
}

/**
 * The active profile's own page, reached from the avatar: who it is, what it
 * has watched, its lists, the services it is signed in to, and its data.
 *
 * Its totals used to appear only inside the delete confirmation.
 */
export function ProfilePage() {
  const { profile, patchProfile, openSwitcher } = useProfile();
  const isKids = useIsKidsProfile();
  const { openTitle } = useTitlePanel();
  const { sections: health } = useSettingsHealth();
  const [stats, setStats] = useState<WatchStats | null>(null);
  const [detailed, setDetailed] = useState<DetailedWatchStats | null>(null);
  const [year, setYear] = useState<YearInReviewStats | null>(null);
  const [summary, setSummary] = useState<ProfileSummary | null>(null);
  const [lists, setLists] = useState<CatalogList[] | null>(null);
  const [household, setHousehold] = useState<Profile[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const profileId = profile?.id;

  useEffect(() => {
    if (!profileId) return;
    let cancelled = false;
    const thisYear = new Date().getFullYear();
    void Promise.all([
      settle(api.getWatchStats()),
      settle(api.getDetailedStats()),
      settle(api.getYearInReview(thisYear)),
      settle(api.getProfileSummary(profileId)),
      settle(api.getLists()),
      settle(api.getProfiles()),
    ]).then(([nextStats, nextDetailed, nextYear, nextSummary, nextLists, nextProfiles]) => {
      if (cancelled) return;
      setStats(nextStats);
      setDetailed(nextDetailed);
      setYear(nextYear);
      setSummary(nextSummary);
      setLists(nextLists?.lists ?? null);
      setHousehold(nextProfiles?.profiles ?? []);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [profileId]);

  if (!profile) {
    return (
      <PageFrame width="reading">
        <h1 className="sr-only">Profile</h1>
        <p className="flex items-center gap-2 text-sm" style={{ color: "var(--text-mute)" }}>
          <Loader2 size={16} className="animate-spin" /> Loading profile…
        </p>
      </PageFrame>
    );
  }

  const index = household.findIndex((p) => p.id === profile.id);
  const isPrimary = index === 0;
  const hoursThisYear = year ? Math.round(year.totalRuntimeMinutes / 60) : null;

  const exportNow = async () => {
    setExporting(true);
    setExportError(null);
    try {
      await downloadProfileExport(profile.name);
    } catch (err) {
      setExportError(err instanceof Error ? err.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  return (
    <PageFrame width="reading" className="space-y-8">
      <header className="flex flex-col items-start gap-5 sm:flex-row sm:items-center">
        <ProfileAvatar profile={profile} size="xl" />
        <div className="min-w-0 flex-1">
          <h1 className={`${PAGE_TITLE} truncate`} style={{ color: "var(--text)" }}>{profile.name}</h1>
          <ProfileBadges profile={profile} isActive={false} isPrimary={isPrimary} className="mt-2" />
          <div className="mt-4 flex flex-wrap gap-2">
            {!isKids && (
              <button type="button" onClick={() => setEditing(true)} className="btn-secondary btn-sm" disabled={index < 0}>
                <Pencil size={14} /> Edit profile
              </button>
            )}
            <button type="button" onClick={openSwitcher} className="btn-secondary btn-sm">
              <Users size={14} /> Switch profile
            </button>
          </div>
        </div>
      </header>

      <section aria-labelledby="profile-totals" className="space-y-3">
        <div className="flex items-baseline justify-between gap-3">
          <h2 id="profile-totals" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Watched</h2>
          <Link to="/stats" className="inline-flex items-center gap-1 text-sm font-medium text-accent-text">
            <BarChart3 size={14} aria-hidden="true" /> All stats
          </Link>
        </div>
        <dl className={`${PANEL} grid grid-cols-2 gap-y-5 sm:grid-cols-4`} style={PANEL_STYLE}>
          <Figure label="Films" value={stats ? stats.totalMovies.toLocaleString() : null} />
          <Figure label="Episodes" value={stats ? stats.totalEpisodes.toLocaleString() : null} />
          <Figure label={`Hours in ${new Date().getFullYear()}`} value={hoursThisYear !== null ? hoursThisYear.toLocaleString() : null} />
          <Figure label="Ratings" value={summary ? summary.ratings.toLocaleString() : null} />
        </dl>
        {detailed && detailed.currentStreak > 0 && (
          <p className="text-sm" style={{ color: "var(--text-dim)" }}>
            On a {detailed.currentStreak.toLocaleString()}-day streak. The longest was {detailed.longestStreak.toLocaleString()} days.
          </p>
        )}
      </section>

      {detailed && detailed.topRated.length > 0 && (
        <section aria-labelledby="profile-top" className="space-y-3">
          <h2 id="profile-top" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Best reviewed of what {profile.name} watched</h2>
          <PosterGrid density="compact">
            {detailed.topRated.slice(0, 6).map((item) => (
              <PosterCard
                key={`${item.type}:${item.imdbId}`}
                poster={item.poster}
                name={item.name}
                onOpen={() => openTitle(toSearchResult(item))}
                meta={kindWord(item.type)}
                rating={item.rating != null ? { source: "tmdb", value: item.rating } : null}
              />
            ))}
          </PosterGrid>
        </section>
      )}

      <section aria-labelledby="profile-lists" className="space-y-3">
        <div className="flex items-baseline justify-between gap-3">
          <h2 id="profile-lists" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Lists</h2>
          <Link to="/lists" className="text-sm font-medium text-accent-text">Open Lists</Link>
        </div>
        {lists === null ? (
          <p className="text-sm" style={{ color: "var(--text-mute)" }}>{loading ? "Loading…" : "Lists couldn't be loaded."}</p>
        ) : lists.length === 0 ? (
          <p className="text-sm" style={{ color: "var(--text-mute)" }}>No lists yet.</p>
        ) : (
          <ul className={`${PANEL} divide-y py-1`} style={{ ...PANEL_STYLE, borderColor: "var(--border)" }}>
            {lists.slice(0, 8).map((list) => (
              <li key={list.id} className="flex items-center gap-3 py-2.5" style={{ borderColor: "var(--border)" }}>
                <ListIcon size={16} aria-hidden="true" style={{ color: "var(--text-mute)" }} />
                <span className="min-w-0 flex-1 truncate text-sm" style={{ color: "var(--text)" }}>{list.name}</span>
                <span className="text-sm tabular-nums" style={{ color: "var(--text-mute)" }}>
                  {list.itemCount.toLocaleString()} {list.itemCount === 1 ? "title" : "titles"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {!isKids && (
        <>
          <section aria-labelledby="profile-services" className="space-y-3">
            <div>
              <h2 id="profile-services" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Connected services</h2>
              <p className="mt-1 text-sm" style={{ color: "var(--text-dim)" }}>
                Signed in for {profile.name} only. Other profiles connect their own.
              </p>
            </div>
            <ul className="grid gap-2 sm:grid-cols-2">
              {PROFILE_SOURCES.map((source) => {
                const state = health[source.id];
                return (
                  <li key={source.id}>
                    <Link
                      to={`/settings?section=${source.id}`}
                      className="flex items-center gap-3 rounded-xl border px-4 py-3 transition-colors hover:bg-[var(--surface)] focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
                      style={{ borderColor: "var(--border)" }}
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium" style={{ color: "var(--text)" }}>{source.title}</span>
                        <span className="mt-0.5 flex items-center gap-1.5 text-xs" style={{ color: "var(--text-mute)" }}>
                          {state && <span aria-hidden="true" className={healthDotClass(state.tone)} />}
                          {state?.label ?? "Open"}
                        </span>
                      </span>
                      <ChevronRight size={16} aria-hidden="true" style={{ color: "var(--text-mute)" }} />
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>

          <section aria-labelledby="profile-more" className="space-y-3">
            <h2 id="profile-more" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Notifications and data</h2>
            <div className="flex flex-wrap gap-2">
              <Link to="/settings?section=notifications" className="btn-secondary btn-sm">
                <Bell size={14} /> Notifications for {profile.name}
              </Link>
              <button type="button" onClick={() => void exportNow()} disabled={exporting} className="btn-secondary btn-sm">
                {exporting ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />} Export {profile.name}&apos;s data
              </button>
            </div>
            <p className="text-xs" style={{ color: "var(--text-mute)" }}>
              The export is {profile.name}&apos;s history, lists, ratings and progress as one JSON file. Import it in Settings → Data.
            </p>
            {exportError && <p role="alert" className="text-sm text-danger">{exportError}</p>}
          </section>
        </>
      )}

      {editing && index >= 0 && (
        <ProfileSheet
          profile={profile}
          isActive
          isPrimary={isPrimary}
          isOnlyProfile={household.length === 1}
          onUpdated={(updated) => {
            patchProfile(updated);
            setHousehold((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
          }}
          onDeleted={() => openSwitcher()}
          onSwitch={openSwitcher}
          onClose={() => setEditing(false)}
        />
      )}
    </PageFrame>
  );
}
