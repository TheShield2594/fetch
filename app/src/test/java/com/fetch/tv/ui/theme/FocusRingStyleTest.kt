package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
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
    fun supporterGradientsMatchTheMobileColorways() {
        val expectedGradients = mapOf(
            AppTheme.JADE to listOf(
                Color(0xFF7BF08D),
                Color(0xFF22D37C),
                Color(0xFF0BBF9A)
            ),
            AppTheme.ROSE_GOLD to listOf(
                Color(0xFFB75AFF),
                Color(0xFFEC70A9),
                Color(0xFFFFB37A)
            ),
            AppTheme.ARCTIC_BLUE to listOf(
                Color(0xFF4DE3FF),
                Color(0xFF3185F5),
                Color(0xFF4D55E8)
            ),
            AppTheme.GRAPHITE to listOf(
                Color(0xFFF3F5F7),
                Color(0xFFAAB2BE),
                Color(0xFF687381)
            )
        )

        expectedGradients.forEach { (theme, expectedGradient) ->
            assertEquals(expectedGradient, ThemeColors.getColorPalette(theme).accentGradient)
        }
    }
}
