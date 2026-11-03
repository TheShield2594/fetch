package com.fetch.desktop.data

import com.fetch.desktop.SettingsPage
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.ExperienceMode
import kotlinx.serialization.Serializable

/**
 * The TV app's `experience_settings`: Essential or Advanced, and whether Essential's addon setup
 * was put off with "Continue for now". Synced with the TV under its keys (`mode`,
 * `addon_setup_skipped`).
 *
 * [mode] is null only on a fresh install (or a new profile) until the first-run wizard picks one.
 * A settings file from before the mode existed has no `experience` and reads as Advanced (see
 * [DesktopSettings.experience]), so existing users keep every setting, as on TV.
 */
@Serializable
data class ExperienceSettings(
    val mode: ExperienceMode? = null,
    val addonSetupSkipped: Boolean = false
) {
    val isEssential: Boolean get() = mode == ExperienceMode.ESSENTIAL

    companion object {
        /** What a fresh install starts with: no mode yet, so the first-run wizard shows. */
        val Fresh = ExperienceSettings(mode = null)
        val Advanced = ExperienceSettings(mode = ExperienceMode.ADVANCED)
    }
}

/**
 * What Essential mode hides, in one place (the TV's `visibleSettingsCategories` and
 * `docs/essential-mode.md`). Essential never changes a saved value; it only hides the controls.
 */
object ExperiencePolicy {
    /** Playback's separate pages, which Essential folds into one Playback basics page. */
    val playbackPages = setOf(
        SettingsPage.PLAYER, SettingsPage.STREAM_SELECTION, SettingsPage.UP_NEXT,
        SettingsPage.AUDIO, SettingsPage.SUBTITLES, SettingsPage.SKIP_SEGMENTS
    )

    /**
     * Pages Essential shows with only their basic rows (Layout, Appearance, TMDB, MDBList, About);
     * the others it shows show everything.
     */
    val reducedPages = setOf(SettingsPage.LAYOUT, SettingsPage.APPEARANCE, SettingsPage.TMDB, SettingsPage.MDBLIST, SettingsPage.ABOUT)

    /** Settings pages left out of the rail (and of search) in [mode]. */
    fun hiddenPages(mode: ExperienceMode?): Set<SettingsPage> =
        if (mode == ExperienceMode.ESSENTIAL) {
            playbackPages + SettingsPage.STREAM_BADGES + SettingsPage.YOUTUBE
        } else {
            setOf(SettingsPage.PLAYBACK_BASICS)
        }

    fun isVisible(page: SettingsPage, mode: ExperienceMode?): Boolean =
        page !in hiddenPages(mode) && page.parent?.let { it !in hiddenPages(mode) } != false

    /** Where a hidden page sends you instead (a Playback page opens Playback basics in Essential). */
    fun substitute(page: SettingsPage, mode: ExperienceMode?): SettingsPage = when {
        isVisible(page, mode) -> page
        mode == ExperienceMode.ESSENTIAL && (page in playbackPages || page == SettingsPage.STREAM_BADGES) -> SettingsPage.PLAYBACK_BASICS
        mode != ExperienceMode.ESSENTIAL && page == SettingsPage.PLAYBACK_BASICS -> SettingsPage.PLAYER
        else -> SettingsPage.ADVANCED
    }

    /** Addon order is an Advanced control, as on TV; installing and removing stay. */
    fun canReorderAddons(mode: ExperienceMode?): Boolean = mode != ExperienceMode.ESSENTIAL
}

/** The TV's `AddonManagementAccess`: who may change the addon list. */
object AddonManagementAccess {
    /** A profile using the main profile's addons only reads them; the main profile changes them. */
    fun isReadOnly(profile: DesktopProfile?): Boolean = profile?.let { !it.isPrimary && it.usesPrimaryAddons } == true
}

/** An addon that answers `stream` requests, so titles can play. */
fun Addon.givesStreams(): Boolean = enabled && resources.any { it.name == "stream" }

/**
 * Essential's addon setup ("Set up addons") shows when nothing can play yet: Essential mode, the
 * addons have loaded, none of them gives streams, and it wasn't put off. The TV checks for no
 * addons at all; desktop starts with Cinemeta and OpenSubtitles, which play nothing, so it looks
 * for a stream addon instead. A profile that can't change addons never sees it.
 */
fun needsEssentialAddonSetup(
    experience: ExperienceSettings,
    addons: List<Addon>,
    addonsLoading: Boolean,
    readOnly: Boolean
): Boolean = experience.isEssential && !experience.addonSetupSkipped && !addonsLoading && !readOnly && addons.none { it.givesStreams() }
