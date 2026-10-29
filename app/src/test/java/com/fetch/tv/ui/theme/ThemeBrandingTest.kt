package com.fetch.tv.ui.theme

import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBrandingTest {
    @Test
    fun colouredThemesUseMatchingWordmarks() {
        val expectedWordmarks = mapOf(
            AppTheme.GOLD to R.drawable.app_logo_wordmark_gold,
            AppTheme.JADE to R.drawable.app_logo_wordmark_jade,
            AppTheme.ROSE_GOLD to R.drawable.app_logo_wordmark_rose_gold,
            AppTheme.ARCTIC_BLUE to R.drawable.app_logo_wordmark_arctic_blue,
            AppTheme.GRAPHITE to R.drawable.app_logo_wordmark_graphite,
            AppTheme.CRIMSON to R.drawable.app_logo_wordmark_crimson,
            AppTheme.OCEAN to R.drawable.app_logo_wordmark_ocean,
            AppTheme.VIOLET to R.drawable.app_logo_wordmark_violet,
            AppTheme.EMERALD to R.drawable.app_logo_wordmark_emerald,
            AppTheme.AMBER to R.drawable.app_logo_wordmark_amber,
            AppTheme.ROSE to R.drawable.app_logo_wordmark_rose
        )

        expectedWordmarks.forEach { (theme, wordmark) ->
            assertEquals(theme.name, wordmark, theme.brandWordmarkResource)
        }
    }

    @Test
    fun neutralAndCustomThemesUseTheWhiteWordmark() {
        listOf(AppTheme.FETCH, AppTheme.WHITE, AppTheme.SIGNAL, AppTheme.CUSTOM).forEach { theme ->
            assertEquals(theme.name, R.drawable.app_logo_wordmark, theme.brandWordmarkResource)
        }
    }
}
