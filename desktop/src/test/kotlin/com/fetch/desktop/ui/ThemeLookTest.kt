package com.fetch.desktop.ui

import androidx.compose.ui.graphics.Color
import com.fetch.desktop.data.AppearanceSettings
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeLookTest {
    @Test
    fun `the default look is unchanged`() {
        assertEquals(SurfaceColors.Default, surfaceColors(AppTheme.FETCH, AppearanceSettings()))
    }

    @Test
    fun `amoled makes the background black, and the cards only with pure black surfaces`() {
        val amoled = surfaceColors(AppTheme.FETCH, AppearanceSettings(amoledMode = true))
        assertEquals(Color.Black, amoled.background)
        assertNotEquals(Color.Black, amoled.surface)
        val surfaces = surfaceColors(AppTheme.FETCH, AppearanceSettings(amoledMode = true, amoledSurfaces = true))
        assertEquals(Color.Black, surfaces.surface)
        // Without AMOLED Mode, Pure Black Surfaces does nothing, as on TV.
        assertEquals(SurfaceColors.Default, surfaceColors(AppTheme.FETCH, AppearanceSettings(amoledSurfaces = true)))
    }

    @Test
    fun `increase contrast brightens secondary text and borders`() {
        val hc = surfaceColors(AppTheme.FETCH, AppearanceSettings(highContrast = true))
        assertTrue(contrastRatio(hc.textSecondary, hc.background) > contrastRatio(SurfaceColors.Default.textSecondary, hc.background))
        assertTrue(contrastRatio(hc.textMuted, hc.background) > contrastRatio(SurfaceColors.Default.textMuted, hc.background))
        assertTrue(contrastRatio(hc.outline, hc.background) > contrastRatio(SurfaceColors.Default.outline, hc.background))
    }

    @Test
    fun `a dark custom colour is lifted so buttons stay visible`() {
        val dark = CustomThemeColors.solid(0x101010)
        assertTrue(customAccentAdjusted(dark))
        val card = surfaceColors(AppTheme.CUSTOM, AppearanceSettings(customThemeColors = dark.encode())).surfaceRaised
        assertTrue(contrastRatio(customAccent(dark), card) >= 3f)
        val bright = CustomThemeColors.solid(0xEC70A9)
        assertFalse(customAccentAdjusted(bright))
        assertEquals(Color(0xFFEC70A9), customAccent(bright))
    }

    @Test
    fun `the TV's fonts are bundled`() {
        AppFont.entries.forEach { font ->
            assertNotEquals("$font has no font file", FontFamily.Default, fontFamilyFor(font))
        }
    }
}
