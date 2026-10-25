package com.fetch.tv.ui.screens.settings

import com.fetch.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps settings search complete: every `title = stringResource(...)` on a page's files, and every
 * section title in the Playback and Layout models, must be in [settingsSearchIndex] under that
 * page, and the index must hold nothing else.
 */
class SettingsSearchIndexTest {

    private val screens = File("src/main/java/com/fetch/tv/ui/screens")

    private val pageFiles: Map<SettingsCategory, List<String>> = mapOf(
        SettingsCategory.PROFILES to listOf("settings/ProfileSettingsContent.kt"),
        SettingsCategory.WATCH_PARTY to listOf("party/PartySettingsContent.kt"),
        SettingsCategory.PLAYBACK to listOf(
            "settings/PlaybackSettingsScreen.kt",
            "settings/PlaybackSettingsSections.kt",
            "settings/PlaybackAudioSettings.kt",
            "settings/PlaybackAutoPlaySettings.kt",
            "settings/PlaybackBufferNetworkSettings.kt",
            "settings/PlaybackP2pSettings.kt",
            "settings/PlaybackPlayerSettings.kt",
            "settings/PlaybackStreamRankingSettings.kt",
            "settings/PlaybackSubtitleSettings.kt",
            "settings/PlaybackVideoSettings.kt",
            "settings/EssentialPlaybackSettingsContent.kt",
            "settings/PlaybackSettingsModel.kt"
        ),
        SettingsCategory.ANIME_SKIP to listOf("settings/AnimeSkipSettingsScreen.kt"),
        SettingsCategory.CONTENT_DISCOVERY to listOf(
            "settings/ContentDiscoverySettingsContent.kt",
            "settings/MediaServersSettingsScreen.kt",
            "settings/MediaServerSettingsScreen.kt"
        ),
        SettingsCategory.TMDB to listOf("settings/TmdbSettingsScreen.kt"),
        SettingsCategory.MDBLIST to listOf("settings/MDBListSettingsScreen.kt"),
        SettingsCategory.APPEARANCE to listOf(
            "settings/ThemeSettingsScreen.kt",
            "settings/AmbientModeSettings.kt",
            "settings/AppIconPicker.kt",
            "settings/CustomThemeEditor.kt"
        ),
        SettingsCategory.LAYOUT to listOf(
            "settings/LayoutSettingsScreen.kt",
            "settings/LayoutCardSettings.kt",
            "settings/LayoutDetailSettings.kt",
            "settings/LayoutHomeSettings.kt",
            "settings/LayoutSettingsModel.kt"
        ),
        SettingsCategory.ACCOUNT to listOf("account/AccountSettingsContent.kt"),
        SettingsCategory.TRACKING to listOf("settings/TrackingSettingsScreen.kt"),
        SettingsCategory.YOUTUBE to listOf("settings/YouTubeSettingsScreen.kt"),
        SettingsCategory.PHONE to listOf("phone/PhoneHubSettingsContent.kt"),
        SettingsCategory.ADVANCED to listOf("settings/NetworkSettingsScreen.kt", "settings/SkipMarkerSettings.kt"),
        SettingsCategory.ABOUT to listOf("settings/AboutScreen.kt", "settings/UpdateChannelSettings.kt")
    )

    @Test
    fun `the index holds exactly the titles each page shows`() {
        val problems = mutableListOf<String>()
        val stringIds = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        pageFiles.forEach { (category, files) ->
            val expected = files.flatMap { titleIds(File(screens, it).readText()) }.toSet()
            val indexed = settingsSearchIndex[category].orEmpty().mapNotNull { stringIds[it] }.toSet()
            (expected - indexed).forEach { problems += "$category is missing R.string.$it" }
            (indexed - expected).forEach { problems += "$category lists R.string.$it, which none of its files show" }
        }
        if (problems.isNotEmpty()) {
            fail("settingsSearchIndex is out of date:\n" + problems.joinToString("\n"))
        }
    }

    @Test
    fun `every page in the rail can be searched, and only those`() {
        val searchable = SettingsCategory.entries.toSet() -
            setOf(SettingsCategory.SEARCH, SettingsCategory.DEBUG, SettingsCategory.EXPERIENCE)
        assertEquals(searchable, settingsSearchIndex.keys)
        assertEquals(searchable, pageFiles.keys)
    }

    /** The string ids in each `title = stringResource(...)` call, and the first id of each enum entry. */
    private fun titleIds(source: String): List<String> {
        val ids = mutableListOf<String>()
        Regex("""\btitle\s*=\s*stringResource\(""").findAll(source).forEach { match ->
            var depth = 1
            var end = match.range.last + 1
            while (depth > 0) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                end++
            }
            ids += STRING_ID.findAll(source.substring(match.range.last + 1, end)).map { it.groupValues[1] }
        }
        ids += ENUM_TITLE.findAll(source).map { it.groupValues[1] }
        return ids
    }

    private companion object {
        val STRING_ID = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        val ENUM_TITLE = Regex("""(?m)^\s*[A-Z_]+\(\s*R\.string\.([A-Za-z0-9_]+)""")
    }
}
