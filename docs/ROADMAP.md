# Fetch roadmap

Status of every product requirement, mapped to where it lives in the code.
Paths are relative to `app/src/main/java/com/fetch/tv/`.

Legend: ✅ done · 🟡 partial · ⬜ not started

## Must-haves

| Requirement | Status | Where / notes |
|---|---|---|
| Stremio addon protocol: manifest, catalog, meta, stream, subtitles, `behaviorHints` | ✅ | `data/remote/api/AddonApi.kt`, `data/remote/dto/`, `data/mapper/`. Hints: `notWebReady`, `bingeGroup`, `countryWhitelist`, `proxyHeaders`, `videoHash`, `videoSize`, `filename`. Gap: no in-app flow opens an addon's `/configure` page (configure from the phone page instead). |
| Media3/ExoPlayer + FFmpeg audio extension | ✅ | `ui/screens/player/PlayerRuntimeController*.kt`, `ffmpeg-decoder-downmix/`. Uses a prebuilt Media3 fork in `app/libs/` inherited from upstream (see follow-ups). |
| libass ASS/SSA | ✅ | `PlayerLibassCompat.kt`, `FetchAssMatroskaExtractor.kt` (`io.github.peerless2012:ass-media`). |
| Passthrough AC3 / E-AC3 / TrueHD / DTS | ✅ | `AudioOutputRouteDetector.kt`, audio sink setup in `PlayerRuntimeControllerInitialization.kt`, "force optical passthrough" setting. |
| Automatic refresh-rate matching | ✅ | `core/player/FrameRateUtils.kt`, `DisplayCapabilities.kt`, `AfrPreflightGate.kt`. Off by default; consider defaulting to *start* on capable devices. |
| HDR10 / HLG / Dolby Vision with fallback | ✅ | `DolbyVisionCodecFallback.kt`, `DolbyVisionBaseLayerPolicy.kt`, `core/player/dvmkv/`, DV7 handling modes. Stream ranking now also refuses to auto-play DV profile 5 where it can't be displayed. |
| Subtitle offset, styling, size | ✅ | `SubtitleStyleSidePanel.kt`, `SubtitleTimingDialog.kt`, automatic sync in `player/autosync/`. |
| One-press external player hand-off | ✅ | `core/player/ExternalPlayerLauncher.kt` (passes headers, position, subtitles, skip segments). |
| Compose for TV, focus restore on Back, long-press actions, D-pad-only flows | ✅ | `ui/screens/home/HomeScreenFocusState.kt`, `ui/screens/detail/FocusRestoreUtils.kt`, `ui/util/LongPressKeyTracker.kt`, `ui/components/posteroptions/`. |
| Continue Watching + next-episode autoplay | ✅ | `ui/screens/home/HomeViewModelContinueWatching.kt`, `PlayerNextEpisodeRules.kt`, `NextEpisodeEndPromptOverlay.kt`. |
| Skip intro / recap from community markers | 🟡 | `data/repository/SkipIntroRepository.kt`: AniSkip and Anime-Skip work out of the box; IntroDB (non-anime) needs `INTRODB_API_URL`. |
| Two-way Trakt and Simkl sync | ✅ | `data/repository/Trakt*`, `data/simkl/`, `core/tracking/`. |
| Android TV "Watch Next" row | ✅ | `core/recommendations/TvRecommendationManager.kt`, `core/sync/androidtv/`. |
| Debrid as a built-in setting | ✅ | `core/debrid/` (Real-Debrid, Premiumize, TorBox), `ui/screens/settings/DebridSettingsScreen.kt`. Gap: AllDebrid / Debrid-Link. |
| Profiles | ✅ | `core/profile/ProfileManager.kt`, per-profile settings and addons. |
| Parental controls | 🟡 | Profile PINs exist but are verified by the sync server, so they don't work in local-only mode. Needs a local PIN and certification-based filtering / kids profiles. |
| Low-end performance (lazy artwork, capped caches, fast cold start) | 🟡 | Coil memory cache scales with RAM, 200 MB disk cache, RGB565 option, baseline profiles, decoder probing off the main thread. Gap: disk cache is not scaled to free storage; cold-start work in `FetchApplication.onCreate` should be audited on a 1.5 GB device. |

## What we're changing

| # | Change | Status | Where / notes |
|---|---|---|---|
| 1 | Rebuilt stream picker: parsed fields, user rules, device-aware ranking, auto-play best match with other sources one press away | 🟡 | ✅ `core/streams/ranking/` (`StreamTitleParser`, `StreamRanker`, `AndroidDeviceDecodeCapabilities`), "Best match" auto-play mode (default), rule settings in Playback → Stream selection, ranked "All" list, spec line on each card. Next: structured chips instead of raw strings, an "Other sources" button on the player loading screen, unify with the debrid-only filters in `core/debrid/DirectDebridStreamFilter.kt`. |
| 2 | Configure the TV from a phone | 🟡 | QR + local web pages already cover addons, catalog order, plugin repos, debrid formatting, badges and posters (`core/server/`). Next: Trakt/Simkl/debrid logins and long settings on the phone page, and a phone remote with keyboard for search. |
| 3 | Local-first, private, optional self-hosted sync | 🟡 | ✅ Sentry removed, no hosted backend, local-only by default, issue reports disabled unless a builder sets an endpoint. Sync still speaks the upstream Supabase protocol; a Cataloggy adapter (addon-format watch state) is the next step. |
| 4 | Jellyfin and Plex as first-class sources | 🟡 | Jellyfin and Emby items already appear in stream lists and outrank streamed results under Best match (`data/mediaserver/`). Plex is not implemented; catalogs/Continue Watching integration should be verified. |
| 5 | Progressive, visible addon results | ✅ | Parallel, progressive stream loading (`data/repository/StreamRepositoryImpl.kt`), per-addon 25 s timeout, health line on each addon card (`core/streams/AddonHealthTracker.kt`). Next: make the timeout configurable and persist health across restarts. |
| 6 | Customizable home screen | 🟡 | Reorder and hide rows (`ui/screens/addon/CatalogOrderScreen.kt`), custom rows from TMDB/Trakt/addon filters (`ui/screens/collection/`). Next: pin rows, and filter rows over the user's own Jellyfin library ("unwatched sci-fi"). |
| 7 | Simple vs advanced settings | ✅ | `ExperienceMode` ESSENTIAL / ADVANCED (`docs/essential-mode.md`). |

## Follow-ups from the fork

- **Media3 fork.** `app/libs/lib-*-release.aar` are prebuilt Media3 modules from upstream
  (they expose `androidx.media3.common.NuvioEngineConfig`). Replace them with stock Media3 or
  a fork built from source in this repo.
- **QuickJS fork.** `app/libs/quickjs-kt-android-1.0.5-nuvio.aar` is upstream's patched build,
  used only by local scraper plugins in the `full` flavor.
- **Torrent engine.** `core/torrent/TorrentService.kt` keeps the upstream API without an
  engine. An open-source engine (e.g. libtorrent4j) can be plugged in there.
- **Translations.** Strings whose meaning changed (sync, licenses) fall back to English in
  other locales until re-translated. Strings for removed features can be pruned.
- **Known failing test (inherited).** `TraktProgressServiceRefreshTest` "change seen by a
  cancelled refresh is fetched by the next poll" also fails on upstream at the forked commit:
  a refresh cancelled mid-flight leaves the next poll throwing "Progress snapshot fetch
  failed". CI stays red on it until `TraktProgressService.refreshRemoteSnapshot` is fixed.
  (`TraktAuthServiceTest` needs Trakt client credentials and is skipped without them.)
