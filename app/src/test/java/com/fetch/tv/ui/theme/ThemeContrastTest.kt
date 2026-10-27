package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * Runs every [FetchColorScheme.contrastPairs] entry, in every built-in theme with and without the
 * AMOLED modes and "Increase contrast", against WCAG AA's 4.5:1 for normal-size text. A custom theme picks its own accent,
 * so only its default colours are checked here.
 */
class ThemeContrastTest {

    private fun contrast(foreground: Color, background: Color, behind: Color): Double {
        val bg = if (background.alpha < 1f) background.compositeOver(behind) else background
        val fg = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
        val lighter = maxOf(fg.luminance(), bg.luminance()) + 0.05
        val darker = minOf(fg.luminance(), bg.luminance()) + 0.05
        return lighter / darker
    }

    @Test
    fun `every text and surface pair meets WCAG AA in every built-in theme`() {
        val failures = buildList {
            AppTheme.entries.forEach { theme ->
                listOf(false to false, true to false, true to true).forEach { (amoled, amoledSurfaces) ->
                    listOf(false, true).forEach { highContrast ->
                        val scheme = FetchColorScheme(ThemeColors.getColorPalette(theme), amoled, amoledSurfaces, highContrast)
                        scheme.contrastPairs.forEach { pair ->
                            val ratio = contrast(pair.foreground, pair.background, scheme.Background)
                            if (ratio < MIN_RATIO) {
                                val mode = modeName(amoled, amoledSurfaces) + if (highContrast) " (high contrast)" else ""
                                add(String.format(Locale.US, "%s%s: %s is %.2f:1", theme.name, mode, pair.label, ratio))
                            }
                        }
                    }
                }
            }
        }.distinct()
        if (failures.isNotEmpty()) fail("Below ${MIN_RATIO}:1\n" + failures.joinToString("\n"))
    }

    private fun modeName(amoled: Boolean, amoledSurfaces: Boolean) = when {
        amoledSurfaces -> " (AMOLED surfaces)"
        amoled -> " (AMOLED)"
        else -> ""
    }

    private companion object {
        const val MIN_RATIO = 4.5
    }
}
