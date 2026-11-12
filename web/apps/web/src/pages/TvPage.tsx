import { type ReactNode, useContext, useEffect, useRef } from "react";
import { Link, useSearchParams } from "react-router";
import { Activity, ChevronLeft, ChevronRight, FolderTree, LayoutList, Puzzle, SlidersHorizontal } from "lucide-react";
import { PageFrame, PageHeader } from "../components/PageFrame";
import { DevicesSettings } from "../components/settings/DevicesSettings";
import { HomeLayoutSettings } from "../components/tv/HomeLayoutSettings";
import { HomeFoldersSettings } from "../components/tv/HomeFoldersSettings";
import { TvAddonsSettings } from "../components/tv/TvAddonsSettings";
import { TvSettingsForm } from "../components/tv/TvSettingsForm";
import { TvOverview } from "../components/tv/TvOverview";
import { SECTION_TITLE } from "../components/typography";
import { ProfileContext } from "../hooks/useProfile";

export type TvSection = {
  /** Also the section's address: `/tv?section=<id>`, the ids Settings used. */
  id: string;
  title: string;
  /** One line on the tile: what the section changes. */
  blurb: string;
  icon: ReactNode;
  /** Whose it is: the open profile's, or every profile on the account. */
  scope: "profile" | "account";
  content: ReactNode;
};

/**
 * Everything about the TV, under one destination.
 *
 * It was the third group of Settings, between Metadata and This profile, and
 * pairing was a row inside it — so the device the website exists to manage was
 * four taps from anywhere, and a person who installed the server for their TV
 * was never offered it. Home rows, Home folders, addons and playback are what
 * this profile looks like on the TV; Synced data is every profile's.
 */
export const TV_SECTIONS: TvSection[] = [
  {
    id: "tv-home",
    title: "Home rows",
    blurb: "Which rows the TV's Home screen shows, and in what order.",
    icon: <LayoutList size={20} />,
    scope: "profile",
    content: <HomeLayoutSettings />,
  },
  {
    id: "home-folders",
    title: "Home folders",
    blurb: "Rows of your own, built from lists, catalogs and searches.",
    icon: <FolderTree size={20} />,
    scope: "profile",
    content: <HomeFoldersSettings />,
  },
  {
    id: "tv-addons",
    title: "Addons",
    blurb: "The addons the TV finds streams and catalogs with.",
    icon: <Puzzle size={20} />,
    scope: "profile",
    content: <TvAddonsSettings />,
  },
  {
    id: "tv-settings",
    title: "Playback",
    blurb: "Auto-play, the best match, audio and subtitles.",
    icon: <SlidersHorizontal size={20} />,
    scope: "profile",
    content: <TvSettingsForm />,
  },
  {
    id: "tv-overview",
    title: "Synced data",
    blurb: "What each profile has on the TV, and the server's health.",
    icon: <Activity size={20} />,
    scope: "account",
    content: <TvOverview />,
  },
];

function SectionTile({ section }: { section: TvSection }) {
  return (
    <li>
      <Link
        to={`/tv?section=${encodeURIComponent(section.id)}`}
        className="glass-row flex h-full items-center gap-3 rounded-2xl border p-3.5 transition-[background-color,border-color,transform] duration-base hover:border-[var(--border-strong)] active:scale-[0.98] active:duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-ring-offset"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg" style={{ background: "var(--surface-strong)", color: "var(--text-mute)" }}>
          {section.icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold" style={{ color: "var(--text)" }}>{section.title}</span>
          <span className="mt-0.5 block text-xs leading-snug" style={{ color: "var(--text-mute)" }}>{section.blurb}</span>
        </span>
        <ChevronRight size={16} className="flex-none" style={{ color: "var(--text-mute)" }} />
      </Link>
    </li>
  );
}

/**
 * `/tv`: the TVs signed in to this account, with the pairing box in the empty
 * state, then what the open profile looks like on them. `?section=<id>` opens
 * one section, as Settings does.
 */
export function TvPage() {
  const [searchParams] = useSearchParams();
  const sectionId = searchParams.get("section");
  const current = TV_SECTIONS.find((s) => s.id === sectionId) ?? null;
  const profileName = useContext(ProfileContext)?.profile?.name;
  const profileSections = TV_SECTIONS.filter((s) => s.scope === "profile");
  const accountSections = TV_SECTIONS.filter((s) => s.scope === "account");

  // A pick moves focus to the section's heading, as in Settings; arriving by
  // link is not a pick.
  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    if (current) headingRef.current?.focus();
  }, [current]);

  if (current) {
    const headingId = `tv-section-${current.id}`;
    return (
      <PageFrame width="reading" className="space-y-4">
        <Link
          to="/tv"
          className="inline-flex items-center gap-1 rounded-lg py-1 text-sm font-medium text-accent-text focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
        >
          <ChevronLeft size={16} /> TV
        </Link>
        <section
          aria-labelledby={headingId}
          className="glass-panel content-in rounded-2xl border p-5 shadow-e1 sm:p-6"
          style={{ borderColor: "var(--border)", background: "var(--surface)" }}
        >
          <header className="mb-5 flex flex-wrap items-center gap-3">
            <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg" style={{ background: "var(--surface-strong)", color: "var(--text-mute)" }}>
              {current.icon}
            </span>
            <h1 id={headingId} ref={headingRef} tabIndex={-1} className={`min-w-0 flex-1 focus:outline-none ${SECTION_TITLE}`} style={{ color: "var(--text)" }}>
              {current.title}
            </h1>
            <p className="w-full basis-full text-xs leading-relaxed" style={{ color: "var(--text-mute)" }}>
              <span className="font-semibold" style={{ color: "var(--text-dim)" }}>Applies to: </span>
              {current.scope === "profile"
                ? profileName
                  ? `This profile (${profileName})`
                  : "This profile"
                : "Your account: every profile"}
            </p>
          </header>
          {current.content}
        </section>
      </PageFrame>
    );
  }

  return (
    <PageFrame width="reading" className="space-y-8">
      <PageHeader
        title="TV"
        className=""
        summary={<p>The Fetch app on your TV: which ones are signed in, and what each profile sees there.</p>}
      />

      <section aria-labelledby="tv-devices" className="space-y-3">
        <h2 id="tv-devices" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Your TVs</h2>
        <DevicesSettings />
      </section>

      <section aria-labelledby="tv-profile" className="space-y-3">
        <div>
          <h2 id="tv-profile" className={SECTION_TITLE} style={{ color: "var(--text)" }}>
            {profileName ? `${profileName} on the TV` : "This profile on the TV"}
          </h2>
          <p className="mt-1 text-sm" style={{ color: "var(--text-mute)" }}>
            Each profile has its own Home screen, addons and playback settings.
          </p>
        </div>
        <ul className="grid gap-2.5 sm:grid-cols-2">
          {profileSections.map((section) => <SectionTile key={section.id} section={section} />)}
        </ul>
      </section>

      <section aria-labelledby="tv-account" className="space-y-3">
        <h2 id="tv-account" className={SECTION_TITLE} style={{ color: "var(--text)" }}>Every profile</h2>
        <ul className="grid gap-2.5 sm:grid-cols-2">
          {accountSections.map((section) => <SectionTile key={section.id} section={section} />)}
        </ul>
      </section>
    </PageFrame>
  );
}
