package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.fetch.tv.domain.model.CustomThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemePaletteTest {
    @Test
    fun solidColorsUseASolidAccentBrushWithTheExistingStorageFormat() {
        val colors = CustomThemeColors.solid(0x7FD3FF)
        val color = Color(0xFF7FD3FF)
        val palette = colors.toColorPalette()

        assertTrue(colors.isSolid)
        assertEquals("#7FD3FF,#7FD3FF,#7FD3FF", colors.encode())
        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(listOf(color), palette.accentGradient)
        assertEquals(SolidColor(color), palette.accentBrush())
        assertEquals(color, palette.secondary)
        assertEquals(color, palette.secondaryVariant)
    }

    @Test
    fun gradientsWithMatchingEndColorsKeepAllThreeStops() {
        val colors = CustomThemeColors(0xFF5252, 0x42A5F5, 0xFF5252)
        val palette = colors.toColorPalette()
        val stops = listOf(Color(0xFFFF5252), Color(0xFF42A5F5), Color(0xFFFF5252))

        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(stops, palette.accentGradient)
    }

    @Test
    fun solidColorsKeepReadableForegrounds() {
        val lightPalette = CustomThemeColors.solid(0xFFFFFF).toColorPalette()
        val darkPalette = CustomThemeColors.solid(0x000000).toColorPalette()

        assertEquals(Color.Black, lightPalette.onSecondary)
        assertEquals(Color.Black, lightPalette.onSecondaryVariant)
        assertEquals(Color.White, darkPalette.onSecondary)
        assertEquals(Color.White, darkPalette.onSecondaryVariant)
    }

    @Test
    fun tooDarkAccentsAreLiftedUntilTheyStandOutFromCards() {
        val colors = CustomThemeColors.solid(0x0A1A3A)
        val palette = colors.toColorPalette()

        assertTrue(colors.adjustedForContrast)
        assertTrue(contrastRatio(palette.secondary, palette.backgroundCard) >= MIN_CONTROL_CONTRAST)
        assertEquals("the stored colour is unchanged", "#0A1A3A,#0A1A3A,#0A1A3A", colors.encode())
    }

    @Test
    fun accentsThatAlreadyStandOutAreNotAdjusted() {
        assertFalse(CustomThemeColors.solid(0x7FD3FF).adjustedForContrast)
        assertFalse(CustomThemeColors(0xFF5252, 0x42A5F5, 0xFF5252).adjustedForContrast)
    }
}
