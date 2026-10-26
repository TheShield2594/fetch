package com.fetch.tv.ui.screens.settings

import com.fetch.tv.R

/**
 * What settings search looks through in Essential mode: the rows Essential mode's pages show,
 * about 25 settings in all. Pages Essential mode shows in full use [settingsSearchIndex]; the
 * others list only the rows they keep. SettingsEssentialIndexTest checks every entry is a row
 * its page has in Advanced mode too, so a renamed or removed row can't linger here.
 */
internal val essentialSettingsSearchIndex: Map<SettingsCategory, List<Int>> =
    settingsSearchIndex.filterKeys { category ->
        visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = true,
            isDebugBuild = false,
            isWatchPartyAvailable = true,
            isYouTubeAvailable = true
        ).contains(category)
    } + mapOf(
        SettingsCategory.LAYOUT to listOf(
            R.string.layout_title,
            R.string.layout_cw_enabled,
            R.string.layout_cw_sort_mode,
            R.string.layout_landscape_posters,
            R.string.audio_autoplay_trailers,
            R.string.layout_hide_unreleased,
            R.string.layout_show_discover,
            R.string.layout_discover_location_action
        ),
        SettingsCategory.APPEARANCE to listOf(
            R.string.appearance_title,
            R.string.appearance_color_theme,
            R.string.ambient_mode_title,
            R.string.appearance_font_and_language,
            R.string.appearance_font,
            R.string.appearance_language
        ),
        SettingsCategory.TMDB to listOf(
            R.string.tmdb_title,
            R.string.tmdb_enable_title
        ),
        SettingsCategory.MDBLIST to listOf(
            R.string.mdblist_title,
            R.string.mdblist_enable_title,
            R.string.mdblist_api_key_title
        ),
        SettingsCategory.TRACKING to listOf(
            R.string.settings_tracking_title,
            R.string.tracking_accounts_title,
            R.string.trakt_name,
            R.string.simkl_name,
            R.string.mdblist_name
        ),
        SettingsCategory.ADVANCED to listOf(
            R.string.settings_advanced,
            R.string.advanced_confirm_exit
        ),
        SettingsCategory.ABOUT to listOf(
            R.string.about_title,
            R.string.about_privacy_policy,
            R.string.about_licenses_attributions,
            R.string.about_check_updates
        )
    )
