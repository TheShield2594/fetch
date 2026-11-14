import { useEffect, useState } from "react";
import { Link } from "react-router";
import "./LandingPage.css";

/**
 * What a signed-out visitor sees at `/`: what Fetch is, the downloads, and the
 * way in. Signed in, `/` is the Shelf instead (see App.tsx).
 *
 * The screenshots are the desktop app's real screens, captured under Xvfb with
 * a demo profile of public-domain films and Blender open movies, on placeholder
 * art (scripts/landing-screenshots/). The room around the TV and the hero loop
 * are generated. Everything lives in public/landing/, which the service worker
 * leaves out of its precache.
 */

declare const __APP_VERSION__: string;
const WEB_VERSION = typeof __APP_VERSION__ !== "undefined" ? __APP_VERSION__ : "";

const REPO = "https://github.com/TheShield2594/fetch";
const RELEASES = `${REPO}/releases/latest`;
const LATEST_API = "https://api.github.com/repos/TheShield2594/fetch/releases/latest";
const RELEASE_CACHE_KEY = "fetch_landing_release";
const RELEASE_CACHE_MS = 60 * 60 * 1000;

/**
 * People who chip in. Shown in the footer as "Thanks to …" once there is
 * anyone to thank; until then the footer thanks the bug reporters.
 */
const SUPPORTERS: string[] = [];

export interface LatestRelease {
  /** The release tag, such as "v1.9.1". */
  tag: string;
  /** The universal APK: installs on any Android TV, 32- or 64-bit. */
  apkUrl: string | null;
  apkSizeMb: number | null;
  pageUrl: string;
}

interface GitHubRelease {
  tag_name?: unknown;
  html_url?: unknown;
  assets?: { name?: unknown; browser_download_url?: unknown; size?: unknown }[];
}

/** Reads what the page needs out of GitHub's answer; anything unexpected is null. */
export function parseRelease(body: unknown): LatestRelease | null {
  const release = body as GitHubRelease | null;
  if (!release || typeof release.tag_name !== "string") return null;
  const apk = (release.assets ?? []).find(
    (a) => typeof a.name === "string" && a.name.endsWith("-universal.apk") && typeof a.browser_download_url === "string"
  );
  return {
    tag: release.tag_name,
    apkUrl: apk ? String(apk.browser_download_url) : null,
    apkSizeMb: apk && typeof apk.size === "number" ? Math.round(apk.size / 1_000_000) : null,
    pageUrl: typeof release.html_url === "string" ? release.html_url : RELEASES,
  };
}

/**
 * The latest GitHub release, so the download button is the APK itself and the
 * footer shows the version. GitHub allows 60 of these an hour per address, so
 * an answer is kept for the hour in this tab. Until it arrives (or if it never
 * does) the buttons go to the releases page, which always works.
 */
function useLatestRelease(): LatestRelease | null {
  const [release, setRelease] = useState<LatestRelease | null>(() => {
    try {
      const cached = JSON.parse(window.sessionStorage.getItem(RELEASE_CACHE_KEY) ?? "null") as
        | { at: number; release: LatestRelease }
        | null;
      if (cached && Date.now() - cached.at < RELEASE_CACHE_MS) return cached.release;
    } catch {
      // Private mode or a broken entry: ask again.
    }
    return null;
  });

  useEffect(() => {
    if (release) return;
    const controller = new AbortController();
    fetch(LATEST_API, { headers: { Accept: "application/vnd.github+json" }, signal: controller.signal })
      .then((r) => (r.ok ? r.json() : null))
      .then((body: unknown) => {
        const parsed = parseRelease(body);
        if (!parsed) return;
        setRelease(parsed);
        try {
          window.sessionStorage.setItem(RELEASE_CACHE_KEY, JSON.stringify({ at: Date.now(), release: parsed }));
        } catch {
          // Not worth failing over.
        }
      })
      .catch(() => {});
    return () => controller.abort();
  }, [release]);

  return release;
}

/**
 * Whether the hero should play its loop: not for someone who asked for less
 * motion, and not on a connection that asked to save data. They get the still.
 */
function usePlayHeroVideo(): boolean {
  const [play] = useState(() => {
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    const saveData = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData ?? false;
    return !reduce && !saveData;
  });
  return play;
}

function Wordmark({ className = "" }: { className?: string }) {
  return (
    <span className={`lp-wordmark ${className}`} aria-label="Fetch">
      FETCH
    </span>
  );
}

/** A real screen, in the TV on the wall. */
function TvShot({ name, alt, eager = false }: { name: string; alt: string; eager?: boolean }) {
  return (
    <img
      className="lp-tv"
      src={`/landing/tv-${name}.webp`}
      srcSet={`/landing/tv-${name}-800.webp 800w, /landing/tv-${name}.webp 1600w`}
      sizes="(min-width: 960px) 60vw, 100vw"
      width={1600}
      height={905}
      alt={alt}
      loading={eager ? "eager" : "lazy"}
      decoding="async"
    />
  );
}

const FAQ: { q: string; a: React.ReactNode }[] = [
  {
    q: "What does it cost?",
    a: "Nothing. It's open source (GPL-3.0), there's no account to make, and there are no ads. I don't collect anything.",
  },
  {
    q: "Where do the movies come from?",
    a: "Not from me. Fetch hosts nothing. It plays from the Stremio add-ons you install, your own Plex, Jellyfin or Emby server, and files on your network.",
  },
  {
    q: "Which TVs does it run on?",
    a: (
      <>
        Android TV and Google TV, Android 7 and up. Sideload the APK with Downloader or <code>adb install</code>. The
        download button gets the universal build; smaller ones for arm64 and armv7 are on{" "}
        <a href={RELEASES}>the releases page</a>.
      </>
    ),
  },
  {
    q: "And on a computer?",
    a: "There's a Linux build: .deb, .rpm, an Arch package, or a tarball for anything else. It plays in mpv. Windows and macOS aren't packaged yet.",
  },
  {
    q: "Do I need this website?",
    a: (
      <>
        No. The TV app works on its own. The website is for syncing profiles, lists and watch history between TVs, and
        you can <a href={`${REPO}/tree/main/web`}>run your own</a> with Docker Compose.
      </>
    ),
  },
];

const FEATURES: { title: string; body: string }[] = [
  {
    title: "Profiles, and a PIN on the kids one",
    body: "Everyone gets their own Continue Watching and lists. A kids profile gets a rating limit, screen time and a PIN so it stays a kids profile.",
  },
  {
    title: "It remembers where you stopped",
    body: "Continue Watching is the first row. Resume picks up on the second you left, on whichever TV you're on.",
  },
  {
    title: "The next episode just starts",
    body: "Autoplay lines up the next one before the credits. Intros and recaps get skipped using community markers.",
  },
  {
    title: "Trakt, Simkl and MDBList",
    body: "Sign in once with a code on the TV. What you watch gets scrobbled, and your watchlists show up in the Library.",
  },
  {
    title: "Plays from what you already have",
    body: "Plex, Jellyfin and Emby libraries sit next to add-on results and win ties. Local files and network shares work too.",
  },
];

export function LandingPage() {
  const release = useLatestRelease();
  const playVideo = usePlayHeroVideo();
  const apkHref = release?.apkUrl ?? RELEASES;
  const linuxHref = release?.pageUrl ?? RELEASES;

  useEffect(() => {
    const previous = document.title;
    document.title = "Fetch · Everything you watch, one place";
    return () => {
      document.title = previous;
    };
  }, []);

  return (
    <div className="lp">
      <a className="lp-skip" href="#lp-main">
        Skip to content
      </a>

      <header className="lp-hero">
        <div className="lp-hero-media" aria-hidden="true">
          {playVideo ? (
            <video autoPlay muted loop playsInline preload="auto" poster="/landing/hero-poster.webp">
              <source src="/landing/hero.webm" type="video/webm" />
              <source src="/landing/hero.mp4" type="video/mp4" />
            </video>
          ) : (
            <img src="/landing/hero-poster.webp" alt="" />
          )}
        </div>

        <nav className="lp-topbar" aria-label="Site">
          <Wordmark />
          <Link className="lp-topbar-signin" to="/login">
            Sign in
          </Link>
        </nav>

        <div className="lp-hero-copy">
          <h1>
            Everything you watch,
            <br />
            one place.
          </h1>
          <p className="lp-lede">A TV app I built for my friends, so nobody has to ask which app has what.</p>
          <div className="lp-actions">
            <a className="lp-btn lp-btn-primary" href={apkHref}>
              Download for Android TV
            </a>
            <a className="lp-btn lp-btn-secondary" href={linuxHref}>
              Linux build
            </a>
            <Link className="lp-btn lp-btn-ghost" to="/login">
              Sign in
            </Link>
          </div>
          <p className="lp-fineprint">
            {release ? (
              <>
                {release.tag}
                {release.apkSizeMb ? ` · universal APK, ${release.apkSizeMb} MB` : ""} · free, no account
              </>
            ) : (
              "Free, open source, no account."
            )}
          </p>
        </div>
      </header>

      <main id="lp-main">
        <section className="lp-how" aria-labelledby="lp-how-title">
          <div className="lp-how-copy">
            <p className="lp-kicker">How it works</p>
            <h2 id="lp-how-title">You press play. Fetch picks the link.</h2>
            <ol className="lp-steps">
              <li>
                <span className="lp-step-n" aria-hidden="true">
                  1
                </span>
                <p>Pick something and press play. Same as Netflix.</p>
              </li>
              <li>
                <span className="lp-step-n" aria-hidden="true">
                  2
                </span>
                <p>
                  Fetch asks your add-ons and your own server at once, reads every result for resolution, HDR, audio and
                  size, and throws out anything your TV can't actually play.
                </p>
              </li>
              <li>
                <span className="lp-step-n" aria-hidden="true">
                  3
                </span>
                <p>The best one starts. The rest are one press away if you care, and you mostly won't.</p>
              </li>
            </ol>
          </div>
          <figure className="lp-how-shot">
            <TvShot
              name="details"
              alt="A title page in Fetch. The Resume button already names the file it will play: 1080p, H.264, AAC, 1.4 GB, marked Best match."
            />
            <figcaption>The Resume button already says which file it's going to play.</figcaption>
          </figure>
        </section>

        <section className="lp-features" aria-labelledby="lp-features-title">
          <div className="lp-features-head">
            <h2 id="lp-features-title">The stuff you'd expect, done properly.</h2>
            <figure className="lp-features-shot">
              <TvShot name="profiles" alt="The Who's watching screen with three profiles. The kids profile has a lock." />
            </figure>
          </div>
          <dl className="lp-feature-list">
            {FEATURES.map((f) => (
              <div key={f.title} className="lp-feature">
                <dt>{f.title}</dt>
                <dd>{f.body}</dd>
              </div>
            ))}
          </dl>
        </section>

        <section className="lp-tracking" aria-labelledby="lp-tracking-title">
          <div className="lp-tracking-inner">
            <div className="lp-tracking-copy">
              <h2 id="lp-tracking-title">Your lists, from your phone.</h2>
              <p>
                This website is the other half. Add something to your watchlist on the bus, mark last night's episode
                watched, look back through your history. The TV has it by the time you sit down.
              </p>
              <p className="lp-tracking-note">
                It's optional, and it syncs with the TV app both ways. You can use this one or run your own.
              </p>
              <Link className="lp-textlink" to="/login">
                Sign in to your lists
              </Link>
            </div>
            <ul className="lp-poster-strip" aria-label="A watchlist, for example">
              {["nosferatu", "general", "hgf", "tos", "spring", "bbb"].map((id) => (
                <li key={id}>
                  <img src={`/landing/posters/${id}.webp`} alt="" width={240} height={360} loading="lazy" decoding="async" />
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="lp-shots" aria-labelledby="lp-shots-title">
          <h2 id="lp-shots-title" className="lp-shots-title">
            What it looks like
          </h2>
          <figure className="lp-shot lp-shot-a">
            <TvShot
              name="home"
              alt="Fetch's Home screen. Continue Watching is the first row, with Night of the Living Dead in the hero and 55 minutes left."
            />
            <figcaption>Home. Whatever you were halfway through comes first.</figcaption>
          </figure>
          <figure className="lp-shot lp-shot-b">
            <TvShot name="player" alt="Fetch's player, resumed at 41 minutes into a 96 minute film, with its controls showing." />
            <figcaption>The player picks up where you stopped.</figcaption>
          </figure>
          <p className="lp-shots-note">
            Real screens from the app. The films are public domain or Blender open movies; the poster art is a stand-in.
          </p>
        </section>

        <section className="lp-faq" aria-labelledby="lp-faq-title">
          <h2 id="lp-faq-title">Questions</h2>
          <div className="lp-faq-list">
            {FAQ.map((item) => (
              <details key={item.q}>
                <summary>{item.q}</summary>
                <div className="lp-faq-a">{item.a}</div>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="lp-footer">
        <Wordmark className="lp-wordmark-sm" />
        <div className="lp-footer-meta">
          <a href={REPO}>GitHub</a>
          <a href={release?.pageUrl ?? RELEASES}>{release ? `Fetch ${release.tag}` : "Releases"}</a>
          {WEB_VERSION && <span>Website {WEB_VERSION}</span>}
          <Link to="/login">Sign in</Link>
        </div>
        <p className="lp-footer-thanks">
          {SUPPORTERS.length > 0
            ? `Thanks to ${SUPPORTERS.join(", ")}.`
            : "Thanks to everyone who's filed a bug."}
        </p>
      </footer>
    </div>
  );
}
