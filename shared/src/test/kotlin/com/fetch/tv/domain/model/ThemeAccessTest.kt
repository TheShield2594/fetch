package com.fetch.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun everyThemeIsListedOnceWithCustomFirst() {
        val themes = availableAppThemes()

        assertEquals(
            listOf(AppTheme.CUSTOM, AppTheme.FETCH, AppTheme.WHITE, AppTheme.OCEAN, AppTheme.EMERALD, AppTheme.AMBER),
            themes
        )
        assertEquals(AppTheme.entries.toSet(), themes.toSet())
    }

    @Test
    fun aProfileWithoutAPickGetsTheDefault() {
        assertEquals(DEFAULT_APP_THEME, resolveAppTheme(null))
        assertEquals(AppTheme.FETCH, DEFAULT_APP_THEME)
        AppTheme.entries.forEach { assertEquals(it, resolveAppTheme(it)) }
    }

    @Test
    fun currentThemesReadBackByName() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromStoredName(it.name)) }
    }

    @Test
    fun retiredThemesMoveToTheNearestKeptTheme() {
        val expected = mapOf(
            "GOLD" to AppTheme.AMBER,
            "JADE" to AppTheme.EMERALD,
            "ROSE_GOLD" to AppTheme.FETCH,
            "ARCTIC_BLUE" to AppTheme.OCEAN,
            "GRAPHITE" to AppTheme.WHITE,
            "CRIMSON" to AppTheme.FETCH,
            "VIOLET" to AppTheme.OCEAN,
            "ROSE" to AppTheme.FETCH
        )
        expected.forEach { (stored, theme) -> assertEquals(stored, theme, AppTheme.fromStoredName(stored)) }
        assertEquals(expected, AppTheme.retiredThemes)
        // A retired name never shadows a current one.
        AppTheme.entries.forEach { assertNull(AppTheme.retiredThemes[it.name]) }
    }

    @Test
    fun unknownOrMissingNamesAreLeftToTheDefault() {
        assertNull(AppTheme.fromStoredName(null))
        assertNull(AppTheme.fromStoredName(""))
        assertNull(AppTheme.fromStoredName("NEON"))
        assertNull(AppTheme.fromStoredName("ocean"))
        assertEquals(DEFAULT_APP_THEME, resolveAppTheme(AppTheme.fromStoredName("NEON")))
    }
}
