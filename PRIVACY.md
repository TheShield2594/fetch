# Fetch privacy policy

Fetch is local-first. It has no telemetry, no analytics, no automatic crash reporting and
no required account. The only reports it can send are the opt-in diagnostics described
below.

## What stays on your TV

Your addons, profiles, watch progress, library, settings and Trakt/Simkl/MDBList/AniList keys are
stored on the device. Uninstalling Fetch deletes them.

## What Fetch talks to

Fetch connects to services you add or turn on yourself:

- **Stremio addons** you install. Each addon receives the IDs of the titles you browse or play.
- **Trakt, Simkl, MDBList** when you sign in to them.
- **AniList** (`graphql.anilist.co`), only when you sign in to it from the phone page. When you
  finish an anime episode, Fetch looks the title up on AniList and updates that entry's
  progress. To work out which AniList entry an IMDb-listed show's season is, Fetch asks Simkl's
  public ID lookup with the show's IMDb ID, the same lookup skip markers use. Importing your
  AniList history reads your anime lists.
- **Stremio** (`link.stremio.com` and `api.strem.io`), only when you import a Stremio account's
  history from Settings → Tracking. You approve a code on stremio.com, Fetch reads your
  library's watch state once and signs that session out again. It keeps no Stremio sign-in.
  Episode lists for your series come from your own addons.
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
- **Your Fetch website**, only if you pair with one. Fetch has no hosted backend; sync is
  off until you pair the TV with a website you run.
- **The pairing hub at `watchfetch.tv`**, only while the TV is showing a code to sign in to a
  Fetch website. It receives the device's name (on a TV its model, such as "NVIDIA SHIELD
  Android TV"; in the desktop app "Fetch Desktop" and the computer's network name, which can
  identify you or your organization) and hands out the code. When you confirm the code, your
  website tells the hub its address, and the hub passes that address to the TV. The hub never
  receives your sign-in: the TV collects that from your website directly. Codes are kept in
  memory only and deleted within about twelve minutes: ten for the code, up to a minute more
  for the TV to collect the address when it was confirmed at the last moment, and up to a
  minute until the next clean-up. Choosing "Enter the address instead" on the TV skips the hub.
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

Each of these services has its own privacy policy.

## Diagnostic reports

Fetch sends no diagnostic or crash reports, and has no setting that would. Sign-in reports
that older versions queued on the device are deleted when the app starts.

## Phone configuration

The "manage from your phone" feature runs a small web server on your TV that is only
reachable on your local network while the QR code screen is open.

## Contact

Open an issue at https://github.com/TheShield2594/fetch/issues.
