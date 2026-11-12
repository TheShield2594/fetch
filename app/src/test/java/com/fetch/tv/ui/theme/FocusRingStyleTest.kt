package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.SolidColor
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRingStyleTest {
    @Test
    fun focusRingIsTheSameSolidNeutralInEveryTheme() {
        val style = createFocusRingStyle()

        assertEquals(FetchPrimitives.neutral100, style.solidColor)
        assertEquals(SolidColor(FetchPrimitives.neutral100), style.brush())
        assertEquals(SolidColor(FetchPrimitives.neutral100.copy(alpha = 0.5f)), style.brush(0.5f))
        AppTheme.entries.forEach { theme ->
            assertEquals(
                "$theme focus ring",
                FetchPrimitives.neutral100,
                FetchColorScheme(ThemeColors.getColorPalette(theme)).FocusRing
            )
        }
    }

    @Test
    fun `the focus ring reaches 3 to 1 in every theme, mode and focused fill`() {
        // WCAG non-text contrast. Where a focused control's own fill is too light for the ring
        // (Highlight, or a pale accent), the ring is drawn a gap outside it, so what it must
        // contrast with is the page behind instead.
        val failures = buildList {
            AppTheme.entries.forEach { theme ->
                listOf(false to false, true to false, true to true).forEach { (amoled, amoledSurfaces) ->
                    listOf(false, true).forEach { highContrast ->
                        val scheme = FetchColorScheme(ThemeColors.getColorPalette(theme), amoled, amoledSurfaces, highContrast)
                        val ring = createFocusRingStyle(highContrast)
                        val surfaces = mapOf(
                            "background" to scheme.Background,
                            "elevated" to scheme.BackgroundElevated,
                            "card" to scheme.BackgroundCard,
                            "surface" to scheme.Surface,
                            "panel" to scheme.Panel
                        )
                        val fills = mapOf(
                            "highlight" to scheme.Highlight,
                            "accent (primary)" to scheme.Primary,
                            "accent (secondary)" to scheme.Secondary,
                            "error" to scheme.Error,
                            "card" to scheme.BackgroundCard
                        )
                        surfaces.forEach { (name, surface) ->
                            val ratio = contrastRatio(ring.solidColor, surface)
                            if (ratio < MIN_CONTROL_CONTRAST) add("$theme hc=$highContrast amoled=$amoled: ring on $name is $ratio")
                        }
                        fills.forEach { (name, fill) ->
                            val behind = if (ring.needsInnerGap(fill)) scheme.Background else fill
                            val ratio = contrastRatio(ring.solidColor, behind)
                            if (ratio < MIN_CONTROL_CONTRAST) add("$theme hc=$highContrast amoled=$amoled: ring around a focused $name control is $ratio")
                        }
                    }
                }
            }
        }.distinct()
        if (failures.isNotEmpty()) org.junit.Assert.fail(failures.joinToString("\n"))
    }

    @Test
    fun `a light fill gets the gap and a dark one does not`() {
        val ring = createFocusRingStyle()
        assertEquals(true, ring.needsInnerGap(FetchPrimitives.white))
        assertEquals(false, ring.needsInnerGap(FetchPrimitives.neutral925))
        assertEquals(FetchStrokes.tokens.focus, ring.width)
        assertEquals(FetchStrokes.tokens.focus * 1.5f, createFocusRingStyle(highContrast = true).width)
    }
}
