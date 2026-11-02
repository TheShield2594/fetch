# Fetch for desktop (Linux first)

Fetch for desktop is a Compose Multiplatform (JVM) app in [`desktop/`](../desktop). It shares the
Stremio addon protocol, the domain models and stream parsing and ranking with the TV app through
[`shared/`](../shared). Linux is the first target. Windows and macOS use the same code once their
packaging and players are set up.

It is a preview. It does less than the TV app (see [What works](#what-works)).

## Install

Each GitHub release carries the Linux packages beside the APKs:

- `fetch-<tag>-linux-amd64.deb` for Debian, Ubuntu and Mint: `sudo apt install ./fetch-*.deb`
- `fetch-<tag>-linux-x86_64.rpm` for Fedora and openSUSE: `sudo dnf install ./fetch-*.rpm`
- `fetch-<tag>-linux-x64.tar.gz`, a portable build for any distribution: unpack it and run `fetch/bin/fetch`

Every CI run of the **Desktop** workflow also uploads the same three as a `fetch-linux-x64`
artifact.

The desktop app has the same version as the TV app, read from `versionName` in
`app/build.gradle.kts`. Packages need a plain `MAJOR.MINOR.PATCH`, so a beta such as
`1.3.0-beta.2` is packaged as `1.3.0` with package release `0.beta.2`. That sorts before the
stable `1.3.0-1`, so installing the stable build upgrades the beta.

Each package bundles its own Java runtime. To play streams, install
[mpv](https://mpv.io) (`sudo apt install mpv`, `sudo dnf install mpv`). Without mpv, Fetch opens
the link in your default video player. For YouTube trailers, mpv also needs `yt-dlp`.

## Build and run from source

You don't need the Android SDK for the desktop app:

```bash
./gradlew -Pfetch.desktopOnly=true :desktop:run              # run it
./gradlew -Pfetch.desktopOnly=true :shared:test :desktop:test # tests
./gradlew -Pfetch.desktopOnly=true :desktop:packageDeb        # or packageRpm, createDistributable
```

`-Pfetch.desktopOnly=true` leaves the Android modules out of the build. Without it, Gradle also
configures `:app` and needs the Android SDK, as before. A JDK 17 or newer is required, and
packaging needs `jpackage`, which ships with the JDK. Building the `.rpm` also needs `rpmbuild`.

Everything is kept in `$XDG_CONFIG_HOME/fetch` (normally `~/.config/fetch`):

| File | What it holds |
| --- | --- |
| `addons.json` | The installed addons, in order |
| `settings.json` | Settings: stream selection rules, theme, layout, home catalog pins |
| `library.json` | Titles saved with Add to library |
| `progress.json` | Where you stopped each title (Continue Watching) and what you finished |
| `tracking.json` | Trakt and Simkl sign-ins (owner-readable only) |
| `profiles.json` | The profiles, and which one opened last |
| `servers.json` | Jellyfin, Emby and Plex sign-ins (owner-readable only), beside the addons |
| `device-id` | The id media servers list this installation under |

These are the main profile's. Every other profile keeps its own copies in `profiles/<id>/`
(its `addons.json` too, unless it uses the main profile's addons).

## What works

- The TV app's navigation: a floating sidebar with Home, Search, Library and Settings (plus
  Discover when its location is "Show in Side Panel"). It opens to show labels on hover.
  Addons are in Settings › Content & Discovery, as on TV.
- Installing, ordering and removing addons by manifest link. A first run starts with Cinemeta
  and OpenSubtitles, like the TV app.
- A Home hero with the highlighted title's backdrop, logo, facts and Play. Hovering a poster
  puts it in the hero.
- **Continue Watching** first on Home: landscape cards with the time left and a progress bar,
  and the hero's "Resume S2 E5 · 38m left". Fetch reads mpv's position through its IPC socket
  while you watch, resumes from there, and follows the TV app's rules: started at 2%, finished
  at 90%, one card per show, and a finished episode turns into Next Up for the following one.
  Right-click a card (or the × on hover) for Go to details, Start from beginning and Remove.
- **Library**: titles saved with Add to library on the title page, with the TV app's Type,
  Sort, Genre, Year and Watched filters. It is local; Trakt and Simkl lists aren't on desktop.
- **Discover**: pick a type, catalog and genre and browse the catalog as a grid that loads more
  as you scroll. It sits in Search until you type, as on TV, or in the sidebar.
- **Profiles**: each with its own Library, Continue Watching, settings and Trakt/Simkl accounts,
  and optionally its own addons. "Who's watching?" at start-up when Remember Last Profile is
  off; the avatar at the bottom of the sidebar switches.
- **Trakt and Simkl** (Settings › Tracking): sign in with a code, as on TV. What you play in mpv
  is scrobbled (stopping at 80% or more marks it watched), and Fetch pulls their Continue
  Watching (resumed by percentage), watched history and watchlist (a Library source). Add to
  library also adds to the Trakt watchlist and Simkl Plan to Watch; removing only removes from
  Trakt, since Simkl's remove also clears that title's history there. Builds need the TV app's
  client IDs: `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` and `SIMKL_CLIENT_ID` from the environment
  (the release workflow passes the repository secrets) or the root `local.properties`.
- **Media servers** (Settings › Content & Discovery › Media servers): Jellyfin and Emby with
  address, user name and password; Plex with a code at plex.tv/link, adding every server on the
  account. Your copies of a title or episode (matched by IMDb id) are listed as sources and,
  with "Prefer my own library" (on by default, as on TV), ranked first; mpv plays them directly.
  Each picked library adds a "Recently added" row to Home, what you were watching on the server
  joins Continue Watching, and when mpv closes the server hears where you stopped (or that you
  finished). Transcoding, server-only titles without an IMDb id, and server subtitles and
  tracks aren't on desktop yet.
- **Player settings** passed to mpv: fullscreen, hardware decoding, extra mpv options; preferred
  audio language and passthrough; subtitle language, when to show them, size and background,
  and subtitles from your addons (such as OpenSubtitles) added as subtitle files.
- **Up Next**: when an episode plays to its end in mpv, the next episode's best match starts.
- **Settings**: Stream Selection (quality, codec, file size, audio languages, skipped release
  types, audio formats and release groups, result limits), Content & Discovery (Addons, and
  Home catalogs to pin or hide rows), Appearance (the TV app's color themes), Layout (Continue
  Watching, catalog type and addon name on rows, poster labels, Discover location) and About.
  Ctrl+, opens them.
- Home rows from every addon catalog that needs no search or genre input. Rows keep the
  catalog's poster shape, and hovering one shows arrows to page through it.
- Search across every addon catalog that takes a search (Ctrl+F or Ctrl+K from anywhere).
- Title pages like the TV detail page: backdrop, logo, facts, a Play (or Resume) button that
  names the best source's specs, Add to library, the best match card, the trailer, episodes by
  season and the cast, from the first addon that serves metadata.
- Sources from every stream addon at once, ranked best first by the TV app's `StreamRanker`
  with your Stream Selection rules,
  with its labels as chips (resolution, HDR, codec, audio, size, cached). Sources it rules out
  are listed last with the reason. All sources open in a side panel.
- Playback in mpv, in its own window, including addon `proxyHeaders`. Torrent-only results are
  not playable; Fetch is debrid-only, as on TV.
- A scrollbar on every long page and list, to grab and drag.
- Escape goes back (and closes the sources panel first).

## How the code is split

| Module | What it holds | Depends on Android? |
| --- | --- | --- |
| `shared` | Addon DTOs, mappers and `AddonApi`; domain models; `StreamTitleParser`, `StreamRanker`, badge rules; `AddonUrls` | No. Kotlin/JVM only. The Desktop workflow builds it without the Android SDK, so an Android import fails there. |
| `app` | The Android TV app. Depends on `shared` | Yes |
| `desktop` | The desktop app. Depends on `shared` | No |

The packages didn't change when files moved into `shared`, so imports in `app` stay the same.
Two Kotlin rules now apply across the module boundary:

- `internal` no longer reaches `app`. The moved declarations that `app` uses are public.
- Kotlin won't smart-cast a nullable property declared in another module. Read it into a local
  `val` first: `val ratings = item.mdbListRatings; if (ratings != null) …`.

## Next steps

Roughly in order:

1. **Embedded player.** Render libmpv inside the window (through JNA and an OpenGL surface)
   so the player has Fetch's own controls, subtitles menu and Up Next instead of mpv's window.
2. **Move more of the data layer into `shared`.** The addon, catalog, meta and stream repositories
   need their Android `Context` (strings, DataStore) and Hilt bindings put behind interfaces first.
   Then both apps get the same caching, addon health tracking and best-match logic.
3. **Media servers, the rest**: titles without an IMDb id (opened from the server's own
   metadata), transcoding for slow links, and the Library's Servers view.
4. **The sync server** (Settings › Account on TV), so desktop and TV share profiles, addons and
   Continue Watching without Trakt.
5. **More settings**: the stream badge editor, skip intro (needs the embedded player), PINs
   and limits on profiles.
6. **More packages**: Flatpak (Flathub) and AppImage, then Windows (`.msi`) and macOS (`.dmg`)
   from the same `nativeDistributions` block.
