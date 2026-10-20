# Fetch privacy policy

Fetch is local-first. It has no telemetry, no analytics, no crash reporting and no
required account.

## What stays on your TV

Your addons, profiles, watch progress, library, settings and debrid/Trakt/Simkl keys are
stored on the device. Uninstalling Fetch deletes them.

## What Fetch talks to

Fetch only connects to services you add or turn on yourself:

- **Stremio addons** you install. Each addon receives the IDs of the titles you browse or play.
- **Debrid services** (Real-Debrid, Premiumize, TorBox) when you add an API key.
- **Trakt, Simkl, MDBList** when you sign in to them.
- **Jellyfin / Emby servers** you connect.
- **Metadata and artwork providers** your addons point to (for example TMDB image hosts),
  plus optional skip-intro marker services (IntroDB, AniSkip, Anime-Skip) when skip
  segments are enabled.
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
