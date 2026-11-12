package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

/**
 * What the design system guarantees for every theme it ships (#455), measured with WCAG 2's own
 * relative-luminance formula rather than the app's helpers: body text 4.5:1 on every surface, the
 * label on the accent 4.5:1 on every colour of the accent brush, the accent 3:1 against the
 * ground it sits on, and the focus ring 3:1 against every surface it can be drawn over. Each
 * theme is checked with and without AMOLED, Pure Black Surfaces and "Increase contrast".
 */
class ThemeAccessibilityTest {

    @Test
    fun `the shipped themes are the kept set`() {
        assertEquals(
            listOf(AppTheme.FETCH, AppTheme.CUSTOM, AppTheme.OCEAN, AppTheme.EMERALD, AppTheme.AMBER, AppTheme.WHITE),
            AppTheme.entries
        )
    }

    @Test
    fun `every theme meets WCAG AA for text, accent and focus ring`() {
        val failures = buildList {
            AppTheme.entries.forEach { theme ->
                schemes(ThemeColors.getColorPalette(theme)).forEach { (mode, scheme, palette) ->
                    addAll(check("${theme.name}$mode", scheme, palette))
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `custom colours always draw with readable labels`() {
        val random = Random(455)
        val failures = buildList {
            repeat(400) {
                val colors = if (it % 4 == 0) {
                    CustomThemeColors.solid(random.nextInt(0x1000000))
                } else {
                    CustomThemeColors(random.nextInt(0x1000000), random.nextInt(0x1000000), random.nextInt(0x1000000))
                }
                val palette = colors.toColorPalette()
                val scheme = FetchColorScheme(palette)
                addAll(check("CUSTOM ${colors.encode()}", scheme, palette))
            }
        }
        if (failures.isNotEmpty()) fail(failures.take(20).joinToString("\n"))
    }

    @Test
    fun `every solid custom colour passes the editor's contrast check`() {
        val random = Random(4550)
        repeat(300) {
            val colors = CustomThemeColors.solid(random.nextInt(0x1000000))
            assertTrue(colors.encode(), colors.meetsContrastMinimum)
        }
        listOf(0x000000, 0xFFFFFF, 0x808080, 0x0000FF, 0xFFFF00).forEach {
            assertTrue(formatColor(it), CustomThemeColors.solid(it).meetsContrastMinimum)
        }
        assertTrue(CustomThemeColors.Default.meetsContrastMinimum)
    }

    @Test
    fun `a gradient no label can read on fails the check and draws solid`() {
        // A mid grey (3:1 against the cards, so left as it is) between white ends: black text
        // misses 4.5:1 on the grey and white text has no contrast at all on the white.
        val colors = CustomThemeColors(0xFFFFFF, 0x727272, 0xFFFFFF)

        assertFalse(colors.meetsContrastMinimum)
        assertTrue(colors.labelContrast < MIN_TEXT_CONTRAST)
        val palette = colors.toColorPalette()
        assertEquals(listOf(palette.secondary), palette.accentGradient)
        assertTrue(contrast(palette.onSecondary, palette.secondary) >= 4.5)
    }

    private fun schemes(palette: ThemeColorPalette): List<Triple<String, FetchColorScheme, ThemeColorPalette>> =
        listOf(false to false, true to false, true to true).flatMap { (amoled, surfaces) ->
            listOf(false, true).map { highContrast ->
                val mode = buildString {
                    if (surfaces) append(" (AMOLED surfaces)") else if (amoled) append(" (AMOLED)")
                    if (highContrast) append(" (high contrast)")
                }
                Triple(mode, FetchColorScheme(palette, amoled, surfaces, highContrast), palette)
            }
        }

    private fun check(name: String, scheme: FetchColorScheme, palette: ThemeColorPalette): List<String> = buildList {
        fun require(label: String, ratio: Double, min: Double) {
            if (ratio < min) add(String.format(Locale.US, "%s: %s is %.2f:1 (needs %.1f:1)", name, label, ratio, min))
        }
        val surfaces = listOf(
            "background" to scheme.Background,
            "elevated" to scheme.BackgroundElevated,
            "card" to scheme.BackgroundCard,
            "surface" to scheme.Surface,
            "surfaceVariant" to scheme.SurfaceVariant,
            "panel" to scheme.Panel,
            "field" to scheme.Field,
            "menu" to scheme.Menu,
            "modal" to scheme.Modal,
            "focusBackground" to scheme.FocusBackground
        )
        listOf("textPrimary" to scheme.TextPrimary, "textSecondary" to scheme.TextSecondary).forEach { (textName, text) ->
            surfaces.forEach { (surfaceName, surface) ->
                require("$textName on $surfaceName", contrast(text, surface, scheme.Background), BODY_TEXT)
            }
        }
        palette.accentGradient.forEachIndexed { index, fill ->
            require("onSecondary on accent brush[$index]", contrast(scheme.OnSecondary, fill), BODY_TEXT)
        }
        require("onSecondary on secondary", contrast(scheme.OnSecondary, scheme.Secondary), BODY_TEXT)
        listOf("background" to scheme.Background, "card" to scheme.BackgroundCard).forEach { (surfaceName, surface) ->
            require("secondary (Play) on $surfaceName", contrast(scheme.Secondary, surface), NON_TEXT)
        }
        surfaces.forEach { (surfaceName, surface) ->
            require("focus ring on $surfaceName", contrast(scheme.FocusRing, surface, scheme.Background), NON_TEXT)
        }
    }

    private fun contrast(foreground: Color, background: Color, behind: Color = Color.Black): Double {
        val bg = if (background.alpha < 1f) background.compositeOver(behind) else background
        val fg = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** WCAG 2.x relative luminance of an sRGB colour. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun formatColor(color: Int) = "#" + color.toString(16).padStart(6, '0')

    private companion object {
        const val BODY_TEXT = 4.5
        const val NON_TEXT = 3.0
    }
}
