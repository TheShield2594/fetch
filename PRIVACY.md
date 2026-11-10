# Fetch privacy policy

Fetch is local-first. It has no telemetry, no analytics, no automatic crash reporting and
no required account. The only reports it can send are the opt-in diagnostics described
below.

## What stays on your TV

Your addons, profiles, watch progress, library, settings and Trakt/Simkl/MDBList keys are
stored on the device. Uninstalling Fetch deletes them.

## What Fetch talks to

Fetch connects to services you add or turn on yourself:

- **Stremio addons** you install. Each addon receives the IDs of the titles you browse or play.
- **Trakt, Simkl, MDBList** when you sign in to them.
- **Jellyfin / Emby / Plex servers** you connect. Signing in to Plex also talks to plex.tv,
  which approves the sign-in code and lists the servers your account can reach.
- **Metadata and artwork providers** your addons point to (for example TMDB image hosts),
  plus optional skip-intro marker services (IntroDB, AniSkip, Anime-Skip) when skip
  segments are enabled.
- **YouTube**, only if you sign in to it in Settings (`full` builds). Fetch then reads your
  feeds, plays videos, and, unless you turn it off, adds what you watch to your YouTube
  history. Alongside it:
  - **SponsorBlock** (on by default; can be turned off) is asked for a video's sponsor segments
    by the first few characters of a hash of the video's ID, not by the ID itself.
  - **DeArrow** (off by default) is asked for community titles and thumbnails the same way.
  - **Return YouTube Dislike** (off by default) receives the ID of each video you play.
- **YouTube's search suggestions**: typing on the YouTube section's Search tab sends what you
  type to YouTube's suggestion service, signed in or not.
- **A sync server**, only if you connect one. Fetch has no hosted backend; sync is off
  until you point it at a server you run.
- **GitHub Releases** for the in-app update check (`full` builds only). It is an
  anonymous request to `api.github.com` and carries no personal data.

A few features use a service Fetch picks for you. Each is listed here with what it receives:

- **TMDB** (`api.themoviedb.org` and its image hosts), when TMDB metadata is on. It receives
  the IDs of the titles you browse, with the app's own API key.
- **Content warnings** (`api.tiffara.com`), **off by default**. When you turn on Content
  Warnings in Settings, the IMDb ID of each title you play is sent to look up its parents
  guide.
- **Episode ratings** for the ratings grid on series pages and episode ratings on Continue
  Watching. Official builds ask an IMDb ratings service and a SeriesGraph-compatible service
  for a series' per-episode ratings, sending the series' IMDb ID or TMDB ID. This is on
  whenever those services are built in; builds made without them (the default for
  self-built APKs) make no such requests.
- **Simkl artwork** is loaded through the `wsrv.nl` image proxy when you are signed in to
  Simkl. The proxy sees the address of each Simkl image.
- **The network speed test** in Settings contacts `fast.com` (Netflix) and
  `cloudflare.com`, only when you start a test.

Each of these services has its own privacy policy.

## Diagnostic reports

Official builds send no reports. A build only has reporting if whoever built it set
`PLAYBACK_REPORTS_BASE_URL` to an endpoint they run, and even then nothing is sent until you
turn on **Playback issue reports** in Settings → Advanced. With it on:

- **Playback issue reports** are sent only when you press a report button in the player.
  They carry the title being played, the stream and player state, and device details.
- **Sign-in diagnostics** are sent when signing in to, signing up with, or refreshing a session
  on the build's default sync server. They carry device make, model and Android version, the
  server and sign-in URLs, and a timeline of the sign-in requests and errors. Passwords, tokens,
  email addresses and other credentials are removed first. Nothing is sent for a sync server
  you set up yourself.

With it off, sign-in diagnostics are discarded. Either way none are stored on the device: a
report that can't be sent straight away is dropped, not kept to retry.

## Phone configuration

The "manage from your phone" feature runs a small web server on your TV that is only
reachable on your local network while the QR code screen is open.

## Contact

Open an issue at https://github.com/TheShield2594/fetch/issues.
