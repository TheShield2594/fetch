package com.fetch.tv.ui.theme

import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBrandingTest {
    @Test
    fun colouredThemesUseMatchingWordmarks() {
        val expectedWordmarks = mapOf(
            AppTheme.OCEAN to R.drawable.app_logo_wordmark_ocean,
            AppTheme.EMERALD to R.drawable.app_logo_wordmark_emerald,
            AppTheme.AMBER to R.drawable.app_logo_wordmark_amber
        )

        expectedWordmarks.forEach { (theme, wordmark) ->
            assertEquals(theme.name, wordmark, theme.brandWordmarkResource)
        }
    }

    @Test
    fun neutralAndCustomThemesUseTheWhiteWordmark() {
        listOf(AppTheme.FETCH, AppTheme.WHITE, AppTheme.CUSTOM).forEach { theme ->
            assertEquals(theme.name, R.drawable.app_logo_wordmark, theme.brandWordmarkResource)
        }
    }
}
