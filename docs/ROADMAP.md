# Fetch roadmap

Status of every product requirement, mapped to where it lives in the code.
Paths are relative to `app/src/main/java/com/fetch/tv/`.

Legend: ✅ done · 🟡 partial · ⬜ not started

## Must-haves

| Requirement | Status | Where / notes |
|---|---|---|
| Stremio addon protocol: manifest, catalog, meta, stream, subtitles, `behaviorHints` | ✅ | `data/remote/api/AddonApi.kt`, `data/remote/dto/`, `data/mapper/`. Hints: `notWebReady`, `bingeGroup`, `countryWhitelist`, `proxyHeaders`, `videoHash`, `videoSize`, `filename`. `configurable` addons get a Configure action (QR to `/configure`, configured link sent back through `core/server/AddonConfigureServer.kt`); `configurationRequired` manifests are not installed until configured. |
| Media3/ExoPlayer + FFmpeg audio extension | ✅ | `ui/screens/player/PlayerRuntimeController*.kt`, `ffmpeg-decoder-downmix/`. Stock Media3 1.8.0 from Maven; the prebuilt upstream fork was dropped (`docs/architecture/media3-fork.md`). |
| libass ASS/SSA | ✅ | `PlayerLibassCompat.kt`, `FetchAssMatroskaExtractor.kt` (`io.github.peerless2012:ass-media`). |
| Passthrough AC3 / E-AC3 / TrueHD / DTS | ✅ | `AudioOutputRouteDetector.kt`, audio sink setup in `PlayerRuntimeControllerInitialization.kt`, "force optical passthrough" setting. |
| Automatic refresh-rate matching | ✅ | `core/player/FrameRateUtils.kt`, `DisplayCapabilities.kt`, `AfrPreflightGate.kt`. Off by default; consider defaulting to *start* on capable devices. |
| HDR10 / HLG / Dolby Vision with fallback | ✅ | `DolbyVisionCodecFallback.kt`, `DolbyVisionBaseLayerPolicy.kt`, `core/player/dvmkv/`, DV7 handling modes. Stream ranking now also refuses to auto-play DV profile 5 where it can't be displayed. |
| Subtitle offset, styling, size | ✅ | `SubtitleStyleSidePanel.kt`, `SubtitleTimingDialog.kt`, automatic sync in `player/autosync/`. |
| One-press external player hand-off | ✅ | `core/player/ExternalPlayerLauncher.kt` (passes headers, position, subtitles, skip segments). |
| Compose for TV, focus restore on Back, long-press actions, D-pad-only flows | ✅ | `ui/screens/home/HomeScreenFocusState.kt`, `ui/screens/detail/FocusRestoreUtils.kt`, `ui/util/LongPressKeyTracker.kt`, `ui/components/posteroptions/`. |
| Continue Watching + next-episode autoplay | ✅ | `ui/screens/home/HomeViewModelContinueWatching.kt`, `PlayerNextEpisodeRules.kt`, `NextEpisodeEndPromptOverlay.kt`. |
| Skip intro / recap from community markers | ✅ | `data/repository/SkipIntroRepository.kt`: AniSkip, Anime-Skip and IntroDB (public `api.introdb.app` by default, overridable in Advanced settings). |
| Two-way Trakt and Simkl sync | ✅ | `data/repository/Trakt*`, `data/simkl/`, `core/tracking/`. |
| Android TV "Watch Next" row | ✅ | `core/recommendations/TvRecommendationManager.kt`, `core/sync/androidtv/`. |
| Debrid as a built-in setting | ✅ | `core/debrid/` (Real-Debrid, Premiumize, TorBox), `ui/screens/settings/DebridSettingsScreen.kt`. Gap: AllDebrid / Debrid-Link. |
| Profiles | ✅ | `core/profile/ProfileManager.kt`, per-profile settings and addons. |
| Parental controls | ✅ | `core/parental/`: local salted PIN hash with lockout (`ProfilePinManager`, works offline; mirrored to the sync server when signed in), per-profile rating limit and unrated toggle (`ParentalControls`, ratings from addons or TMDB), kids preset (no hero or upcoming row), filtering of home rows, search, Discover and Continue Watching, and blocked streams above the limit. The parent PIN guards settings, addon management, phone pages and leaving a restricted profile (`ui/screens/profile/ParentalGate.kt`). |
| Low-end performance (lazy artwork, capped caches, fast cold start) | 🟡 | Coil memory cache scales with RAM, 200 MB disk cache, RGB565 option, baseline profiles, decoder probing off the main thread. Gap: disk cache is not scaled to free storage; cold-start work in `FetchApplication.onCreate` should be audited on a 1.5 GB device. |

## What we're changing

| # | Change | Status | Where / notes |
|---|---|---|---|
| 1 | Rebuilt stream picker: parsed fields, user rules, device-aware ranking, auto-play best match with other sources one press away | 🟡 | ✅ `core/streams/ranking/` (`StreamTitleParser`, `StreamRanker`, `AndroidDeviceDecodeCapabilities`), "Best match" auto-play mode (default), rule settings in Playback → Stream selection, parsed chips on source cards, ranked "All" list on the Sources screen and in the player's source panel with the reason Best match passed over a stream, audio-aware ranking (TrueHD/DTS-HD skipped without passthrough or an FFmpeg decoder), parsed descriptors cached per stream text. One rules model for every source (`StreamRankingRules`: resolution, size, codec, HDR/DV, audio, languages, release groups, result limits; the old debrid-only rules are migrated once by `core/debrid/DebridRulesMigration.kt`) that holds in every auto-play mode, and a "Prefer this next time" prompt in the player after picking a stream over Best match's choice (`PreferNextTime.kt`). Next: parser fixtures from real addon output (#8). |
| 2 | Configure the TV from a phone | ✅ | Settings → Configure from phone: one QR code for a hub (`core/server/PhoneHubServer.kt`) with a remote and keyboard, account sign-ins (debrid keys, MDBList, Trakt, Simkl, Jellyfin/Emby), stream ranking rules, debrid templates, badges and posters. Addons, catalogs, collections and plugin repos keep their per-screen QR codes because changes are confirmed on the TV. All servers need a one-time pairing token and only answer the LAN; see `docs/architecture/phone-config-security.md`. |
| 3 | Local-first, private, optional self-hosted sync | 🟡 | ✅ Sentry removed, no hosted backend, local-only by default, issue reports disabled unless a builder sets an endpoint. Sync still speaks the upstream Supabase protocol; a Cataloggy adapter (addon-format watch state) is the next step. |
| 4 | Jellyfin and Plex as first-class sources | ✅ | Jellyfin, Emby and Plex (`data/mediaserver/plex/`, plex.tv PIN sign-in with a QR code, server discovery through plex.tv) share one `ServerProvider` model: library rows and a server Continue Watching row on Home, search, stream lists where an owned file outranks streamed results under Best match, two-way watched state and progress. With Import watch progress on, titles in progress on the server reach Fetch's Continue Watching on start without being opened first (`ServerUserStateProjection.importResume`). |
| 5 | Progressive, visible addon results | ✅ | Parallel, progressive stream loading (`data/repository/StreamRepositoryImpl.kt`), per-addon 25 s timeout, health line on each addon card (`core/streams/AddonHealthTracker.kt`). Next: make the timeout configurable and persist health across restarts. |
| 6 | Customizable home screen | 🟡 | Reorder and hide rows (`ui/screens/addon/CatalogOrderScreen.kt`), custom rows from TMDB/Trakt/addon filters (`ui/screens/collection/`). Next: pin rows, and filter rows over the user's own Jellyfin library ("unwatched sci-fi"). |
| 7 | Simple vs advanced settings | ✅ | `ExperienceMode` ESSENTIAL / ADVANCED (`docs/essential-mode.md`). |
| 8 | Fetch's own look and one Home layout | 🟡 | ✅ One Home layout (Modern; Classic and Grid removed, landscape cards by default), Fetch theme and type (`ui/theme/`, Schibsted Grotesk + Bricolage Grotesque, OFL texts in `docs/third-party/fonts/`), a thicker focus ring and tighter card corners, no blur behind the source list, Play button that names the Best match pick (`core/streams/BestMatchPreviewer.kt`), a live "Fetching" panel with Play now / See all sources on the auto-play loader (`ui/screens/stream/FetchingPanel.kt`), parsed chips on source cards, and a first run that pairs the phone code with the Essential / Advanced choice. One floating sidebar of round icons (`ui/components/FloatingSidebarButton.kt`) replaces Nuvio's classic and blurred "modern" sidebars; Haze is no longer a dependency. Next: show the Best match pick on the Home hero for Continue Watching items without querying addons on every focus move. |

## Follow-ups from the fork

- **Media3 and QuickJS forks.** Replaced with stock Media3 1.8.0 and the published
  quickjs-kt 1.0.5 (#1). What was dropped and what to re-check on devices is in
  `docs/architecture/media3-fork.md`.
- **Torrent engine.** `core/torrent/TorrentService.kt` keeps the upstream API without an
  engine. An open-source engine (e.g. libtorrent4j) can be plugged in there.
- **Translations.** Strings whose meaning changed (sync, licenses) fall back to English in
  other locales until re-translated. Strings for removed features can be pruned.
- `TraktAuthServiceTest` needs Trakt client credentials and is skipped without them.

## Tracking

Open work lives in GitHub issues. `roadmap` = planned work, `upstream-bug` = bugs inherited
from NuvioTV (grouped by area, each linking the original reports).

| Area | Issues |
|---|---|
| Stream picker / addons | #8 follow-ups, #9 addon health, #15 torrent engine |
| Player | #1 replace Media3 fork, #17 DV/HDR, #18 refresh rate & judder, #19 playback failures, #20 subtitles, #21 controls & episode flow |
| Watch state | #11 skip intro markers, #22 Continue Watching / Trakt / Simkl |
| Sources | #3 Plex, #12 more debrid, #25 media servers & TMDB |
| Privacy & sync | #4 self-hosted sync (Cataloggy) |
| Phone & profiles | #5 phone config & remote, #6 parental controls |
| Home & navigation | #7 pinned and filter rows, #23 focus, #24 hero/trailers/ambient |
| Platform | #13 low-end performance, #14 first release, #16 translations |

Upstream bugs not tracked because the feature was removed in Fetch:
NuvioMedia/NuvioTV#4120 (contributors list), NuvioMedia/NuvioTV#3651 (Nuvio account),
NuvioMedia/NuvioTV#4110 (Nuvio global sync of catalog order).
