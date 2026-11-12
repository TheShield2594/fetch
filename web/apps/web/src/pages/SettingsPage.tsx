import { type ReactNode, useContext, useEffect, useMemo, useRef, useState } from "react";
import { Link as RouterLink, Navigate, useSearchParams } from "react-router";
import { Link, Database, Info, Clapperboard, Film, Image, Globe, Star, Sparkles, Bell, Users, Activity, Search, X, ChevronLeft, ChevronRight, UserCircle, UserPlus, Webhook, ListChecks, Server, Palette } from "lucide-react";
import { healthDotClass, healthSummary, type SectionHealth } from "../components/settings/health";
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
import { AppearanceSettings } from "../components/settings/AppearanceSettings";
import { JobStatusSettings } from "../components/settings/JobStatusSettings";
import { PAGE_TITLE, SECTION_TITLE } from "../components/typography";
import { PageFrame } from "../components/PageFrame";
import { ProfileContext } from "../hooks/useProfile";

declare const __APP_VERSION__: string;
const APP_VERSION = typeof __APP_VERSION__ !== "undefined" ? __APP_VERSION__ : "unknown";

/**
 * Where a section sits. Grouped by what a reader is there to do rather than by
 * the old Preferences / Integrations & Data / TV tabs, which put an account's
 * password, the TV pairing list and the AI switch side by side under
 * "Preferences".
 */
export type SettingsGroup = "sources" | "metadata" | "profile" | "account" | "server" | "about";

export const SETTINGS_GROUPS: { id: SettingsGroup; label: string }[] = [
  { id: "sources", label: "Sources" },
  { id: "metadata", label: "Metadata" },
  // The last three are named by whose they are: the reader's profile, the
  // account its profiles share, and the server every account shares.
  { id: "profile", label: "This profile" },
  { id: "account", label: "Your account" },
  { id: "server", label: "This server (admin)" },
  { id: "about", label: "About" },
];

/**
 * Whose a setting is. Settings used to mix all four without saying so: Trakt is
 * signed in per profile, the region is the account's, the metadata language
 * the server's, and AI recommendations' switch this browser's.
 */
export type SettingsScope = "profile" | "account" | "server" | "browser";

/** The scope in words, as the section's header says it. */
export function scopeLabel(scope: SettingsScope, profileName?: string | undefined): string {
  switch (scope) {
    case "profile":
      return profileName ? `This profile (${profileName})` : "This profile";
    case "account":
      return "Your account: every profile";
    case "server":
      return "This server: every account";
    case "browser":
      return "This browser only";
  }
}

export type SettingsSection = {
  /** Also the section's address: `/settings?section=<id>`. */
  id: string;
  group: SettingsGroup;
  /** The service's name and nothing else, where the section is a service. */
  title: string;
  icon: ReactNode;
  /** Extra terms the section should be findable by; the title is always searched. */
  keywords: string;
  /** Whose it is; absent for About, which is nobody's. */
  scope?: SettingsScope;
  /** A line under the scope, where one section holds settings of more than one. */
  scopeNote?: string;
  content: ReactNode;
  /**
   * A tile on the status board: something history, artwork or ratings come
   * from, whose state is worth reading without opening it.
   */
  source?: true;
  /**
   * Install-wide settings every account shares, which only the admin may
   * change (the server refuses anyone else). Hidden from other accounts.
   */
  adminOnly?: true;
  /** Shown only with AI recommendations turned on (see hooks/useExtras.ts). */
  extra?: true;
};

/**
 * The sections that moved to the TV page, by the ids `?section=` used for
 * them, so old links and bookmarks land where the settings are now.
 */
export const MOVED_TO_TV = new Set(["devices", "tv-home", "home-folders", "tv-addons", "tv-settings", "tv-overview"]);

function AboutSection() {
  return (
    <div className="space-y-2 text-sm" style={{ color: "var(--text-dim)" }}>
      <p className="text-base font-semibold" style={{ color: "var(--text)" }}>Fetch <span className="font-mono text-accent-text">v{APP_VERSION}</span></p>
      <p className="text-sm">
        Fetch is a player for your TV and the website that runs it. This is where your account lives,
        where you manage the TV and its profiles, and where what you watch, your lists and your history
        are kept in step with the TV app and with Stremio.
      </p>
    </div>
  );
}

export const SETTINGS_SECTIONS: SettingsSection[] = [
  // Where history comes from. They lead because they are what the page is
  // usually opened to check: everything after is configuration, and these are
  // the ones that can quietly stop working.
  {
    id: "trakt",
    group: "sources",
    scope: "profile",
    source: true,
    title: "Trakt",
    icon: <Link size={20} />,
    keywords: "scrobble import watchlist history oauth sync",
    content: <TraktSettings />,
  },
  {
    id: "simkl",
    group: "sources",
    scope: "profile",
    source: true,
    title: "Simkl",
    icon: <ListChecks size={20} />,
    keywords: "simkl tracker scrobble watched history plan to watch watchlist anime sign in code",
    content: <SimklSettings />,
  },
  {
    id: "mdblist",
    group: "sources",
    scope: "profile",
    source: true,
    title: "MDBList",
    icon: <ListChecks size={20} />,
    keywords: "mdblist tracker scrobble watched history watchlist sign in code",
    content: <MdbListSettings />,
  },
  {
    id: "media-servers",
    group: "sources",
    scope: "profile",
    source: true,
    title: "Media servers",
    icon: <Server size={20} />,
    keywords: "plex jellyfin emby media server sign in link code watched progress resume library",
    content: <MediaServersSettings />,
  },
  {
    id: "media-server-webhooks",
    group: "sources",
    scope: "account",
    source: true,
    title: "Webhooks",
    icon: <Webhook size={20} />,
    keywords: "plex jellyfin emby webhook media server scrobble watched link key",
    content: <WebhookSettings />,
  },
  // What titles look like once they arrive.
  {
    id: "preferences",
    group: "metadata",
    scope: "account",
    scopeNote: "Region and spoiler protection are your account's. The metadata language is the server's, and only the admin changes it.",
    title: "Language & region",
    icon: <Globe size={20} />,
    keywords: "language locale translation streaming region country spoiler protection tmdb metadata",
    content: <PreferencesSettings />,
  },
  {
    id: "tmdb",
    adminOnly: true,
    group: "metadata",
    scope: "server",
    source: true,
    title: "TMDB",
    icon: <Film size={20} />,
    keywords: "the movie database api key posters metadata artwork cast",
    content: <TmdbSettings />,
  },
  {
    id: "omdb",
    adminOnly: true,
    group: "metadata",
    scope: "server",
    source: true,
    title: "OMDb",
    icon: <Star size={20} />,
    keywords: "omdb imdb rotten tomatoes metacritic scores ratings api key",
    content: <OmdbSettings />,
  },
  {
    id: "mdblist-ratings",
    group: "metadata",
    scope: "profile",
    source: true,
    title: "MDBList ratings",
    icon: <Star size={20} />,
    keywords: "mdblist ratings api key imdb trakt tmdb rotten tomatoes audience letterboxd metacritic myanimelist mal order detail",
    content: <MdbListRatingsSettings />,
  },
  {
    id: "rpdb",
    adminOnly: true,
    group: "metadata",
    scope: "server",
    source: true,
    title: "RPDB",
    icon: <Image size={20} />,
    keywords: "rating poster database posters artwork images api key",
    content: <RpdbSettings />,
  },
  // The reader's own profile, then the account its profiles share.
  {
    id: "profile",
    group: "account",
    scope: "account",
    title: "Profiles",
    icon: <Users size={20} />,
    keywords: "profiles users household switch rename pin lock delete avatar",
    content: <ProfileSettings />,
  },
  {
    id: "appearance",
    group: "profile",
    scope: "profile",
    title: "Appearance",
    icon: <Palette size={20} />,
    keywords: "theme themes dark light colour color glass midnight matinee install app pwa home screen",
    content: <AppearanceSettings />,
  },
  {
    id: "notifications",
    group: "profile",
    scope: "profile",
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
    group: "account",
    scope: "browser",
    title: "Extras",
    icon: <Sparkles size={20} />,
    keywords: "ai recommendations llm extras show",
    content: <ExtrasSettings />,
  },
  {
    id: "account",
    group: "account",
    scope: "account",
    title: "Account",
    icon: <UserCircle size={20} />,
    keywords:
      "sign in sign out log out password username login sessions devices browsers sign out everywhere activity log security delete account",
    content: <AccountSettings />,
  },
  // This server: what it offers and to whom, and how its own work is going.
  {
    id: "job-status",
    adminOnly: true,
    group: "server",
    scope: "server",
    title: "Sync status",
    icon: <Activity size={20} />,
    keywords: "background jobs scheduled sync failures errors trakt scrobble",
    content: <JobStatusSettings />,
  },
  {
    id: "addon",
    adminOnly: true,
    group: "server",
    scope: "server",
    title: "Stremio addon",
    icon: <Clapperboard size={20} />,
    keywords: "stremio catalogs manifest install url",
    content: <AddonSettings />,
  },
  {
    id: "invites",
    adminOnly: true,
    group: "server",
    scope: "server",
    title: "Invites & accounts",
    icon: <UserPlus size={20} />,
    keywords: "invite friend share sign up users accounts members remove reset password temporary admin transfer",
    content: <InvitesSettings />,
  },
  {
    id: "data",
    group: "profile",
    scope: "profile",
    scopeNote: "Export and import are this profile's. Refreshing every title's metadata is the server's, and only the admin can.",
    title: "Data",
    icon: <Database size={20} />,
    keywords: "export import backup restore refresh metadata wipe",
    content: <DataSettings />,
  },
  {
    id: "ai",
    extra: true,
    group: "account",
    scope: "account",
    title: "AI recommendations",
    icon: <Sparkles size={20} />,
    keywords: "llm openai ollama model provider suggestions",
    content: <AiSettings />,
  },
  {
    id: "about",
    group: "about",
    title: "About",
    icon: <Info size={20} />,
    keywords: "version build release fetch",
    content: <AboutSection />,
  },
];

/** Every whitespace-separated term has to appear in the title or keywords. */
export function matchesSearch(section: SettingsSection, query: string): boolean {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return true;
  const haystack = `${section.title} ${section.keywords}`.toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

/** The sections an account can see: admin-only and opt-in ones filtered, and any the server's environment already answers. */
export function visibleSections(
  health: Record<string, SectionHealth>,
  options: { isAdmin: boolean; showExtras: boolean }
): SettingsSection[] {
  return SETTINGS_SECTIONS.filter(
    (s) => (options.isAdmin || !s.adminOnly) && (options.showExtras || !s.extra) && !health[s.id]?.setByServer
  );
}

/**
 * The status board's two halves: sources that are set up, with their state,
 * and the ones that aren't, grouped below as "Available". A source whose status
 * could not be read has no entry and sits with the set-up ones without a dot —
 * "we could not ask" is not "not set up".
 */
export function boardTiles(sections: SettingsSection[], health: Record<string, SectionHealth>) {
  const sources = sections.filter((s) => s.source);
  return {
    live: sources.filter((s) => health[s.id] && health[s.id]!.tone !== "idle"),
    unknown: sources.filter((s) => !health[s.id]),
    available: sources.filter((s) => health[s.id]?.tone === "idle"),
  };
}

const sectionHref = (id: string) => `/settings?section=${encodeURIComponent(id)}`;

function HealthMark({ health, className = "" }: { health: SectionHealth; className?: string }) {
  return (
    <span className={`flex min-w-0 items-center gap-1.5 ${className}`}>
      <span aria-hidden="true" className={`${healthDotClass(health.tone)} flex-none`} />
      <span className="meta-row truncate" style={{ color: "var(--text-mute)" }}>{health.label}</span>
    </span>
  );
}

/** One source on the board: its name and state, and the way into its settings. */
function SourceTile({ section, health }: { section: SettingsSection; health: SectionHealth | undefined }) {
  return (
    <li>
      <RouterLink
        to={sectionHref(section.id)}
        className="glass-row flex h-full items-center gap-3 rounded-2xl border p-3.5 transition-[background-color,border-color,transform] duration-base hover:border-[var(--border-strong)] active:scale-[0.98] active:duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg" style={{ background: "var(--surface-strong)", color: "var(--text-mute)" }}>
          {section.icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold" style={{ color: "var(--text)" }}>{section.title}</span>
          {/* Words as well as a colour, on every width: the label is the
              answer, the dot is how you spot it in a grid (SC 1.4.1). */}
          {health ? <HealthMark health={health} className="mt-0.5" /> : (
            <span className="meta-row mt-0.5 block truncate" style={{ color: "var(--text-mute)" }}>Open</span>
          )}
        </span>
        <ChevronRight size={16} className="flex-none" style={{ color: "var(--text-mute)" }} />
      </RouterLink>
    </li>
  );
}

/**
 * The page's lead: every source's state, readable without opening anything.
 *
 * It was one line under the title and a dot on each collapsed row, with the
 * dot's words hidden below `sm` — which made the page's best idea its
 * smallest thing. Now it is the first thing on the page on a phone, and the
 * right-hand pane on a desktop until a section is picked.
 */
function StatusBoard({ sections, health, summary }: { sections: SettingsSection[]; health: Record<string, SectionHealth>; summary: string }) {
  const { live, unknown, available } = boardTiles(sections, health);
  const setUp = [...live, ...unknown];
  return (
    <section aria-labelledby="settings-board" className="space-y-5">
      <div>
        <h2 id="settings-board" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Status</h2>
        {/*
         * A polite live region because it arrives a moment after the page: the
         * status requests resolve together, and a screen reader that has
         * already moved on should hear the answer rather than have it appear
         * silently behind it.
         */}
        {summary && (
          <p role="status" className="meta-row mt-1" style={{ color: "var(--text-mute)" }}>{summary}</p>
        )}
      </div>
      {setUp.length > 0 && (
        <ul aria-label="Sources in use" className="grid gap-2.5 sm:grid-cols-2 xl:grid-cols-3">
          {setUp.map((section) => <SourceTile key={section.id} section={section} health={health[section.id]} />)}
        </ul>
      )}
      {available.length > 0 && (
        <div>
          <p className="eyebrow mb-2">Available</p>
          <ul aria-label="Available sources" className="grid gap-2.5 sm:grid-cols-2 xl:grid-cols-3">
            {available.map((section) => <SourceTile key={section.id} section={section} health={health[section.id]} />)}
          </ul>
        </div>
      )}
    </section>
  );
}

/** The section list, grouped. The rail on a desktop, the index under the board on a phone. */
function SectionNav({
  sections,
  health,
  current,
  searching,
}: {
  sections: SettingsSection[];
  health: Record<string, SectionHealth>;
  current: string | null;
  searching: boolean;
}) {
  // While searching the results span every group, so a run of one-row
  // headings would be noise rather than structure.
  const groups = searching
    ? [{ id: "results", label: "", items: sections }]
    : SETTINGS_GROUPS.map((g) => ({ ...g, items: sections.filter((s) => s.group === g.id) })).filter((g) => g.items.length > 0);
  return (
    <nav aria-label="Settings sections" className="space-y-4">
      {groups.map((group) => (
        <div key={group.id}>
          {group.label && <p className="eyebrow mb-1.5 px-3">{group.label}</p>}
          <ul className="space-y-0.5">
            {group.items.map((section) => {
              const active = section.id === current;
              const sectionHealth = health[section.id];
              return (
                <li key={section.id}>
                  <RouterLink
                    to={sectionHref(section.id)}
                    aria-current={active ? "page" : undefined}
                    className={`flex items-center gap-2.5 rounded-xl px-3 py-2 text-sm transition-colors duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-focus ${
                      active ? "bg-accent-500/10 font-medium text-accent-text" : "hover:bg-[var(--surface)]"
                    }`}
                    style={active ? undefined : { color: "var(--text-dim)" }}
                  >
                    <span className="flex h-5 w-5 flex-none items-center justify-center [&>svg]:h-4 [&>svg]:w-4" style={{ color: active ? undefined : "var(--text-mute)" }}>
                      {section.icon}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{section.title}</span>
                    {sectionHealth && (
                      <>
                        {/* The state in words for the accessible name; the dot is the glance. */}
                        <span className="sr-only">, {sectionHealth.label}</span>
                        <span aria-hidden="true" title={sectionHealth.label} className={`${healthDotClass(sectionHealth.tone)} flex-none`} />
                      </>
                    )}
                  </RouterLink>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

/**
 * Whose the open section is, under its heading: "This profile (Alex)", "Your
 * account: every profile", "This server: every account", "This browser only".
 */
function ScopeLine({ section }: { section: SettingsSection }) {
  // Read without `useProfile`, which throws outside a provider: the scope
  // still has words to say without a name.
  const profileName = useContext(ProfileContext)?.profile?.name;
  if (!section.scope) return null;
  return (
    <div className="w-full basis-full text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
      <p>
        <span className="font-semibold" style={{ color: "var(--text-dim)" }}>Applies to: </span>
        {scopeLabel(section.scope, profileName)}
        {section.adminOnly ? ". Only the admin can change it." : ""}
      </p>
      {section.scopeNote && <p className="mt-0.5">{section.scopeNote}</p>}
    </div>
  );
}

/** One section, open: its heading, its state and its form. */
function SectionPanel({ section, health, headingRef }: { section: SettingsSection; health: SectionHealth | undefined; headingRef: React.Ref<HTMLHeadingElement> }) {
  const headingId = `settings-section-${section.id}`;
  return (
    <section
      aria-labelledby={headingId}
      className="glass-panel content-in rounded-2xl border p-5 shadow-e1 sm:p-6"
      style={{ borderColor: "var(--border)", background: "var(--surface)" }}
    >
      <header className="mb-5 flex flex-wrap items-center gap-3">
        <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg" style={{ background: "var(--surface-strong)", color: "var(--text-mute)" }}>
          {section.icon}
        </span>
        <h2 id={headingId} ref={headingRef} tabIndex={-1} className={`min-w-0 flex-1 focus:outline-none ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>
          {section.title}
        </h2>
        {health && <HealthMark health={health} />}
        {section.scope && <ScopeLine section={section} />}
      </header>
      {section.content}
    </section>
  );
}

/**
 * The settings page: a status board of every source, and every section with
 * its own address.
 *
 * It was a narrow column of collapsed accordions across three tabs, opening on
 * a change-password form. Now the board leads, the sections are grouped by
 * what they are for, and from `lg` the page is two panes: the section list on
 * the left and the section on the right. `?section=<id>` names what is open —
 * the rail's source rows and Stremio's Configure button both link straight to
 * one.
 */
export function SettingsPage() {
  // `sections` is shared with the rail, which shows the same dots against its
  // Sources rows — see the provider note in useSettingsHealth. It is re-asked
  // here, because this page is the status board: a reader who navigated to it
  // deliberately is owed what is true now.
  const { sections: health, refresh: refreshHealth } = useSettingsHealth();
  useEffect(() => { refreshHealth(); }, [refreshHealth]);
  const [searchParams] = useSearchParams();
  const [query, setQuery] = useState("");
  const searching = query.trim().length > 0;

  const { isAdmin } = useAccount();
  const showExtras = useExtrasEnabled();
  const allowed = useMemo(() => visibleSections(health, { isAdmin, showExtras }), [health, isAdmin, showExtras]);
  const listed = searching ? allowed.filter((s) => matchesSearch(s, query)) : allowed;

  // A section the account can't see (an admin-only link, a key the server's
  // environment holds) falls back to the board rather than to an empty pane.
  const sectionId = searchParams.get("section");
  const current = allowed.find((s) => s.id === sectionId) ?? null;
  const movedToTv = sectionId !== null && MOVED_TO_TV.has(sectionId);

  // Focus follows a pick to the section's heading, so a keyboard or screen
  // reader user lands on what they chose rather than back at the top of the
  // list. Not on the first render: arriving by link is not a pick.
  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    if (current) headingRef.current?.focus({ preventScroll: false });
  }, [current]);

  // Empty until the status requests land, and empty again if every one of them
  // failed — in which case saying nothing is right, because the alternative is
  // reporting a health the page could not actually read.
  const summary = healthSummary(health);

  const search = (
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
  );

  const searchStatus = searching && (
    <p role="status" className="px-3 text-sm tabular-nums" style={{ color: "var(--text-dim)" }}>
      {listed.length === 0
        ? `No settings match "${query.trim()}".`
        : `${listed.length} ${listed.length === 1 ? "section" : "sections"} match.`}
    </p>
  );

  if (movedToTv) {
    return <Navigate to={sectionId === "devices" ? "/tv" : `/tv?section=${encodeURIComponent(sectionId)}`} replace />;
  }

  return (
    // The wide frame, not the reading one: from `lg` this is two panes, and the
    // section pane is what the reading width is for. Left-aligned like every
    // other page, so the title doesn't move between routes.
    <PageFrame className="max-w-6xl">
      <h1 className={`${PAGE_TITLE} ${current ? "sr-only lg:not-sr-only" : ""}`} style={{ color: "var(--text)" }}>Settings</h1>
      <div className="mt-4 lg:grid lg:grid-cols-[15rem_minmax(0,1fr)] lg:gap-8">
        {/* The list. From `lg` always beside the content; below it, only on
            the index — a phone shows one thing at a time. */}
        <aside className={`space-y-4 lg:sticky lg:top-[84px] lg:max-h-[calc(100dvh-104px)] lg:overflow-y-auto lg:pb-4 hidden lg:block`}>
          {search}
          {searchStatus}
          <SectionNav sections={listed} health={health} current={current?.id ?? null} searching={searching} />
        </aside>

        <div className="min-w-0 space-y-6">
          {current ? (
            <>
              <RouterLink
                to="/settings"
                className="inline-flex items-center gap-1 rounded-lg py-1 text-sm font-medium text-accent-text focus:outline-none focus-visible:ring-2 focus-visible:ring-focus lg:hidden"
              >
                <ChevronLeft size={16} /> Settings
              </RouterLink>
              <SectionPanel key={current.id} section={current} health={health[current.id]} headingRef={headingRef} />
            </>
          ) : (
            <>
              {/* The phone's index: search, then the board, then every section. */}
              <div className="space-y-4 lg:hidden">
                {search}
                {searchStatus}
              </div>
              {!searching && <StatusBoard sections={allowed} health={health} summary={summary} />}
              <div className="lg:hidden">
                <SectionNav sections={listed} health={health} current={null} searching={searching} />
              </div>
            </>
          )}
        </div>
      </div>
    </PageFrame>
  );
}
