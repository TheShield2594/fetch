package com.fetch.tv.ui.screens.settings

import com.fetch.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SettingsChangeIndexTest {

    private val stringIds = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    @Test
    fun `every player and P2P rule points at a row its page lists`() {
        val problems = (playerSettingsChangeRules.map { it.key } + torrentSettingsChangeRules.map { it.key })
            .filterNot { key -> key.title in settingsSearchIndex[key.category].orEmpty() }
            .map { key -> "${key.category} has no R.string.${stringIds[key.title]}" }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `no row has two rules`() {
        val keys = playerSettingsChangeRules.map { it.key } + torrentSettingsChangeRules.map { it.key }
        assertEquals(keys.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<SettingsSearchKey>())
    }

    @Test
    fun `every row the view model tracks is a searchable title`() {
        // The view model's rows are built from injected stores, so check its source instead.
        val source = File(screens, "SettingsChangedViewModel.kt").readText()
        val indexed = settingsSearchIndex.values.flatten().mapNotNull { stringIds[it] }.toSet()
        val missing = Regex("""R\.string\.([A-Za-z0-9_]+)""").findAll(source)
            .map { it.groupValues[1] }
            .filterNot { it in indexed }
            .toList()
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `a rule compares only its own part of the settings`() {
        val rule = SettingsChangeRule<Pair<Int, Int>>(SettingsSearchKey(SettingsCategory.TMDB, 0)) { it.first }

        assertFalse(rule.isChanged(current = 1 to 5, default = 1 to 9))
        assertTrue(rule.isChanged(current = 2 to 9, default = 1 to 9))
    }

    private companion object {
        val screens = File("src/main/java/com/fetch/tv/ui/screens/settings")
    }
}
