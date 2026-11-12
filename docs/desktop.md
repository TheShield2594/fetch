# Fetch for desktop (Linux first)

Fetch for desktop is a personal tool for the owner and friends, not a product that tracks the TV
app feature for feature. It is a Compose Multiplatform (JVM) app in [`desktop/`](../desktop) that
shares the Stremio addon protocol, the domain models and stream parsing and ranking with the TV
app through [`shared/`](../shared), plays in mpv's own window by default, and syncs with the Fetch
website. Desktop work never gates a TV release.

Linux is the first target. Windows and macOS use the same code once their packaging and players
are set up. There is no parity list to keep up: a feature lands on desktop when someone wants it
there. Desktop clients that duplicate the TV's (media servers, Trakt and Simkl, the website) move
into `shared/` when they are next touched, and new work doesn't add more duplication.

## Install

Each GitHub release carries the Linux packages beside the APKs:

- `fetch-<tag>-linux-amd64.deb` for Debian, Ubuntu and Mint: `sudo apt install ./fetch-*.deb`
- `fetch-<tag>-linux-x86_64.rpm` for Fedora and openSUSE: `sudo dnf install ./fetch-*.rpm`
- `fetch-<tag>-linux-x86_64.pkg.tar.zst` for Arch Linux, EndeavourOS and Manjaro: `sudo pacman -U ./fetch-*.pkg.tar.zst`
- `fetch-<tag>-linux-x64.tar.gz`, a portable build for any distribution: unpack it and run `fetch/bin/fetch`

Every CI run of the **Desktop** workflow also uploads the same four as a `fetch-linux-x64`
artifact.

The desktop app has the same version as the TV app, read from `versionName` in
`app/build.gradle.kts`. Packages need a plain `MAJOR.MINOR.PATCH`, so a beta such as
`1.3.0-beta.2` is packaged as `1.3.0` with package release `0.beta.2`. That sorts before the
stable `1.3.0-1`, so installing the stable build upgrades the beta. The Arch package drops the
dash instead (`1.3.0beta.2-1`), which pacman also sorts before `1.3.0-1`.

Each package bundles its own Java runtime. To play streams, install
[mpv](https://mpv.io) (`sudo apt install mpv`, `sudo dnf install mpv`, `sudo pacman -S mpv`): a new
install plays in mpv's own window. The built-in player is opt-in
(Settings › Player › Default Player › Built-in, a choice kept across updates) and also needs libmpv: `sudo apt install libmpv2`
(Fedora: `mpv-libs`; on Arch the `mpv` package already has it). Without mpv, Fetch opens
the link in your default video player. For YouTube (trailers and the YouTube page), mpv also needs
`yt-dlp` (`sudo apt install yt-dlp`; distribution packages can lag behind YouTube, so `pipx install yt-dlp`
is the safer choice).

## Build and run from source

You don't need the Android SDK for the desktop app:

```bash
./gradlew -Pfetch.desktopOnly=true :desktop:run              # run it
./gradlew -Pfetch.desktopOnly=true :shared:test :desktop:test # tests
./gradlew -Pfetch.desktopOnly=true :desktop:packageDeb        # or packageRpm, createDistributable
```

The Arch package is made from the `createDistributable` app image rather than by Gradle:
tar up `desktop/build/compose/binaries/main/app/fetch` and run
`scripts/build-arch-package.sh <that.tar.gz> <out-dir>`. It runs makepkg in the
`archlinux:base-devel` Docker image; on Arch itself, `FETCH_ARCH_NATIVE=1` runs your own
makepkg instead. The PKGBUILD is in [`desktop/packaging/arch/`](../desktop/packaging/arch).

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
| `certifications.json` | Ratings looked up on TMDB, for 30 days |
| `mdblist-ratings.json` | Ratings from MDBList, for 12 hours, for the whole app |
| `mdblist.json` | Per profile: the MDBList account (owner-readable only) and the lists hidden from the Library |
| `mdblist-sync.json` | Per profile: what the last MDBList sync brought back, so the next one fetches only what changed |
| `screen-time.json`, `stream-badges.json` | Per profile: today's screen time, imported badge URLs |
| `stream-cache.json` | Per profile: the last link per movie or episode (Reuse Last Link) and each show's binge group |
| `youtube-auth.json` | Per profile: the YouTube sign-in (owner-readable only) |
| `youtube.json` | Per profile: YouTube searches and where each video stopped |
| `parental-titles.json` | Titles hidden from or always allowed on each profile |
| `website.json` | The Fetch website sign-in (owner-readable only), for the whole app |
| `addon-health.json` | Each addon's recent response times and failures (Addons › Addon health) |

These are the main profile's. Every other profile keeps its own copies in `profiles/<id>/`
(its `addons.json` too, unless it uses the main profile's addons).

## What works

- The TV app's navigation: a floating sidebar with Home, Search, Library and Settings (plus
  Discover when its location is "Show in Side Panel", and YouTube while signed in). It opens to show labels on hover.
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
  Finishing a show's last listed episode makes it caught up: Fetch re-reads its episode list at
  start-up and every six hours, and when a new episode appears the show comes back as Next Up,
  marked "Airs Fri, Nov 7" until it airs and "New episode" after. Layout › Continue Watching
  has the TV's Show Unaired Next Up Episodes, Up Next From Furthest Episode (off for rewatches)
  and Sort Order (Default, Streaming Style, or a separate Upcoming row).
- **Library**: titles saved with Add to library on the title page, with the TV app's Type,
  Sort, Genre, Year and Watched filters. Its sources are Local (the library and lists of your
  own, kept in `lists.json`), Trakt (watchlist and personal lists), Simkl (Watching, Plan to
  Watch, On Hold, Completed, Dropped) and MDBList (Watchlist, static, dynamic, external and
  liked lists), each list a tab. **Manage lists** (a poster's right-click menu, or Lists on a
  title page) puts a title on any of them (MDBList's Watchlist and static lists; the others are
  read-only there too); the Library's Manage lists creates, renames, reorders and deletes your
  own and Trakt's lists, and creates, renames (private or public) and deletes MDBList's static
  lists.
- **Filter rows** (Settings › Content & Discovery › Filter rows), kept per profile in
  `filter-rows.json` as the TV keeps them per TV: a Home row from a Jellyfin, Emby or Plex
  library narrowed by genre, years, watched and minimum rating, in the order you pick (the
  server does the filtering), or from an addon catalog that takes a genre.
- **See all** on a Home row opens its whole catalog as a grid that loads more as you scroll.
- **Collections** (Settings › Content & Discovery › Collections), as on TV: Home rows of
  folder tiles (poster, landscape or square; a cover image or emoji; the title shown or hidden),
  pinned ones before the other rows and the rest after the catalog rows. A folder opens a page
  of its sources in the collection's view mode: a tabbed grid (with an "All" tab that takes
  turns between them), rows, or rows under a Home-style hero. Sources are addon catalogs (with a
  genre; read from the addon's address when it isn't installed), TMDB (a list, a movie
  collection, a studio, a network, a person's or a director's credits, or discover with
  genres, dates, rating, votes, language, country, keywords and year; needs a TMDB key), Trakt
  lists (yours, a public one by address, or searched) and your local lists, each loading more
  as you scroll. Collections are kept per profile in `collections.json` in the TV's format,
  import and export as the TV's JSON, and sync with the Fetch website while paired (Settings ›
  Account): the website's copy wins, as on TV, and ones made here go up when it has none. The
  folder editor adds the profile's website lists as sources.
- **Discover**: pick a type, catalog and genre and browse the catalog as a grid that loads more
  as you scroll. It sits in Search until you type, as on TV, or in the sidebar.
- **Profiles**: each with its own Library, Continue Watching, settings and Trakt/Simkl accounts,
  and optionally its own addons. "Who's watching?" at start-up when Remember Last Profile is
  off; the avatar at the bottom of the sidebar switches. As on TV, a new profile can be a kids
  profile (PG or lower, unrated hidden) and start with another profile's settings (once, with
  its API keys where it has none; Copy settings does the same later, and while paired also
  copies it on the Fetch website), the editor picks an avatar from the TV's 40 bundled ones
  (synced as the account's `avatar_id`; Remove avatar clears the account's too, while a profile
  that never picked one here keeps the TV's), and a Preferences tab (audio and subtitle
  language, autoplay next episode), and Activity lists what a profile watched and when.
- **Hide or allow single titles**: a poster's right-click menu has the TV's parental entry: on
  another profile "Hide from <profile>" (behind the main profile's PIN), on the main profile a
  dialog that sets the title for each profile (rating limit decides, hidden, always allowed).
  Hidden titles leave rows, search, the Library, collections, Continue Watching and autoplay;
  Limits lists them to clear. Kept on this computer, as the TV keeps them.
- **Tracking options** (Settings › Tracking, synced with the TV's `trakt_settings`): where the
  Library opens, whose resume points join Continue Watching (Trakt, Simkl, MDBList or the Fetch
  website), how many days Continue Watching goes back, More like this from Trakt, TMDB or Simkl,
  Trakt comments on title pages, and which id a Simkl anime opens with (IMDb, MyAnimeList, Kitsu
  or TVDB).
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
- **Titles only on your server**, without an IMDb id, open from the server's own details and
  episodes. The Library's Source picker lists each server library, browsable by recently added,
  title or year. Server subtitle files are passed to mpv, and **Media server quality** caps the
  bitrate by having the server transcode (Jellyfin/Emby HLS, Plex universal transcode).
- **Profile PINs and limits**: PINs in the TV app's format with its lockout after wrong tries; a
  locked profile always asks at start-up. From the main profile: a maturity rating limit (titles
  rated above it are hidden and can't be played; catalogs that give no rating count as unrated),
  daily screen time, bedtime and how many episodes Up Next plays in a row. "That's all for
  today" offers more time with the main profile's PIN. As on TV, giving a profile limits
  suggests a PIN on the main profile when it has none, and with one offers to lock the profiles
  that have neither limits nor a PIN (Settings › Profiles › Locks): they then ask for the main
  profile's PIN to open, so the limits can't be skipped by switching.
- **Skip Segments**: intros, recaps and credits from introdb.app, and for anime also AniSkip and
  Anime Skip (with your own client id), merged best-of as on TV; movie end credits that never
  skip a post-credits scene. mpv jumps over them through its IPC socket and says so on screen;
  the built-in player's Skip button uses the TV's wording (Skip Intro, Skip Ending, Skip to
  Post-Credits…) and timing.
- **Stream badges**: Fusion badge URLs (Settings › Stream Selection › Stream badges) put the TV
  app's badges on each source.
- **Built-in player (beta)**: libmpv inside Fetch's window, with Fetch's own controls over the
  picture: seek bar, ±10 s, audio and subtitle tracks, volume, fullscreen, a Skip button for
  intros and credits, Up Next in the same player, and keys (Space, ←/→, ↑/↓, M, F, Esc). It draws
  with the graphics card: mpv's OpenGL renderer (scaling, HDR tone mapping, VA-API decoding on
  X11) runs in an offscreen EGL context at the window's size, and the finished frame is read into
  the bitmap Compose draws. Where there's no EGL/OpenGL, or only a CPU one (Mesa's llvmpipe,
  which with mpv's lanczos scaler sometimes draws only black), it uses mpv's software renderer;
  Settings › Player › Draw with the graphics card switches to it too. mpv's own window stays
  the default. `FETCH_MPV_DEBUG=1` prints libmpv's log for reporting playback problems.
- **TMDB** (Settings › TMDB, off by default as on TV, and synced with it): title pages filled
  in from TMDB (artwork, title and description in your language, runtime and status, cast and
  crew with photos, episode names, stills and ratings, trailers), each part switchable; TMDB's
  rating and the director or creator in the hero; **More like this**, the title's
  **collection**, and its studios and networks as logos. Click a person for their page
  (biography and filmography) or a logo for a studio's or network's titles. Home gets
  **Because you watched** rows for the last two titles you finished. Titles TMDB knows only by
  its own id open with their IMDb id when TMDB has one, otherwise from TMDB's details.
- **MDBList** (Settings › MDBList, off by default as on TV, and synced with it except the API
  key): ratings from IMDb, Trakt, TMDB, Rotten Tomatoes (fresh, rotten, Certified Fresh), the
  audience score (Verified Hot), Letterboxd, Metacritic and MyAnimeList as badges on the title
  page, in the order you set and with the ones you turn off left out; with "Use ratings on Home's
  hero" the first three on Home's hero too. They take the place of the addon's IMDb badge.
  Lookups go by IMDb id, a whole Home row in one request, and are cached for 12 hours, as on TV.
  Ratings use your API key, or the connected MDBList account. The account, connected with a code
  (builds need `MDBLIST_CLIENT_ID`, as for the TV) or an API key, adds its lists to the Library
  (see Library above); Settings › MDBList › Library lists picks which of them show. The
  account is a tracking service too: what you play is scrobbled, Mark watched goes to its
  history, and its resume points and watched history come back with each tracking sync. As on
  TV the sync is incremental (MDBList's last activities, then the journal of history changes
  since the last sync), shows and seasons dropped on MDBList stay out of Continue Watching, and
  titles without an IMDb id are matched by their TMDB, TVDB, Trakt or MDBList id. Its
  lists sort by provider order, added and release (MDBList's own orders). The API key stays on
  this computer.
- **Ratings from TMDB**: a profile's rating limit uses the addon's certificate, else TMDB's
  (picked by country as the TV app does), cached for 30 days. Builds read `TMDB_API_KEY`; you
  can enter your own key in Settings › TMDB.
- **Website sync, the rest**: shared settings (theme with its contrast, AMOLED, custom colours and
  font, Essential or Advanced, layout, languages, stream rules, Up Next, auto-skip, the TV's
  auto-play settings and the playback queue; only what changed is pushed, so the TV's other
  settings stay), addons, Home rows, profiles (at most 6, as on TV), limits and PINs (a PIN set
  on the TV is checked with the website, then kept here).
- **YouTube** (Settings › YouTube): sign in as YouTube's TV app does, with a code at
  youtube.com/activate, and pick which of the account's channels to act as. While signed in (and
  not on a profile with limits) YouTube is in the sidebar, as on TV, with Home, Subscriptions,
  History, Library (Watch later, Liked videos, your playlists) and Search (YouTube's suggestions
  and your recent searches); channels, playlists and "View all" open as pages. Right-click a
  video for the TV's menu: Go to channel, Save to Watch later, Save to playlist (or a new one),
  Remove from watch history, Not interested. Videos play in mpv (the built-in player or its
  window) through yt-dlp, capped at the quality you pick, resuming where you stopped, added to
  your YouTube history, skipping SponsorBlock segments by category (Skip jumps, and the
  built-in player then offers "Skipped sponsor · Watch it" for a few seconds; Ask shows the
  built-in player's Skip button; Show plays through), and, when a video ends, the TV's end card:
  YouTube's Up Next pick (a playlist's next video) counting down ten seconds with Play now,
  Cancel and Close, and the related videos (over the built-in player, or over the app after
  mpv's window closes; with autoplay off it waits for a pick). In the built-in player a YouTube
  video also has, as on TV: SponsorBlock segments marked on the seek bar in their categories'
  colours, chapter breaks, and under the pointer the storyboard frame for that point with its
  time and chapter; a like (with the count), dislike (with Return YouTube Dislike's estimate),
  Subscribe, Save (the account's playlists, or a new one) and channel row at the top right;
  Chapters (mpv's chapter list from yt-dlp, else the watch page's) and Captions (YouTube's own
  tracks, labelled auto-generated or auto-translated, with translations into your subtitle
  languages). Fetch draws captions itself, as the TV does, in your subtitle style from the
  player settings (size, colour, outline, background, position), and the last choice (on or off, and
  its language) carries on to the next video, in either player. DeArrow titles and thumbnails,
  Return YouTube Dislike counts (in the built-in player, and on mpv's window's OSD as a video
  starts), the Shorts, live and watched filters and a "YouTube subscriptions" row on Home are
  optional, as on TV.
  **Signed-in playback**: Settings › YouTube › Play signed in has yt-dlp borrow a browser's
  YouTube sign-in (`--cookies-from-browser`), which plays members-only, age-restricted and
  private videos. Fetch's own sign-in can't do it: the TV app plays signed out too (its
  `data/trailer/` extractor asks the VISIONOS, ANDROID and IOS player clients with no account and
  only uses the signed-in TV player request for history, captions and storyboards). The signed-in
  TV (TVHTML5) player response that does unlock those videos gives cipher-protected stream URLs
  (`signatureCipher` and the `n` throttling parameter), which only YouTube's player JavaScript
  can decode; the JVM has no JavaScript engine and adding one is a new dependency, and yt-dlp
  dropped OAuth sign-in in 2024 (Google blocks the TV client's tokens there), so the device-code
  token can't be handed to it either. Off by default: videos play signed out, as on TV.
- **Player settings** passed to mpv: fullscreen, hardware decoding, extra mpv options; preferred
  audio language and passthrough; subtitle language, when to show them, size and background,
  and subtitles from your addons (such as OpenSubtitles) added as subtitle files.
- **Up Next**: the next episode's best match starts at the TV's next-episode threshold
  (Settings › Up Next: a percentage or minutes before the end, or the credits when IntroDB
  knows them). In the built-in player the card says "Finding a source…", counts down three
  seconds and switches, and Cancel stops it. In mpv's own window (followed over its IPC socket)
  the OSD counts down five seconds of playback, then the window closes and the next episode
  opens; Alt+N keeps watching to the end (mpv 0.37 or later for the key). The next
  episode tries the same binge group (addon and release) first, then the same release by file
  name; with Fallback off, its sources open instead. Preload Next Episode Sources asks the
  addons ahead of the card, and Reuse Binge Group remembers a show's group for later sessions.
- **Fetching panel**: sources appear and are ranked again as each addon answers, with a chip
  per addon (answered, failed, timed out) on the title page. Pressing Play before they're all
  in shows the TV's panel: the best source so far, Play now, See all sources, and "Starting in
  N seconds" for the Stream selection timeout (Instant, 1–30 s or Unlimited, as on TV). Start
  without waiting plays a best match that already meets your quality (and cached) rules, Reuse
  Last Link plays the link that last worked for the same movie or episode (kept per profile in
  `stream-cache.json`, for the hours you pick), and with Show the pick on the Play button off
  the addons are only asked when you press Play. In the built-in player, a source that won't
  open is followed by the next one in the ranking.
- **Playback queue**, as on TV: Add to queue from a poster's menu, a title page, an episode or
  a Continue Watching card; the built-in player's Queue panel plays, reorders and removes
  (with Undo); autoplay puts the next episode first; near the end of something with nothing
  next, "Up next in queue" offers the queue's next item, which plays when the title ends. The
  queue syncs with the TV (`episode_queue`). In mpv's own window, Alt+Q lists the queue on the OSD.
- **After a movie**: with Settings › TMDB and More like this on, a recommendation from TMDB
  appears near the end (the TV's timing: 96 % or the credits), skipping what you've watched,
  with Play, Details, its trailer and up to four to page through. At the end its trailer plays
  in the built-in player after a five-second countdown; after mpv's window it shows over the app.
- **Prefer this next time?**: a few seconds into playing a source you picked over Best match's
  choice, one click makes Best match prefer its release group, language, resolution or size.
- **Content warnings** (Player › Content warnings): as the built-in player starts, the rating
  and IMDb's parents guide (the TV's source) show for five seconds at the top left.
- **Settings**: Stream Selection (quality, codec, file size, audio languages, skipped release
  types, audio formats and release groups, result limits), Content & Discovery (Addons, and
  Home catalogs to pin or hide rows), Appearance (the TV app's color themes), Layout (Continue
  Watching, catalog type and addon name on rows, poster labels, Discover location) and About.
  Ctrl+, opens them. **Search settings** (the field atop the rail, or Ctrl+F in Settings) finds
  any setting by name or a word such as "dolby" and opens its page with the row lit up.
- **First run**, as on TV: a fresh install opens a wizard (Server, Sources, Metadata,
  Experience), each step skippable, with Fetch website pairing, an addon link field, media
  servers, TMDB, the MDBList key and Trakt or Simkl right on the step. Existing installs skip it;
  System › Advanced › First-run setup runs it again.
- **Essential or Advanced** (System › Advanced, with the TV's confirmation, synced with it):
  Essential shows one Playback basics page instead of Playback's six, fewer rows on Layout,
  Appearance, TMDB, MDBList and About, no YouTube settings and no addon reordering, and asks for a
  stream addon ("Set up addons") until one is installed or you continue for now. Nothing saved
  changes when you switch.
- **Appearance extras**, synced with the TV: Increase contrast, AMOLED Mode and Pure Black
  Surfaces, a Custom theme (a colour from swatches, sliders or a hex code; a TV's custom theme
  shows here too) and the TV's fonts (Fetch, Inter, DM Sans, Open Sans).
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
- **Addon health and catalog order** (Settings › Content & Discovery › Addons): every addon
  request is timed, and Addon health shows each addon's median response time, failure rate,
  last success and last error over its last 50 requests, sortable and kept across restarts.
  Catalog order (also on Home catalogs) moves Home's catalogs one by one and hides them, synced
  with the TV through the Fetch website.
- **Fetch website** (Settings › Account): pair this computer with your Fetch website by a code
  (or its QR code) confirmed there; Fetch notices when the website revokes it. While paired,
  profiles, addons, settings, Home rows and collections sync with it, and it can be a tracking
  service for watch progress and history.
- **Built-in player panels**, as on TV: Audio and Subtitles (tracks, delay, any addon subtitle
  added on demand), Sources (switch mid-playback, keeping the position) and Episodes; playback
  speed (`[` `]`), picture fit (Fit, Crop, Stretch, Zoom), stream info (I), an Up Next card
  with Play now and Cancel before the end or at the credits, "Are you still watching?", and a
  pause overlay. Z/X shift subtitles, N plays the next episode. The subtitle list groups the
  file's tracks and your addons' by language (yours first) or by source, optionally only your
  languages; Subtitles › Style changes size, colour, opacity, outline, background and position
  live (saved as defaults), with a separate HDR colour and brightness on HDR video. Auto Sync
  matches an addon subtitle to the video's own subtitle track (text, or PGS/VobSub by their
  timings in the file): a shift, or a re-timed copy when it was made for another frame rate or
  cut; if it won't match, other subtitles in that language are tried; the Audio panel has a dialogue
  boost; skip silence and the audio delay per output device are in Settings › Audio. A stats
  overlay (Settings › Player › Advanced) shows cpu, memory, buffer, bitrate and network.
- **Trailers** play in the built-in player when it's your Default Player (mpv's window
  otherwise), at 1080p unless 4K trailers are on, and can auto-play on title pages
  (Settings › Layout › Trailers).
- **Play mode** (Stream Selection › When you press Play): Best match, First source, a pattern,
  or Pick every time; and how long to wait for addons. "Default Player: Ask every time".
  Limit autoplay to chosen addons (the TV's Allowed addons for autoplay, synced) picks only from
  those addons and your media servers, and stops waiting once they've answered.
- **Sources**: grouped as on TV (Best match, by resolution, Not auto-played), filtered by
  addon, and a right-click menu to play in either player, prefer that addon, never auto-play
  that release group, or copy the link.
- **Mark watched**: titles, episodes, earlier episodes and seasons, from the title page, an
  episode's or any poster's right-click menu; synced to Trakt, Simkl, MDBList and the Fetch website.
  Episodes show watched marks, progress and "Not aired yet". Start over on the title page.
- **More player settings** passed to mpv: secondary audio and subtitle languages, forced
  subtitles, removing hearing-impaired text, subtitle bold, color, outline, position and ASS
  styling, downmix channels, normalization, volume boost, cache size; an IntroDB server of
  your own and the Skip button toggle.
- **Recent searches**, hide unreleased titles, a Configure button for configurable addons, and
  an update check (Settings › About) at start-up and every six hours that links the package
  for this install. A newer version found while Fetch is running also raises a desktop
  notification (through `notify-send`), once per version. An install that follows Fetch's APT
  repository is shown the `apt` command to run instead of a download.
- **Ambient Mode** (Settings › Appearance), on by default as on TV: after five minutes (or the
  time you pick) with no mouse or keyboard input, and never while something plays, the window
  shows a slideshow of backdrops from up to three Home rows, or a clock with what to resume.
  Any input closes it.
- **Links**: `fetch://` links open in Fetch, as on TV: a title (`fetch://movie/tt0111161`,
  `fetch://meta?type=series&id=…`, `fetch://tmdb/movie/603`) and addon links, which install the addon (not on
  restricted profiles). `stremio://` addon links work too: an addon's Install button after
  Configure replaces the addon in place. Fetch runs once per user: launching it again (or
  clicking a link) hands the link to the open window and brings it forward. Installed and
  unpacked builds register themselves for `fetch://` with `xdg-mime` (a hidden entry in
  `~/.local/share/applications`), and for `stremio://` when no other app has it; Settings ›
  About shows which app opens each and can take them over.
- **Layout** (Settings › Layout, synced with the TV under its `layout_settings`,
  `trailer_settings` and `player_settings` keys, see `data/LayoutPrefs.kt`): Continue Watching
  card style (Card, Wide, Poster), episode stills instead of the backdrop and blurred Next Up
  stills; the hero on or off, hero catalogs it cycles through every 10 seconds, a full-window
  backdrop, ratings on Home on or off and full release dates; poster card width, height and
  corner radius (the TV's sizes, scaled), landscape cards everywhere and backdrop-with-logo
  landscape cards; the hovered poster growing into its backdrop after a delay; a muted (or not)
  trailer in the hero after the trailer delay, cropped or letterboxed (libmpv and yt-dlp); on
  title pages the episode options backdrop (Blur, Artwork, None) behind an episode's sources,
  Shuffle, blurred unwatched episodes, episode ratings (show, hide unwatched, hide), the
  trailer button and "prefer meta from external addon"; and a custom poster URL pattern (the
  TV's resolver) for Home, Continue Watching, collections, Library, Search and title pages.
- **Home catalogs** (Settings › Content & Discovery › Home catalogs): every row and collection
  in Home's order to move, pin, rename or hide; "Follow addons order" keeps the catalogs in
  addon order and lets only collections move.
- **TMDB on Home** (Settings › TMDB): "Enable on Home" puts TMDB's artwork and details in the
  hero; "Enrich Continue Watching" gives cards TMDB's artwork, logo and title.
- **Ready tonight**: with Best match as the Play mode, Fetch asks the addons in the background
  (while Home is up, at most 6 titles, each at most every 12 hours) about Continue Watching and
  watchlist movies; those whose Best match is cached on a debrid service (confirmed in the last
  day) show in a row after Continue Watching. Title pages save their Best match too
  (`best-match.json`). As on TV it doesn't run on metered or mobile connections (NetworkManager's
  `Metered` and connection type, read over D-Bus; without NetworkManager it runs).
- **Episode ratings grid** on series pages: IMDb's rating per episode by season, coloured as on
  TV, from the TV's ratings services (`IMDB_TAPFRAME_API_BASE_URL`, then
  `IMDB_RATINGS_API_BASE_URL` by TMDB id, given at build time from the environment or the root
  `local.properties`; the release workflow passes the repository secrets of the same names.
  Without them there's no grid).
- **Trakt comments** on title pages (with a Trakt client id in the build): most liked first,
  for the title or the episode picked, spoilers hidden until clicked, whole comment on click.
- **Shuffle** (Layout › Shuffle): a Shuffle button on series pages picks a random unwatched (or
  any) episode to preview and play; the show stays shuffled, so its Continue Watching card
  plays a new pick, and any card's right-click menu offers "Shuffle episodes" (`shuffle.json`).
- **Kids profiles** (Profiles › Limits › Kids profile, synced as the TV's `isKidsProfile`): no
  hero, upcoming row, recommendations, Ready tonight or YouTube row; at most six rows after
  Continue Watching, cards a quarter bigger, no addon names or ratings, and Switch profile
  first in the sidebar.
- **Library sort**: Trakt, Simkl and MDBList lists open in their own order ("Provider order",
  Trakt's ranks), and MDBList also sorts by release.
- **Collection folders' hero videos** play behind the folder page, and behind Home's hero while
  the folder's tile is pointed at. Renaming a local list renames the collection sources showing it.
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

Whatever the owner and friends need next. Two standing rules:

1. **Move duplicated clients into `shared`** when they are next touched (media servers,
   Trakt/Simkl, the Fetch website), putting their Android dependencies behind interfaces first,
   rather than adding more desktop-only copies.
2. **More packages** if someone wants them: Flatpak (Flathub) and AppImage, then Windows (`.msi`)
   and macOS (`.dmg`) from the same `nativeDistributions` block.
