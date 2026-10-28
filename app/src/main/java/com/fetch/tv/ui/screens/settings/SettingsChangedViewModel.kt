package com.fetch.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.R
import com.fetch.tv.data.local.AddonTimeoutPreferences
import com.fetch.tv.data.local.AmbientModeDataStore
import com.fetch.tv.data.local.AnimeSkipSettingsDataStore
import com.fetch.tv.data.local.DeviceLocalPlayerPreferences
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.StreamBadgeSettingsDataStore
import com.fetch.tv.data.local.ThemeDataStore
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.local.TrailerSettingsDataStore
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.YouTubeSettingsDataStore
import com.fetch.tv.data.simkl.SimklAnimeIdPreference
import com.fetch.tv.domain.model.AppFont
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A searchable settings row: its page and its title, which is also the row's key. */
internal data class SettingsSearchKey(val category: SettingsCategory, @StringRes val title: Int)

/**
 * Which settings rows the active profile has moved off their default, for the search filter. Rows
 * with no stored value of their own (headers, actions, sign-ins, API keys) are never listed, nor
 * are the few whose value isn't a plain setting: app icon, language, media servers, RGB565 and the
 * update channel.
 */
@HiltViewModel
class SettingsChangedViewModel @Inject constructor(
    playerSettings: PlayerSettingsDataStore,
    deviceLocalPlayer: DeviceLocalPlayerPreferences,
    layout: LayoutPreferenceDataStore,
    trailers: TrailerSettingsDataStore,
    streamBadges: StreamBadgeSettingsDataStore,
    theme: ThemeDataStore,
    ambientMode: AmbientModeDataStore,
    tmdb: TmdbSettingsDataStore,
    mdbList: MDBListSettingsDataStore,
    trakt: TraktSettingsDataStore,
    youTube: YouTubeSettingsDataStore,
    animeSkip: AnimeSkipSettingsDataStore,
    profiles: ProfileDataStore,
    addonTimeouts: AddonTimeoutPreferences
) : ViewModel() {

    private val sources: List<Flow<Set<SettingsSearchKey>>> = buildList {
        add(playerSettings.playerSettings.changedRows(playerSettings.defaultPlayerSettings, playerSettingsChangeRules))
        add(
            deviceLocalPlayer.transparentLetterbox.changedRow(
                SettingsSearchKey(SettingsCategory.PLAYBACK_VIDEO, R.string.playback_true_black_letterbox),
                DeviceLocalPlayerPreferences.TRANSPARENT_LETTERBOX_DEFAULT
            )
        )

        fun <T> layoutRow(category: SettingsCategory, @StringRes title: Int, flow: Flow<T>) =
            add(flow.changedRow(SettingsSearchKey(category, title), layout.defaultOf(flow)))
        val home = SettingsCategory.LAYOUT
        layoutRow(home, R.string.layout_cw_enabled, layout.continueWatchingEnabled)
        layoutRow(home, R.string.layout_use_episode_thumbnails_cw, layout.useEpisodeThumbnailsInCw)
        layoutRow(home, R.string.layout_blur_cw_next_up, layout.blurContinueWatchingNextUp)
        layoutRow(home, R.string.layout_next_up_furthest_episode, layout.nextUpFromFurthestEpisode)
        layoutRow(home, R.string.layout_show_unaired_next_up, layout.showUnairedNextUp)
        layoutRow(home, R.string.layout_cw_sort_mode, layout.continueWatchingSortMode)
        layoutRow(home, R.string.layout_expand_poster, layout.focusedPosterBackdropExpandEnabled)
        layoutRow(home, R.string.layout_expand_delay, layout.focusedPosterBackdropExpandDelaySeconds)
        layoutRow(home, R.string.layout_autoplay_trailer, layout.focusedPosterBackdropTrailerEnabled)
        layoutRow(home, R.string.layout_trailer_muted, layout.focusedPosterBackdropTrailerMuted)
        layoutRow(home, R.string.layout_trailer_crop_to_fill, layout.trailerCropToFill)
        layoutRow(home, R.string.layout_card_width, layout.posterCardWidthDp)
        layoutRow(home, R.string.layout_card_radius, layout.posterCardCornerRadiusDp)
        layoutRow(home, R.string.layout_landscape_posters, layout.modernLandscapePostersEnabled)
        layoutRow(home, R.string.layout_always_show_landscape_clearlogo, layout.alwaysShowLandscapeClearlogo)
        layoutRow(home, R.string.layout_custom_poster_qr, layout.customPosterUrlPattern)
        layoutRow(home, R.string.layout_episode_options_overlay, layout.episodeOptionsOverlayStyle)
        layoutRow(home, R.string.random_episode_title, layout.randomEpisodeEnabled)
        layoutRow(home, R.string.layout_blur_unwatched, layout.blurUnwatchedEpisodes)
        layoutRow(home, R.string.layout_episode_ratings, layout.detailImdbRatingsVisibility)
        layoutRow(home, R.string.layout_trailer_button, layout.detailPageTrailerButtonEnabled)
        layoutRow(home, R.string.layout_prefer_external_meta, layout.preferExternalMetaAddonDetail)
        layoutRow(home, R.string.layout_show_full_release_date, layout.showFullReleaseDate)
        layoutRow(home, R.string.layout_fullscreen_hero_backdrop, layout.modernHeroFullScreenBackdropEnabled)
        layoutRow(home, R.string.layout_poster_labels, layout.posterLabelsEnabled)
        layoutRow(home, R.string.layout_addon_name, layout.catalogAddonNameEnabled)
        layoutRow(home, R.string.layout_catalog_type, layout.catalogTypeSuffixEnabled)
        layoutRow(home, R.string.layout_hide_unreleased, layout.hideUnreleasedContent)
        layoutRow(home, R.string.layout_overall_ratings, layout.homeImdbRatingsVisibility)
        layoutRow(home, R.string.layout_collapse_sidebar, layout.sidebarCollapsedByDefault)
        layoutRow(home, R.string.layout_show_discover, layout.discoverLocation)
        layoutRow(home, R.string.layout_discover_location_action, layout.discoverLocation)
        val advanced = SettingsCategory.ADVANCED
        layoutRow(advanced, R.string.advanced_fast_horizontal_navigation, layout.fastHorizontalNavigationEnabled)
        layoutRow(advanced, R.string.advanced_fetch_focus_scroll, layout.smoothBringIntoViewEnabled)

        add(
            trailers.settings.changedRows(
                trailers.defaultSettings,
                listOf(
                    rule(home, R.string.audio_autoplay_trailers) { it.enabled },
                    rule(home, R.string.layout_trailer_play_in_background) { it.playInBackground },
                    rule(home, R.string.layout_trailer_pause_on_scroll) { it.pauseOnScroll },
                    rule(home, R.string.audio_trailer_delay) { it.delaySeconds },
                    rule(home, R.string.layout_trailer_4k) { it.allow4k }
                )
            )
        )
        add(
            streamBadges.settings.changedRows(
                streamBadges.defaultSettings,
                listOf(
                    rule(home, R.string.settings_stream_size_badges_title) { it.showFileSizeBadges },
                    rule(home, R.string.settings_stream_badge_position_title) { it.badgePlacement },
                    rule(home, R.string.settings_stream_addon_logo_title) { it.showAddonLogo }
                )
            )
        )

        val appearance = SettingsCategory.APPEARANCE
        // The theme a profile hasn't picked is stored as none; the font, contrast and AMOLED flags as below.
        add(theme.themeSelection.map { it.theme }.changedRow(SettingsSearchKey(appearance, R.string.appearance_color_theme), null))
        add(theme.selectedFont.changedRow(SettingsSearchKey(appearance, R.string.appearance_font), AppFont.SCHIBSTED_GROTESK))
        add(theme.highContrast.changedRow(SettingsSearchKey(appearance, R.string.appearance_high_contrast), false))
        add(theme.amoledMode.changedRow(SettingsSearchKey(appearance, R.string.appearance_amoled_mode), false))
        add(theme.amoledSurfacesMode.changedRow(SettingsSearchKey(appearance, R.string.appearance_amoled_surfaces_mode), false))
        add(
            ambientMode.settings.changedRows(
                ambientMode.defaultSettings,
                listOf(
                    rule(appearance, R.string.ambient_mode_title) { it.enabled },
                    rule(appearance, R.string.ambient_mode_timeout) { it.timeoutMinutes },
                    rule(appearance, R.string.ambient_mode_catalogs) { it.catalogKeys }
                )
            )
        )

        val tmdbPage = SettingsCategory.TMDB
        add(
            tmdb.settings.changedRows(
                tmdb.defaultSettings,
                listOf(
                    rule(tmdbPage, R.string.tmdb_enable_title) { it.enabled },
                    rule(tmdbPage, R.string.tmdb_modern_home_title) { it.modernHomeEnabled },
                    rule(tmdbPage, R.string.tmdb_enrich_continue_watching_title) { it.enrichContinueWatching },
                    rule(tmdbPage, R.string.tmdb_language_title) { it.language },
                    rule(tmdbPage, R.string.tmdb_artwork_title) { it.useArtwork },
                    rule(tmdbPage, R.string.tmdb_basic_info_title) { it.useBasicInfo },
                    rule(tmdbPage, R.string.tmdb_details_title) { it.useDetails },
                    rule(tmdbPage, R.string.tmdb_credits_title) { it.useCredits },
                    rule(tmdbPage, R.string.tmdb_productions_title) { it.useProductions },
                    rule(tmdbPage, R.string.tmdb_networks_title) { it.useNetworks },
                    rule(tmdbPage, R.string.tmdb_episodes_title) { it.useEpisodes },
                    rule(tmdbPage, R.string.tmdb_trailers_title) { it.useTrailers },
                    rule(tmdbPage, R.string.tmdb_more_like_this_title) { it.useMoreLikeThis },
                    rule(tmdbPage, R.string.tmdb_collections_title) { it.useCollections }
                )
            )
        )
        add(
            mdbList.settings.changedRows(
                mdbList.defaultSettings,
                listOf(
                    rule(SettingsCategory.MDBLIST, R.string.mdblist_enable_title) { it.enabled },
                    rule(SettingsCategory.MDBLIST, R.string.mdblist_show_on_hero_title) { it.showOnHero }
                )
            )
        )

        val tracking = SettingsCategory.TRACKING
        add(trakt.librarySourceMode.changedRow(SettingsSearchKey(tracking, R.string.trakt_library_source_title), TraktSettingsDataStore.DEFAULT_LIBRARY_SOURCE_MODE))
        add(trakt.watchProgressSource.changedRow(SettingsSearchKey(tracking, R.string.trakt_watch_progress_title), TraktSettingsDataStore.DEFAULT_WATCH_PROGRESS_SOURCE))
        add(trakt.moreLikeThisSource.changedRow(SettingsSearchKey(tracking, R.string.trakt_more_like_this_source_title), TraktSettingsDataStore.DEFAULT_MORE_LIKE_THIS_SOURCE))
        add(trakt.continueWatchingDaysCap.changedRow(SettingsSearchKey(tracking, R.string.trakt_continue_watching_window), TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP))
        add(trakt.showMetaComments.changedRow(SettingsSearchKey(tracking, R.string.trakt_comments_title), TraktSettingsDataStore.DEFAULT_SHOW_META_COMMENTS))
        add(trakt.simklAnimeIdPreference.changedRow(SettingsSearchKey(tracking, R.string.tracking_simkl_anime_id_title), SimklAnimeIdPreference.DEFAULT))

        val youTubePage = SettingsCategory.YOUTUBE
        add(youTube.sponsorBlockEnabled.changedRow(SettingsSearchKey(youTubePage, R.string.youtube_sponsor_block_title), YouTubeSettingsDataStore.DEFAULT_SPONSOR_BLOCK_ENABLED))
        add(youTube.autoplayEnabled.changedRow(SettingsSearchKey(youTubePage, R.string.youtube_autoplay_title), YouTubeSettingsDataStore.DEFAULT_AUTOPLAY_ENABLED))
        add(youTube.watchHistoryEnabled.changedRow(SettingsSearchKey(youTubePage, R.string.youtube_watch_history_title), YouTubeSettingsDataStore.DEFAULT_WATCH_HISTORY_ENABLED))
        add(
            youTube.feedFilter.changedRows(
                YouTubeSettingsDataStore.DEFAULT_FEED_FILTER,
                listOf(
                    rule(youTubePage, R.string.youtube_show_shorts_title) { it.showShorts },
                    rule(youTubePage, R.string.youtube_show_live_title) { it.showLive },
                    rule(youTubePage, R.string.youtube_hide_watched_title) { it.hideWatched }
                )
            )
        )

        add(animeSkip.enabled.changedRow(SettingsSearchKey(SettingsCategory.ANIME_SKIP, R.string.animeskip_enable_title), false))

        add(profiles.startupSplashEnabled.changedRow(SettingsSearchKey(advanced, R.string.appearance_startup_splash), true))
        add(profiles.rememberLastProfileEnabled.changedRow(SettingsSearchKey(SettingsCategory.PROFILES, R.string.profile_remember_last_profile), false))
        add(profiles.confirmExitEnabled.changedRow(SettingsSearchKey(advanced, R.string.advanced_confirm_exit), false))
        add(addonTimeouts.skipSlowAddons.changedRow(SettingsSearchKey(advanced, R.string.advanced_skip_slow_addons), false))
        add(addonTimeouts.timeoutSeconds.changedRow(SettingsSearchKey(advanced, R.string.advanced_addon_timeout), AddonTimeoutPreferences.DEFAULT_TIMEOUT_SECONDS))
        add(deviceLocalPlayer.playerStatsHudButtonEnabled.changedRow(SettingsSearchKey(advanced, R.string.advanced_player_stats_hud), false))
    }

    internal val changedSettings: StateFlow<Set<SettingsSearchKey>> =
        combine(sources) { sets -> sets.flatMapTo(HashSet()) { it } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
