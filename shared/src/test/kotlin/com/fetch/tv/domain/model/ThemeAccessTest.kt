package com.fetch.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun everyThemeIsAvailableOnceInPickerOrder() {
        val themes = availableAppThemes()

        assertEquals(
            listOf(AppTheme.GOLD, AppTheme.JADE, AppTheme.ROSE_GOLD, AppTheme.ARCTIC_BLUE, AppTheme.GRAPHITE),
            themes.take(5)
        )
        assertEquals(AppTheme.CUSTOM, themes[5])
        assertEquals(listOf(DEFAULT_APP_THEME, AppTheme.WHITE), themes.subList(6, 8))
        assertEquals(themes.size, themes.distinct().size)
        assertEquals(AppTheme.entries.toSet(), themes.toSet())
    }

    @Test
    fun unsetThemeDefaultsToGold() {
        assertEquals(AppTheme.GOLD, resolveAppTheme(null))
    }

    @Test
    fun explicitThemeSelectionIsPreserved() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, resolveAppTheme(theme))
        }
    }
}
