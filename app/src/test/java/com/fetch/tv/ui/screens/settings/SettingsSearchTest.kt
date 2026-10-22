package com.fetch.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val entries = listOf(
        SettingsSearchEntry(SettingsCategory.PLAYBACK, "Preferred audio language"),
        SettingsSearchEntry(SettingsCategory.PLAYBACK, "Subtitle size"),
        SettingsSearchEntry(SettingsCategory.TMDB, "Language"),
        SettingsSearchEntry(SettingsCategory.APPEARANCE, "Thème de couleur"),
        SettingsSearchEntry(SettingsCategory.TMDB, "Language")
    )

    @Test
    fun `titles that start with the query lead, then word starts, then the rest`() {
        val results = searchSettings("lang", entries)

        assertEquals(
            listOf(
                SettingsSearchEntry(SettingsCategory.TMDB, "Language"),
                SettingsSearchEntry(SettingsCategory.PLAYBACK, "Preferred audio language")
            ),
            results
        )
    }

    @Test
    fun `every word must match, in any order, ignoring case and accents`() {
        assertEquals(
            listOf("Preferred audio language"),
            searchSettings("LANGUAGE audio", entries).map { it.title }
        )
        assertEquals(listOf("Thème de couleur"), searchSettings("theme", entries).map { it.title })
    }

    @Test
    fun `a blank query or one that matches nothing lists nothing`() {
        assertTrue(searchSettings("  ", entries).isEmpty())
        assertTrue(searchSettings("bitrate", entries).isEmpty())
    }
}
