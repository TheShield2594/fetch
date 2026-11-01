# Fetch privacy policy

Fetch is local-first. It has no telemetry, no analytics, no crash reporting and no
required account.

## What stays on your TV

Your addons, profiles, watch progress, library, settings and Trakt/Simkl/MDBList keys are
stored on the device. Uninstalling Fetch deletes them.

## What Fetch talks to

Fetch only connects to services you add or turn on yourself:

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

Each of these services has its own privacy policy.

## Phone configuration

The "manage from your phone" feature runs a small web server on your TV that is only
reachable on your local network while the QR code screen is open.

## Contact

Open an issue at https://github.com/TheShield2594/fetch/issues.
