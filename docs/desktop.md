# Fetch for desktop (Linux first)

Fetch for desktop is a Compose Multiplatform (JVM) app in [`desktop/`](../desktop). It shares the
Stremio addon protocol, the domain models and stream parsing and ranking with the TV app through
[`shared/`](../shared). Linux is the first target. Windows and macOS use the same code once their
packaging and players are set up.

It is a preview. It does less than the TV app (see [What works](#what-works)).

## Install

Every CI run of the **Desktop** workflow uploads a `fetch-linux-x64` artifact with:

- `fetch_<version>-1_amd64.deb` for Debian, Ubuntu and Mint: `sudo apt install ./fetch_*.deb`
- `fetch-<version>-1.x86_64.rpm` for Fedora and openSUSE: `sudo dnf install ./fetch-*.rpm`
- `fetch-linux-x64.tar.gz`, a portable build for any distribution: unpack it and run `fetch/bin/fetch`

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

Settings live in `$XDG_CONFIG_HOME/fetch` (normally `~/.config/fetch`). Today that is only
`addons.json`, the list of installed addons.

## What works

- Installing, ordering and removing addons by manifest link. A first run starts with Cinemeta
  and OpenSubtitles, like the TV app.
- Home rows from every addon catalog that needs no search or genre input.
- Title details with seasons and episodes, from the first addon that serves metadata.
- Sources from every stream addon at once, ranked best first by the TV app's `StreamRanker`
  with its spec line (resolution, HDR, codec, audio, size, cached). Sources it rules out are
  listed last with the reason.
- Playback in mpv, in its own window, including addon `proxyHeaders`. Torrent-only results are
  not playable; Fetch is debrid-only, as on TV.
- Escape goes back.

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
3. **Settings and stream rules** on desktop: ranking rules, preferred languages, the stream badge
   editor.
4. **Watch state**: Continue Watching, Trakt and Simkl sync, and the sync server.
5. **Search and Discover.**
6. **More packages**: Flatpak (Flathub) and AppImage, then Windows (`.msi`) and macOS (`.dmg`)
   from the same `nativeDistributions` block.
7. **Releases**: add the Linux packages to `release.yml` once the desktop app is ready to ship
   beside the APKs.
