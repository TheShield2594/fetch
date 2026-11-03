# Desktop parity with the TV app

What the Linux app has of the Android TV app, and what's left. The TV paths are relative to
`app/src/main/java/com/fetch/tv/`, the desktop ones to `desktop/src/main/kotlin/com/fetch/desktop/`.
Update this list when either app changes.

Legend: ✅ on desktop · 🟡 partly · ⬜ not yet · ➖ Android-only, not planned

## Playback

| Feature | TV | Desktop |
|---|---|---|
| Best match, ranked sources with reasons, badges | `core/streams/ranking/`, `ui/screens/stream/` | ✅ shared `StreamRanker`; `ui/DetailScreen.kt` |
| Play mode: Best match, First source, Pattern, Manual | `PlaybackAutoPlaySettings.kt` | ✅ Stream Selection › When you press Play (`pickSource`) |
| Addon timeout | `PlaybackAutoPlaySettings.kt` | ✅ |
| Source sections (Best match, by resolution, passed over) and addon chips | `stream/SourceGroups.kt`, `StreamScreen.kt` | ✅ All sources panel |
| Source options: prefer addon, never a release group | `stream/SourceOptionsDialog.kt`, `SourceRules.kt` | ✅ right-click on a source |
| Player per source (built-in or mpv window) | `StreamScreen.kt` | ✅ right-click on a source; "Ask every time" |
| Sources, Audio, Subtitles, Episodes side panels in the player | `player/PlayerSidePanel.kt` and panels | ✅ built-in player; switching source keeps the position |
| Audio and subtitle delay | `AudioSelectionOverlay.kt`, `SubtitleSelectionOverlay.kt` | ✅ panels, Z/X keys |
| Addon subtitles picked in the player | `SubtitleSelectionOverlay.kt` | ✅ every language, added on demand |
| Playback speed | `PlayerUiState.kt` | ✅ menu and `[` `]` keys |
| Aspect / zoom | `PlayerAspectScaleUtils.kt` | ✅ Fit, Crop, Stretch, Zoom (setting and live) |
| Stream info overlay | `StreamInfoOverlay.kt` | ✅ I key |
| Up Next card with Play now / Cancel | `NextEpisodeEndPromptOverlay.kt` | ✅ before the end or at the credits |
| Are you still watching? | `PlayerRuntimeControllerStillWatching.kt` | ✅ setting and episode count |
| Pause overlay | `PauseOverlay.kt` | ✅ |
| Skip intro/recap/credits, auto-skip, button toggle, IntroDB server | `SkipIntroButton.kt`, `SkipMarkerSettings.kt` | ✅ (IntroDB only) |
| AniSkip and Anime-Skip | `data/repository/SkipIntroRepository.kt` | ⬜ |
| Subtitle style: size, bold, color, outline, background, position, ASS override | `PlaybackSubtitleSettings.kt` | ✅ Settings (mpv options); ⬜ live style panel in the player |
| Forced subtitles, strip SDH, secondary languages | `PlaybackSubtitleSettings.kt`, `PlaybackAudioSettings.kt` | ✅ |
| Subtitle organisation, only preferred languages | `PlaybackSubtitleSettings.kt` | ⬜ |
| HDR subtitle color and brightness | `PlaybackSubtitleSettings.kt` | ⬜ |
| Automatic subtitle sync | `player/autosync/` | ⬜ |
| Downmix channels, normalization, volume boost | `PlaybackAudioSettings.kt` | ✅ |
| Dialogue (centre) boost, skip silence, audio delay per output | `PlaybackAudioSettings.kt` | ⬜ |
| Cache size | `PlaybackBufferNetworkSettings.kt` | ✅ (mpv's demuxer cache) |
| Next-episode threshold by percent | `PlaybackAutoPlaySettings.kt` | 🟡 Up Next starts at the end; the card offers it earlier |
| Reuse last link, preload next episode, fallback to next source, binge group | `PlaybackAutoPlaySettings.kt` | ⬜ |
| Post-play recommendation after a movie | `PostPlayRecommendation*.kt` | ⬜ (TMDB recommendations are now available to build it on) |
| "Prefer this next time" prompt | `PreferNextTimePrompt.kt` | ⬜ (the source menu sets the same rules) |
| Playback queue | `QueueSidePanel.kt` | ⬜ |
| Parental guide overlay at start | `ParentalGuideOverlay.kt` | ⬜ |
| Debug stats HUD | `PlayerDebugStatsOverlay.kt` | 🟡 stream info shows drops and buffer |
| Fetching panel with live per-addon progress | `stream/FetchingPanel.kt` | ⬜ sources appear when every addon has answered |
| ExoPlayer engine, AFR, DV7 conversion, tunneling, decoder priority, Exo buffers | `player/*` | ➖ mpv does this its own way |

## Home, title pages, Library, Search

| Feature | TV | Desktop |
|---|---|---|
| Hero, Continue Watching, addon rows, pin and hide rows | `screens/home/` | ✅ |
| Hide unreleased content | `HomeUiState.hideUnreleasedContent` | ✅ |
| Poster options (play, details, library, watched) | `components/posteroptions/` | ✅ right-click on any poster |
| Mark watched/unwatched: title, episode, previous episodes, season | `detail/DetailButtons.kt`, `EpisodeOptionsOverlay.kt` | ✅ synced to Trakt, Simkl and the sync server |
| Episode watched marks, progress and not-aired state | `detail/EpisodesSection.kt` | ✅ |
| Start over on the title page | `detail/DetailButtons.kt` | ✅ |
| Recent searches | `search/SearchScreen.kt` | ✅ |
| Catalog "See all" grid | `screens/CatalogSeeAllScreen.kt` | ✅ "See all" on each addon row, paged, with its genres |
| Continue Watching airing rules: caught-up shows return when a new episode is listed, unaired Next Up with its air date | `home/ContinueWatchingAiringRules.kt`, `HomeViewModelContinueWatching.kt` | ✅ re-checked at start-up and every 6 hours |
| Show unaired Next Up, Next Up from furthest episode, sort order with a separate Upcoming row | `settings/LayoutHomeSettings.kt` | ✅ synced with the TV |
| Continue Watching card style, episode thumbnails, blur Next Up | `settings/LayoutHomeSettings.kt` | ⬜ |
| TMDB enrichment: artwork, title and description in your language, details, cast and crew, episodes, trailers | `core/tmdb/TmdbMetadataService.kt`, `detail/MetaDetailsViewModel.kt` | ✅ `tmdb/`, Settings › TMDB, each part switchable, synced with the TV |
| TMDB rating, director or creator, show status on the title page | `detail/HeroSection.kt` | ✅ |
| More like this, Because you watched | `detail/MoreLikeThisSection.kt`, `home/HomeViewModelBecauseYouWatched.kt` | ✅ (without the TV's "Also directed by" reasons) |
| Cast and crew pages | `screens/cast/` | ✅ biography, facts, filmography |
| Collections ("Part of …") | `detail/CollectionSection.kt` | ✅ |
| Studio and network logos and pages | `detail/CompanyLogosSection.kt`, `screens/tmdb/` | ✅ paged, movies or series for studios |
| Titles only on TMDB (no IMDb id) | `core/tmdb/` | ✅ page built from TMDB; episode ids `tmdb:<id>:<s>:<e>` for addons that take them |
| TMDB's modern Home and Continue Watching enrichment | `TmdbSettingsScreen.kt` | ⬜ (kept untouched when syncing) |
| Episode ratings grid (IMDb), Trakt comments | `detail/EpisodeRatingsSection.kt`, `CommentsSection.kt` | 🟡 TMDB's per-episode rating on each episode card; no grid or comments |
| Episode shuffle | `detail/DetailEpisodeShuffle.kt` | ⬜ |
| Ready tonight row | `home/ReadyTonight.kt` | ⬜ |
| Collections, folders and filter rows | `screens/collection/`, `screens/filterrows/` | ⬜ |
| Lists: local lists, Trakt watchlist and personal lists, Simkl status lists, as Library tabs | `library/LibraryScreen.kt`, `data/simkl/SimklLibraryProjection.kt` | ✅ |
| Manage lists for a title; create, edit (privacy on Trakt), reorder and delete lists | `library/LibraryListDialogs.kt`, `posteroptions/` | ✅ poster menu and the title page's Lists button |
| MDBList lists | `data/mdblist/` | ⬜ |
| Library provider order | `library/LibraryProviderOrder.kt` | ⬜ |
| Kids Home layout | `HomeUiState.kidsHome` | ⬜ |
| Hero carousel, trailer on focus, card size and shape options | `settings/LayoutHomeSettings.kt`, `LayoutCardSettings.kt` | ⬜ |
| In-app trailer playback | `data/trailer/` | 🟡 trailers open in mpv with yt-dlp |

## Accounts, profiles and system

| Feature | TV | Desktop |
|---|---|---|
| Profiles, PINs, rating limits, screen time, bedtime, autoplay cap | `core/profile/`, `core/parental/` | ✅ |
| Hide or allow single titles | `profile/ParentalTitleOptions.kt` | ⬜ |
| Profile setup with copied settings, preferences tab, viewing activity, avatar images | `screens/profile/` | ⬜ |
| Trakt, Simkl, Jellyfin, Emby, Plex, sync server | `core/tracking/`, `data/mediaserver/`, `core/sync/` | ✅ |
| Tracking options (sources for library, progress, More like this) | `TrackingSettingsScreen.kt` | ⬜ |
| MDBList ratings and lists | `data/mdblist/` | ⬜ |
| Addon configure page | `screens/addon/AddonManagerScreen.kt` | ✅ opens in the browser; install the link it gives |
| Addon health panel, catalog order | `screens/addon/` | ⬜ |
| Update check (stable/beta) | `updater/` | ✅ banner and Settings › About; installing stays with apt, dnf or the tarball |
| First-run wizard | `screens/setup/` | ⬜ |
| Settings search, Essential/Advanced mode | `settings/SettingsSearch*.kt`, `ExperienceMode*` | ⬜ |
| Theme extras (high contrast, AMOLED, custom), fonts, UI language | `settings/ThemeSettingsScreen.kt` | ⬜ |
| Deep links (`fetch://`, `stremio://`) | `core/deeplink/` | ⬜ |
| Fetch website pairing, show a sign-in code | `screens/account/` | 🟡 desktop claims codes only |
| YouTube (feeds, SponsorBlock, DeArrow) | `screens/youtube/`, `data/youtube/` | ⬜ |
| Watch party | `screens/party/`, `core/party/` | ⬜ |
| Ambient mode | `screens/ambient/` | ✅ Appearance › Ambient Mode: artwork slideshow (Ken Burns, up to 3 Home rows) or clock with what to resume; never during playback |
| Phone hub | `screens/phone/` | ➖ a desktop has a keyboard; may return for sending links |
| Watch Next row, launcher channels, app icon picker | `core/recommendations/`, `AppIconPicker.kt` | ➖ |
