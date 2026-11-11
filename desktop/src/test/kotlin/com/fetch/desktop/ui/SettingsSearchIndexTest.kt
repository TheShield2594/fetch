package com.fetch.desktop.ui

import com.fetch.desktop.SettingsPage
import com.fetch.desktop.data.ExperiencePolicy
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.domain.model.ExperienceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSearchIndexTest {

    /** Every page sub-page is reachable from a page the rail lists. */
    private fun railPages(mode: ExperienceMode) =
        SettingsPage.entries.filter { it.parent == null && it !in ExperiencePolicy.hiddenPages(mode) }

    @Test
    fun `every settings page is indexed`() {
        val indexed = settingsSearchIndex.map { it.page }.toSet()
        val missing = SettingsPage.entries.filter { it !in indexed }
        assertTrue("pages with nothing in the search index: $missing", missing.isEmpty())
    }

    @Test
    fun `every settings page is reachable in the mode that shows it`() {
        for (mode in ExperienceMode.entries) {
            val rail = railPages(mode)
            SettingsPage.entries.filter { ExperiencePolicy.isVisible(it, mode) }.forEach { page ->
                assertTrue("$page isn't reachable from the rail in $mode", page.railPage in rail)
            }
            // A page the mode hides opens one it shows instead.
            SettingsPage.entries.filterNot { ExperiencePolicy.isVisible(it, mode) }.forEach { page ->
                assertTrue("$page has no stand-in in $mode", ExperiencePolicy.isVisible(ExperiencePolicy.substitute(page, mode), mode))
            }
        }
    }

    @Test
    fun `every page Essential or Advanced shows can be found by search`() {
        for (mode in ExperienceMode.entries) {
            val found = SettingsSearch.visibleEntries(mode).map { it.page }.toSet()
            val visible = SettingsPage.entries.filter { ExperiencePolicy.isVisible(it, mode) }
            assertEquals("$mode pages search can't find", emptyList<SettingsPage>(), visible.filter { it !in found })
        }
    }

    @Test
    fun `every indexed title is a row the app shows`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val built = SegmentType.entries.map { "Skip ${it.label.lowercase()}" }.toSet()
        val stale = settingsSearchIndex.map { it.title }.distinct()
            .filter { it !in built && "\"$it\"" !in sources && "\"${it.replace("\"", "\\\"")}\"" !in sources }
        assertTrue("index titles no page shows: $stale", stale.isEmpty())
    }

    @Test
    fun `search matches titles first, then keywords`() {
        val results = SettingsSearch.search("amoled", ExperienceMode.ADVANCED)
        assertEquals("AMOLED Mode", results.first().title)
        assertTrue(results.any { it.title == "Pure Black Surfaces" })
        // Every word has to match somewhere.
        assertEquals("Audio passthrough", SettingsSearch.search("receiver dolby", ExperienceMode.ADVANCED).single().title)
        assertTrue(SettingsSearch.search("   ", ExperienceMode.ADVANCED).isEmpty())
    }

    @Test
    fun `essential search keeps to what essential shows`() {
        val essential = SettingsSearch.search("subtitle", ExperienceMode.ESSENTIAL)
        assertTrue(essential.isNotEmpty())
        assertTrue(essential.all { it.page == SettingsPage.PLAYBACK_BASICS })
        assertTrue(SettingsSearch.search("amoled", ExperienceMode.ESSENTIAL).isEmpty())
        assertTrue(SettingsSearch.search("increase contrast", ExperienceMode.ESSENTIAL).any { it.page == SettingsPage.APPEARANCE })
        assertFalse(SettingsSearch.search("subtitle", ExperienceMode.ADVANCED).any { it.page == SettingsPage.PLAYBACK_BASICS })
    }

    @Test
    fun `hidden pages leave search`() {
        val hidden = setOf(SettingsPage.YOUTUBE)
        assertTrue(SettingsSearch.search("dearrow", ExperienceMode.ADVANCED, hidden).none { it.page == SettingsPage.YOUTUBE })
        assertTrue(SettingsSearch.search("dearrow", ExperienceMode.ADVANCED).any { it.page == SettingsPage.YOUTUBE })
    }
}
