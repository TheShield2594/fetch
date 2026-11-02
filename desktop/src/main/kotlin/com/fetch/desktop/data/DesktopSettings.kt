package com.fetch.desktop.data

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.domain.model.AppTheme
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything the Settings page changes, saved as `settings.json`. Field names follow the TV
 * app's settings so the two read the same.
 */
@Serializable
data class DesktopSettings(
    /** How a stream is picked when you press Play, shared with the TV app's ranker. */
    val rankingRules: StreamRankingRules = DesktopDefaults.rankingRules,
    /** Accent theme, by [AppTheme] name. */
    val theme: String = AppTheme.FETCH.name,
    val showContinueWatching: Boolean = true,
    /** "Show Catalog Type": the " - Movies" suffix on row titles. */
    val showCatalogType: Boolean = true,
    /** "Show Addon Name" beside each row title. */
    val showAddonName: Boolean = true,
    /** "Show Poster Labels": the title and year under posters. */
    val showPosterLabels: Boolean = true,
    /** "Discover Location": inside Search (the TV default) or its own sidebar entry. */
    val discoverInSidebar: Boolean = false,
    /** Home catalogs by row key: pinned ones lead in pin order, disabled ones are hidden. */
    val pinnedCatalogs: List<String> = emptyList(),
    val disabledCatalogs: List<String> = emptyList(),
    /** The catalog Discover last showed, restored on the next visit. */
    val discoverCatalog: String? = null
) {
    val appTheme: AppTheme get() = AppTheme.entries.firstOrNull { it.name == theme } ?: AppTheme.FETCH
}

object DesktopDefaults {
    /**
     * The TV defaults, except that the desktop can't tell what a stream will decode to (mpv
     * plays nearly anything), so it never drops a stream as undecodable or for being from a
     * media server it doesn't have.
     */
    val rankingRules = StreamRankingRules(dropUndecodable = false, preferOwnLibrary = false)
}

fun settingsFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "settings.json"), DesktopSettings.serializer()) { DesktopSettings() }
