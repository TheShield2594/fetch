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
    val discoverCatalog: String? = null,
    /** Audio, Subtitles, Player and Up Next: how mpv is started. */
    val player: PlayerSettings = PlayerSettings(),
    /** Bumped when a saved default has to change; see [migrated]. */
    val schema: Int = 1
) {
    val appTheme: AppTheme get() = AppTheme.entries.firstOrNull { it.name == theme } ?: AppTheme.FETCH

    /**
     * Brings an older settings file up to date. Schema 2: desktop used to save "prefer my own
     * library" off because it had no media servers; now it has them, so it starts on, as on TV.
     */
    fun migrated(): DesktopSettings {
        var s = this
        if (s.schema < 2) s = s.copy(rankingRules = s.rankingRules.copy(preferOwnLibrary = true), schema = 2)
        return s
    }
}

object DesktopDefaults {
    /**
     * The TV defaults, except that the desktop can't tell what a stream will decode to (mpv
     * plays nearly anything), so it never drops a stream as undecodable.
     */
    val rankingRules = StreamRankingRules(dropUndecodable = false)
}

fun settingsFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "settings.json"), DesktopSettings.serializer()) { DesktopSettings() }

enum class SubtitleMode(val label: String) {
    /** mpv picks a track in the preferred language, or none. */
    AUTO("When available"),
    /** Only when the audio isn't in the subtitle language (foreign films, anime). */
    FOREIGN_AUDIO("Only for other languages"),
    OFF("Off")
}

enum class HwDec(val mpvValue: String, val label: String) {
    AUTO_SAFE("auto-safe", "Auto (auto-safe)"),
    AUTO_COPY("auto-copy", "Hardware (copy)"),
    OFF("no", "Off (software)")
}

/**
 * The TV app's Audio, Subtitles, Player and Up Next settings that make sense for mpv. Languages
 * are ISO 639-1 codes; null means the file's default.
 */
@Serializable
data class PlayerSettings(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    /** "Forward subtitles to external player": addon subtitles in the preferred language. */
    val forwardAddonSubtitles: Boolean = true,
    /** mpv's --sub-scale. */
    val subtitleScale: Double = 1.0,
    /** A dark box behind subtitles instead of an outline. */
    val subtitleBackground: Boolean = false,
    val hwdec: HwDec = HwDec.AUTO_SAFE,
    /** Pass AC-3, E-AC-3, DTS, DTS-HD and TrueHD to the receiver undecoded. */
    val audioPassthrough: Boolean = false,
    val fullscreen: Boolean = true,
    /** Up Next: when an episode plays to its end, start the next one's best match. */
    val autoplayNextEpisode: Boolean = true,
    /** Extra mpv options, space-separated, for anything not covered here. */
    val extraMpvArgs: String = ""
)
