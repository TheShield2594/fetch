import { type ReactNode, useEffect, useId, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { Link, Database, Info, Clapperboard, Film, Image, Globe, Star, Sparkles, Bell, Users, Activity, Search, Tv, X, Puzzle, LayoutList, SlidersHorizontal, UserCircle, UserPlus, Webhook, ListChecks, Server } from "lucide-react";
import { Section } from "../components/settings/Section";
import { healthSummary } from "../components/settings/health";
import { useSettingsHealth } from "../hooks/useSettingsHealth";
import { AccountSettings } from "../components/settings/AccountSettings";
import { InvitesSettings } from "../components/settings/InvitesSettings";
import { WebhookSettings } from "../components/settings/WebhookSettings";
import { MediaServersSettings } from "../components/settings/MediaServersSettings";
import { useAccount } from "../hooks/useAccount";
import { TraktSettings } from "../components/settings/TraktSettings";
import { TmdbSettings } from "../components/settings/TmdbSettings";
import { OmdbSettings } from "../components/settings/OmdbSettings";
import { RpdbSettings } from "../components/settings/RpdbSettings";
import { AddonSettings } from "../components/settings/AddonSettings";
import { MdbListSettings, SimklSettings } from "../components/settings/TrackerSignInSettings";
import { MdbListRatingsSettings } from "../components/settings/MdbListRatingsSettings";
import { AiSettings } from "../components/settings/AiSettings";
import { ExtrasSettings } from "../components/settings/ExtrasSettings";
import { useExtrasEnabled } from "../hooks/useExtras";
import { DataSettings } from "../components/settings/DataSettings";
import { PreferencesSettings } from "../components/settings/PreferencesSettings";
import { PushSettings } from "../components/settings/PushSettings";
import { NotificationChannelsSettings } from "../components/settings/NotificationChannelsSettings";
import { ProfileSettings } from "../components/settings/ProfileSettings";
import { DevicesSettings } from "../components/settings/DevicesSettings";
import { JobStatusSettings } from "../components/settings/JobStatusSettings";
import { TvOverview } from "../components/tv/TvOverview";
import { TvAddonsSettings } from "../components/tv/TvAddonsSettings";
import { HomeLayoutSettings } from "../components/tv/HomeLayoutSettings";
import { TvSettingsForm } from "../components/tv/TvSettingsForm";
import { PAGE_TITLE } from "../components/typography";
import { PageFrame } from "../components/PageFrame";
import { SegmentedControl, segmentedTabId } from "../components/SegmentedControl";

declare const __APP_VERSION__: string;
const APP_VERSION = typeof __APP_VERSION__ !== "undefined" ? __APP_VERSION__ : "unknown";

export type SettingsTab = "preferences" | "integrations" | "tv";

export type SettingsSection = {
  /** Also the key `Section` persists its open/closed state under. */
  id: string;
  tab: SettingsTab;
  /**
   * The eyebrow this section sits under, grouping the tab by what a thing does
   * rather than by which vendor supplies it — "where history comes from" is a
   * question someone actually has; "Trakt, Stremio, TMDB, OMDB" in a flat list
   * is not an answer to it. Sections in the same group have to be adjacent in
   * this array; the renderer prints a heading when the group changes.
   */
  group?: string;
  title: string;
  icon: ReactNode;
  /** Extra terms the section should be findable by; the title is always searched. */
  keywords: string;
  content: ReactNode;
  /**
   * Install-wide settings every account shares, which only the admin may
   * change (the server refuses anyone else). Hidden from other accounts.
   */
  adminOnly?: true;
  /** Shown only with AI recommendations turned on (see hooks/useExtras.ts). */
  extra?: true;
};

function AboutSection() {
  return (
    <div className="space-y-2 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="text-base font-semibold" style={{ color: "var(--text)" }}>Fetch <span className="font-mono text-accent-text">v{APP_VERSION}</span></p>
      <p className="text-sm">
        The web side of Fetch: what you watch, your lists and your history, kept in step with the
        Fetch app on your TV and with Stremio.
      </p>
    </div>
  );
}

export const SETTINGS_SECTIONS: SettingsSection[] = [
  {
    id: "account",
    tab: "preferences",
    title: "Account",
    icon: <UserCircle size={20} />,
    keywords: "sign in sign out log out password username login",
    content: <AccountSettings />,
  },
  {
    id: "preferences",
    tab: "preferences",
    title: "Metadata & region",
    icon: <Globe size={20} />,
    keywords: "language locale translation streaming region country spoiler protection tmdb metadata",
    content: <PreferencesSettings />,
  },
  {
    id: "profile",
    tab: "preferences",
    title: "Profile",
    icon: <Users size={20} />,
    keywords: "profiles users household switch rename pin lock delete",
    content: <ProfileSettings />,
  },
  {
    id: "devices",
    tab: "preferences",
    title: "TVs & devices",
    icon: <Tv size={20} />,
    keywords: "tv android pair pairing code qr sign in sign out revoke device token",
    content: <DevicesSettings />,
  },
  {
    id: "notifications",
    tab: "preferences",
    title: "Notifications",
    icon: <Bell size={20} />,
    keywords: "push alerts episode reminders subscribe browser ntfy gotify discord webhook channels",
    content: (
      <div className="space-y-6">
        <PushSettings />
        <div className="border-t pt-6" style={{ borderColor: "var(--border)" }}>
          <NotificationChannelsSettings />
        </div>
      </div>
    ),
  },
  {
    id: "extras",
    tab: "preferences",
    title: "Extras",
    icon: <Sparkles size={20} />,
    keywords: "ai recommendations llm extras show",
    content: <ExtrasSettings />,
  },
  {
    id: "job-status",
    adminOnly: true,
    tab: "preferences",
    title: "Sync status",
    icon: <Activity size={20} />,
    keywords: "background jobs scheduled sync failures errors trakt scrobble",
    content: <JobStatusSettings />,
  },
  {
    id: "about",
    tab: "preferences",
    title: "About",
    icon: <Info size={20} />,
    keywords: "version build release fetch",
    content: <AboutSection />,
  },
  // Where history comes from. These lead the tab because they are what the
  // page is usually opened to check: everything below is configuration, and
  // these are the ones that can quietly stop working.
  {
    id: "trakt",
    tab: "integrations",
    group: "Where history comes from",
    title: "Trakt",
    icon: <Link size={20} />,
    keywords: "scrobble import watchlist history oauth sync",
    content: <TraktSettings />,
  },
  {
    id: "simkl",
    tab: "integrations",
    group: "Where history comes from",
    title: "Simkl",
    icon: <ListChecks size={20} />,
    keywords: "simkl tracker scrobble watched history plan to watch watchlist anime sign in code",
    content: <SimklSettings />,
  },
  {
    id: "mdblist",
    tab: "integrations",
    group: "Where history comes from",
    title: "MDBList",
    icon: <ListChecks size={20} />,
    keywords: "mdblist tracker scrobble watched history watchlist sign in code",
    content: <MdbListSettings />,
  },
  {
    id: "media-servers",
    tab: "integrations",
    group: "Where history comes from",
    title: "Media servers",
    icon: <Server size={20} />,
    keywords: "plex jellyfin emby media server sign in link code watched progress resume library",
    content: <MediaServersSettings />,
  },
  {
    id: "media-server-webhooks",
    tab: "integrations",
    group: "Where history comes from",
    title: "Plex, Jellyfin & Emby webhooks",
    icon: <Webhook size={20} />,
    keywords: "plex jellyfin emby webhook media server scrobble watched link key",
    content: <WebhookSettings />,
  },
  // What titles look like once they arrive.
  {
    id: "tmdb",
    adminOnly: true,
    tab: "integrations",
    group: "Metadata & artwork",
    title: "TMDB metadata",
    icon: <Film size={20} />,
    keywords: "the movie database api key posters metadata artwork cast",
    content: <TmdbSettings />,
  },
  {
    id: "omdb",
    adminOnly: true,
    tab: "integrations",
    group: "Metadata & artwork",
    title: "OMDB ratings",
    icon: <Star size={20} />,
    keywords: "imdb rotten tomatoes metacritic scores api key",
    content: <OmdbSettings />,
  },
  {
    id: "mdblist-ratings",
    tab: "integrations",
    group: "Metadata & artwork",
    title: "Ratings from MDBList",
    icon: <Star size={20} />,
    keywords: "mdblist ratings api key imdb trakt tmdb rotten tomatoes audience letterboxd metacritic myanimelist mal order detail",
    content: <MdbListRatingsSettings />,
  },
  {
    id: "rpdb",
    adminOnly: true,
    tab: "integrations",
    group: "Metadata & artwork",
    title: "RPDB posters",
    icon: <Image size={20} />,
    keywords: "rating poster database artwork images api key",
    content: <RpdbSettings />,
  },
  // The app's own surfaces and the keys that reach them.
  {
    id: "addon",
    adminOnly: true,
    tab: "integrations",
    group: "This server",
    title: "Stremio addon",
    icon: <Clapperboard size={20} />,
    keywords: "stremio catalogs manifest install url",
    content: <AddonSettings />,
  },
  {
    id: "invites",
    adminOnly: true,
    tab: "integrations",
    group: "This server",
    title: "Invites & accounts",
    icon: <UserPlus size={20} />,
    keywords: "invite friend share sign up users accounts members remove",
    content: <InvitesSettings />,
  },
  {
    id: "data",
    tab: "integrations",
    group: "This server",
    title: "Data",
    icon: <Database size={20} />,
    keywords: "export import backup restore refresh metadata wipe",
    content: <DataSettings />,
  },
  {
    id: "ai",
    extra: true,
    tab: "integrations",
    group: "Recommendations",
    title: "AI recommendations",
    icon: <Sparkles size={20} />,
    keywords: "llm openai ollama model provider suggestions",
    content: <AiSettings />,
  },
  // What a TV pointed at this server runs, per profile. The overview is
  // all profiles; the three below edit the active one.
  {
    id: "tv-overview",
    tab: "tv",
    title: "Synced data",
    icon: <Tv size={20} />,
    keywords: "dashboard overview counts health database jobs sync devices television",
    content: <TvOverview />,
  },
  {
    id: "tv-addons",
    tab: "tv",
    group: "This profile on the TV",
    title: "TV addons",
    icon: <Puzzle size={20} />,
    keywords: "addons manifest install configure order sources streams catalogs television",
    content: <TvAddonsSettings />,
  },
  {
    id: "tv-home",
    tab: "tv",
    group: "This profile on the TV",
    title: "Home rows",
    icon: <LayoutList size={20} />,
    keywords: "home screen rows order reorder hide catalogs lists continue watching layout television",
    content: <HomeLayoutSettings />,
  },
  {
    id: "tv-settings",
    tab: "tv",
    group: "This profile on the TV",
    title: "TV playback",
    icon: <SlidersHorizontal size={20} />,
    keywords: "auto-play autoplay best match ranking resolution codec hdr audio subtitles language style essential advanced television",
    content: <TvSettingsForm />,
  },
];

/**
 * The one section per tab that starts expanded. Everything else opens on demand,
 * so a first visit is a scannable list rather than twelve stacked forms.
 */
export const INITIALLY_OPEN_SECTION_IDS = new Set(
  SETTINGS_SECTIONS.filter((section, index) => SETTINGS_SECTIONS.findIndex((s) => s.tab === section.tab) === index).map((s) => s.id)
);

/** Every whitespace-separated term has to appear in the title or keywords. */
export function matchesSearch(section: SettingsSection, query: string): boolean {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return true;
  const haystack = `${section.title} ${section.keywords}`.toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

export const SETTINGS_TABS: { id: SettingsTab; label: string }[] = [
  { id: "preferences", label: "Preferences" },
  { id: "integrations", label: "Integrations & Data" },
  { id: "tv", label: "TV" },
];

function isSettingsTab(value: string | null): value is SettingsTab {
  return value === "preferences" || value === "integrations" || value === "tv";
}

/**
 * The settings page: Preferences and Integrations tabs built from collapsible
 * `Section`s, with a search box and the integration health dots that are shared
 * with the sidebar's Sources rows.
 */
export function SettingsPage() {
  // `sections` is shared with the rail, which shows the same dots against its
  // Sources rows — see the provider note in useSettingsHealth. It is read once
  // for the tab and re-asked here, because this page is the status board: a
  // reader who navigated to it deliberately is owed what is true now, not what
  // was true when the app started.
  const { sections: health, refresh: refreshHealth } = useSettingsHealth();
  useEffect(() => { refreshHealth(); }, [refreshHealth]);
  const [searchParams, setSearchParams] = useSearchParams();
  // `?section=<id>` (Stremio's Configure button sends `section=addon`) picks the
  // tab that section is on, unless `tab` says otherwise, and opens it.
  const requestedSectionId = searchParams.get("section");
  const requestedSection = SETTINGS_SECTIONS.find((s) => s.id === requestedSectionId);
  const tabParam = searchParams.get("tab");
  const tab: SettingsTab = isSettingsTab(tabParam) ? tabParam : requestedSection?.tab ?? "preferences";
  const [query, setQuery] = useState("");
  // A link to one section (a rail source row) asks to see that section. A
  // search left in the box would filter it out of view, so a new request
  // clears it — derived during render rather than in an effect.
  const [lastRequestedSection, setLastRequestedSection] = useState(requestedSectionId);
  if (requestedSectionId !== lastRequestedSection) {
    setLastRequestedSection(requestedSectionId);
    if (requestedSectionId) setQuery("");
  }
  const searching = query.trim().length > 0;

  const setTab = (next: SettingsTab) => {
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      params.set("tab", next);
      return params;
    });
  };

  const tabsId = useId();
  const { isAdmin } = useAccount();
  const showExtras = useExtrasEnabled();
  const visible = useMemo(() => {
    // A section whose answer the server's environment already holds (a TMDB
    // key in TMDB_API_KEY) has nothing to ask, so it isn't listed.
    const allowed = SETTINGS_SECTIONS.filter(
      (s) => (isAdmin || !s.adminOnly) && (showExtras || !s.extra) && !health[s.id]?.setByServer,
    );
    return searching ? allowed.filter((s) => matchesSearch(s, query)) : allowed.filter((s) => s.tab === tab);
  }, [health, isAdmin, showExtras, query, searching, tab]);

  // Empty until the status requests land, and empty again if every one of them
  // failed — in which case saying nothing is right, because the alternative is
  // reporting a health the page could not actually read.
  const summary = healthSummary(health);

  return (
    <PageFrame width="reading" className="space-y-4">
      <div>
        <h1 className={PAGE_TITLE} style={{ color: "var(--text)" }}>Settings</h1>
        {/*
         * Health first. Every number here was already in the app — one level
         * inside the Sync Status section and inside each integration's own
         * panel — which is to say it was everywhere except where someone
         * wondering "is this working?" would look.
         *
         * A polite live region because it arrives a moment after the page: six
         * status requests resolve together, and a screen reader that has
         * already moved on should hear the answer rather than have it appear
         * silently behind it.
         */}
        {summary && (
          <p role="status" className="meta-row mt-1.5" style={{ color: "var(--text-mute)" }}>
            {summary}
          </p>
        )}
      </div>

      <div className="relative">
        <Search size={16} className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2" style={{ color: "var(--text-mute)" }} />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setQuery("");
          }}
          placeholder="Search settings…"
          aria-label="Search settings"
          className="w-full rounded-full py-2.5 pl-11 pr-11 text-sm placeholder:text-[var(--text-mute)] field-control transition-[border-color,box-shadow] duration-base"
          style={{ borderWidth: 1, borderStyle: "solid", borderColor: "var(--border-field)", background: "var(--bg-0)", color: "var(--text)" }}
        />
        {searching && (
          <button
            type="button"
            onClick={() => setQuery("")}
            aria-label="Clear search"
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full p-1.5 transition-colors hover:bg-[var(--surface-strong)]"
            style={{ color: "var(--text-mute)" }}
          >
            <X size={16} />
          </button>
        )}
      </div>

      {searching ? (
        <p role="status" className="text-sm tabular-nums" style={{ color: "var(--text-dim)" }}>
          {visible.length === 0
            ? `No settings match "${query.trim()}".`
            : `${visible.length} ${visible.length === 1 ? "section" : "sections"} across every tab.`}
        </p>
      ) : (
        // A real tablist — the shared segmented control in its `tabs` mode,
        // which carries the roving tabindex and arrow keys the role promises.
        <SegmentedControl
          label="Settings sections"
          options={SETTINGS_TABS.map((t) => ({ value: t.id, label: t.label }))}
          value={tab}
          onChange={setTab}
          semantics={{ kind: "tabs", idPrefix: tabsId, panelId: `${tabsId}-panel` }}
          className="max-w-md"
        />
      )}

      {/* Only a tabpanel while the tablist above is the thing selecting it. A
          search spans both tabs, so labelling the results as one tab's panel
          would name them after a control that isn't on screen. */}
      <div
        className="space-y-4"
        {...(searching
          ? {}
          : { role: "tabpanel", id: `${tabsId}-panel`, "aria-labelledby": segmentedTabId(tabsId, tab) })}
      >
        {visible.map((section, index) => (
          <div key={section.id} className="space-y-4">
            {/* A heading only where the group changes, and never while
                searching — results span both tabs and every group, so a run of
                one-section headings would be noise rather than structure. */}
            {!searching && section.group && section.group !== visible[index - 1]?.group && (
              <p className={index === 0 ? "eyebrow" : "eyebrow pt-3"}>{section.group}</p>
            )}
            <Section
              title={section.title}
              icon={section.icon}
              storageKey={section.id}
              defaultOpen={INITIALLY_OPEN_SECTION_IDS.has(section.id)}
              alwaysOpen={searching}
              requested={section.id === requestedSection?.id}
              health={health[section.id]}
            >
              {section.content}
            </Section>
          </div>
        ))}
      </div>
    </PageFrame>
  );
}
