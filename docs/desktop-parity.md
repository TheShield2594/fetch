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
| More like this, Because you watched | `detail/MoreLikeThisSection.kt`, `home/HomeViewModelBecauseYouWatched.kt` | ✅ from Trakt, TMDB or Simkl (Settings › Tracking), without the TV's "Also directed by" reasons |
| Cast and crew pages | `screens/cast/` | ✅ biography, facts, filmography |
| Collections ("Part of …") | `detail/CollectionSection.kt` | ✅ |
| Studio and network logos and pages | `detail/CompanyLogosSection.kt`, `screens/tmdb/` | ✅ paged, movies or series for studios |
| Titles only on TMDB (no IMDb id) | `core/tmdb/` | ✅ page built from TMDB; episode ids `tmdb:<id>:<s>:<e>` for addons that take them |
| TMDB's modern Home and Continue Watching enrichment | `TmdbSettingsScreen.kt` | ⬜ (kept untouched when syncing) |
| Episode ratings grid (IMDb), Trakt comments | `detail/EpisodeRatingsSection.kt`, `CommentsSection.kt` | 🟡 Trakt comments (spoilers hidden until clicked) and TMDB's per-episode rating on each episode card; no grid |
| Episode shuffle | `detail/DetailEpisodeShuffle.kt` | ⬜ |
| Ready tonight row | `home/ReadyTonight.kt` | ⬜ |
| Collections: Home rows of folders (tile shape, cover image or emoji, hidden title), pinned to the top or after the catalog rows; folder pages as tabbed grid with "All", rows, or Home-style layout; sources from addon catalogs (with genre), TMDB (list, collection, studio, network, person, director, discover filters), Trakt lists and local lists, each paged; management and editor; import/export JSON; sync | `screens/collection/`, `home/CollectionRowSection.kt`, `core/sync/CollectionSyncService.kt` | ✅ `collections/`, `ui/CollectionsUi.kt`, Settings › Content & Discovery › Collections; Fetch website lists load once paired (`website/`); no hero videos or hiding a collection from Home catalogs |
| Filter rows: a server library (genre, years, watched, rating, sort) or an addon catalog's genre as a Home row | `screens/filterrows/`, `domain/model/FilterRow.kt` | ✅ Settings › Content & Discovery › Filter rows; addon ones pin and hide like other rows and have See all |
| Lists: local lists, Trakt watchlist and personal lists, Simkl status lists, as Library tabs | `library/LibraryScreen.kt`, `data/simkl/SimklLibraryProjection.kt` | ✅ |
| Manage lists for a title; create, edit (privacy on Trakt), reorder and delete lists | `library/LibraryListDialogs.kt`, `posteroptions/` | ✅ poster menu and the title page's Lists button |
| MDBList lists: Watchlist, static, dynamic, external and liked lists as Library tabs; Manage lists; create, rename, delete static lists; hide lists | `data/mdblist/MdbListLibrary*.kt`, `settings/MdbListLibraryListsDialog.kt` | ✅ `mdblist/MdbListAccountState.kt`, `ListsState.kt`; sorted by provider order, added or release (MDBList's own order, `MdbListAccountState.order`) |
| Library provider order | `library/LibraryProviderOrder.kt` | ⬜ |
| Kids Home layout | `HomeUiState.kidsHome` | ⬜ |
| Hero carousel, trailer on focus, card size and shape options | `settings/LayoutHomeSettings.kt`, `LayoutCardSettings.kt` | ⬜ |
| In-app trailer playback | `data/trailer/` | 🟡 trailers open in mpv with yt-dlp |

## Accounts, profiles and system

| Feature | TV | Desktop |
|---|---|---|
| Profiles, PINs, rating limits, screen time, bedtime, autoplay cap | `core/profile/`, `core/parental/` | ✅ |
| Hide or allow single titles | `profile/ParentalTitleOptions.kt` | ✅ `parental/TitleLists.kt`, `ui/ParentalTitlesUi.kt`: poster menu (Hide from <profile> behind the main PIN, or per profile from the main profile), filtered everywhere, listed in Limits; kept on the device as on TV |
| Profile setup with copied settings, preferences tab, viewing activity, avatar images | `screens/profile/` | ✅ `ui/ProfileSetupUi.kt`, `data/ProfileSetup.kt`, `data/AvatarCatalog.kt`: kids profile, shared addons, copy settings (and missing API keys), Preferences tab, viewing activity, the 40 bundled avatars plus the sync server's catalog; no profile backgrounds, PIN nudge or lock offer |
| Trakt, Simkl, Jellyfin, Emby, Plex, sync server | `core/tracking/`, `data/mediaserver/`, `core/sync/` | ✅ |
| Tracking options (sources for library, progress, More like this) | `TrackingSettingsScreen.kt` | ✅ `tracking/TrackingOptions.kt`, `ui/TrackingOptionsUi.kt`: Library source, watch progress source, Continue Watching window, More like this source, comments, Simkl anime id; synced as `trakt_settings` |
| MDBList ratings on the title page and Home hero: which, in what order, batched and cached 12 h | `data/mdblist/MdbListRatings*.kt`, `settings/MDBListSettingsScreen.kt`, `components/MDBListRatingsRow.kt` | ✅ Settings › MDBList, `mdblist/MdbListRatingsState.kt`, `ui/MdbListUi.kt`; synced with the TV except the API key |
| MDBList account: connect with a code | `data/mdblist/MdbListAuthRepository.kt`, `settings/MdbListAccountDialog.kt` | ✅ needs `MDBLIST_CLIENT_ID`; an API key also connects |
| MDBList watched history, playback progress and scrobbling | `data/mdblist/MdbListSync*.kt`, `MdbListScrobbleService.kt` | ✅ `mdblist/MdbListSync.kt` (by IMDb id; no journal or dropped shows) |
| MDBList API key synced between devices (provider credentials) | `core/sync/ProviderCredentialSyncService.kt` | ✅ `sync/ProviderCredentialSync.kt` (AnimeSkip's id is carried unchanged) |
| Addon configure page | `screens/addon/AddonManagerScreen.kt` | ✅ opens in the browser; install the link it gives |
| Addon health panel, catalog order | `screens/addon/` | ✅ `addons/AddonHealth.kt`, `addons/HomeCatalogOrder.kt`, `ui/AddonToolsUi.kt`; catalog order synced as `home_catalog_shared`; no Disable (desktop addons have no on/off) |
| Update check (stable/beta) | `updater/` | ✅ banner and Settings › About; installing stays with apt, dnf or the tarball |
| First-run wizard | `screens/setup/` | ⬜ |
| Settings search, Essential/Advanced mode | `settings/SettingsSearch*.kt`, `ExperienceMode*` | ⬜ |
| Theme extras (high contrast, AMOLED, custom), fonts, UI language | `settings/ThemeSettingsScreen.kt` | ⬜ |
| Deep links (`fetch://`, `stremio://`) | `core/deeplink/` | ✅ the TV's parser; titles, addon install (replacing an addon whose Configure page sent it), party invites; one Fetch per user, later launches hand their link over; registered with xdg-mime (`stremio://` only when nothing else has it, or from Settings › About) |
| Fetch website pairing, show a sign-in code | `screens/account/` | ✅ `website/FetchWebsite.kt`, `ui/FetchWebsiteUi.kt`: pairing by code and QR, revocation notice, website lists; sync codes with a PIN (`generate_sync_code`). 🟡 profiles, addons, settings and tracking don't sync with the website yet |
| YouTube sign-in (TV device code at youtube.com/activate), brand channel switching | `data/youtube/YouTubeService.kt`, `settings/YouTubeSettingsScreen.kt` | ✅ `youtube/YouTubeClient.kt`, Settings › YouTube |
| YouTube page in the sidebar while signed in (not on restricted profiles): Home, Subscriptions, History, Library, Search | `screens/youtube/YouTubeScreen.kt`, `MainActivity.kt` | ✅ `ui/YouTubeScreen.kt`, `YouTubeState.kt` |
| YouTube search suggestions and recent searches | `YouTubeService.searchSuggestions`, `YouTubeSettingsDataStore` | ✅ |
| YouTube channel, playlist and "View all" pages, Watch later and Liked | `YouTubeViewModel.openBrowse` | ✅ |
| YouTube tile menu: go to channel, Watch later, save to playlist, new playlist, remove from history, not interested | `YouTubeViewModel`, `YouTubePlaylistPicker.kt` | ✅ right-click a video (plus Open on YouTube) |
| YouTube playback, quality cap | `YouTubeViewModel.play`, `data/trailer/` extractor | ✅ mpv (built-in or window) through yt-dlp, `--ytdl-format` from the quality setting |
| YouTube resume (exact, else YouTube's percent) and account watch history | `YouTubeResumeStore.kt`, `YouTubeService.reportWatchTime` | ✅ `youtube.json`; history reported every 30 s and at the end |
| SponsorBlock with per-category Skip / Ask / Show / Off | `SponsorBlockService.kt`, `YouTubePlayerOverlay.kt` | ✅ through the skip system (Ask: the built-in player's Skip button); 🟡 Show has no seek bar marks, and there's no "undo skip" |
| DeArrow titles and thumbnails | `DeArrowService.kt` | ✅ |
| Return YouTube Dislike counts | `ReturnYouTubeDislikeService.kt` | 🟡 in the built-in player's pause screen; not shown with mpv's window |
| YouTube autoplay (Up Next, playlists continue) | `YouTubeViewModel.playNext` | 🟡 starts the next pick at the end, without the TV's 10-second countdown card |
| Shorts, live and watched filters | `YouTubeFeedFilter.kt` | ✅ the TV's filter, shared code |
| Home "YouTube subscriptions" row | `home/HomeYouTubeRow.kt` | ✅ after Continue Watching; off on restricted profiles; 10-minute cache |
| YouTube captions | `YouTubeCaptions.kt` | 🟡 yt-dlp loads them in your subtitle languages, off until picked in the player (mpv's own rendering, not the TV's caption styles) |
| YouTube chapters, speed | `YouTubePlayerMenus.kt` | 🟡 mpv reads chapters from yt-dlp; speed in the built-in player's menu; no chapter list |
| In-player like, dislike, subscribe and save | `YouTubePlayerOverlay.kt` | ⬜ (the client has them; the built-in player has no YouTube actions row yet) |
| Seek bar thumbnails (storyboards) | `YouTubeStoryboard.kt`, `YouTubeSeekBar.kt` | ⬜ |
| Watch party | `screens/party/`, `core/party/` | ✅ `party/` (the TV's RPCs, Realtime channel, sync and tolerances), Watch together on title pages, Watch party in the player and in Settings; QR invite in the party panel (`qr/QrCode.kt`), members' avatar images |
| Ambient mode | `screens/ambient/` | ✅ Appearance › Ambient Mode: artwork slideshow (Ken Burns, up to 3 Home rows) or clock with what to resume; never during playback |
| Phone hub | `screens/phone/` | ➖ a desktop has a keyboard; may return for sending links |
| Watch Next row, launcher channels, app icon picker | `core/recommendations/`, `AppIconPicker.kt` | ➖ |
