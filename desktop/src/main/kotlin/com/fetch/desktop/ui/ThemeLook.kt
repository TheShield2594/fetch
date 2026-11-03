package com.fetch.desktop.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.fetch.desktop.data.AppearanceSettings
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors

/**
 * The window's surfaces and text, worked out from the theme and Appearance's settings as the TV
 * app's `FetchColorScheme` does: Increase contrast brightens secondary text and borders, AMOLED
 * Mode makes the background pure black (and with Pure Black Surfaces the cards too), and the
 * Custom theme tints the surfaces with its colour.
 */
data class SurfaceColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color
) {
    companion object {
        val Default = SurfaceColors(
            background = Color(0xFF0B0B0C),
            surface = Color(0xFF16161A),
            surfaceRaised = Color(0xFF1E1E23),
            surfaceHigh = Color(0xFF2A2A30),
            outline = Color(0xFF3A3A42),
            textPrimary = Color(0xFFF5F5F5),
            textSecondary = Color(0xFFB3B3B8),
            textMuted = Color(0xFF85858C)
        )
    }
}

fun surfaceColors(theme: AppTheme, appearance: AppearanceSettings): SurfaceColors {
    var c = SurfaceColors.Default
    if (theme == AppTheme.CUSTOM) {
        // The TV's custom palette: surfaces take a faint tint of the middle colour.
        val tint = Color(appearance.customColors.second or 0xFF000000.toInt())
        fun surface(base: Long, amount: Float) = lerp(Color(base), tint, amount)
        c = c.copy(
            background = surface(0xFF0C0D0F, 0.025f),
            surface = surface(0xFF17191D, 0.045f),
            surfaceRaised = surface(0xFF20242A, 0.06f),
            surfaceHigh = surface(0xFF292E35, 0.06f)
        )
    }
    if (appearance.highContrast) {
        // The TV's neutral200, neutral400 and neutral600.
        c = c.copy(textSecondary = Color(0xFFE0E0E0), textMuted = Color(0xFFB3B3B3), outline = Color(0xFF808080))
    }
    if (appearance.amoledMode) c = c.copy(background = Color.Black)
    if (appearance.pureBlackSurfaces) c = c.copy(surface = Color.Black, surfaceRaised = Color.Black)
    return c
}

/**
 * The Custom theme's accent: its middle colour, lifted towards white until it has 3:1 contrast
 * with the cards it sits on, as on TV, so a very dark pick still gives a visible Play button.
 * The stored colours never change.
 */
fun customAccent(colors: CustomThemeColors): Color {
    val card = lerp(Color(0xFF20242A), Color(colors.second or 0xFF000000.toInt()), 0.06f)
    return Color(colors.second or 0xFF000000.toInt()).liftedForContrast(card, 3f)
}

/** True when [customAccent] had to lighten the picked colour. */
fun customAccentAdjusted(colors: CustomThemeColors): Boolean =
    customAccent(colors) != Color(colors.second or 0xFF000000.toInt())

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private fun Color.liftedForContrast(background: Color, minRatio: Float): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    for (step in 1..40) {
        val lifted = lerp(this, Color.White, step / 40f)
        if (contrastRatio(lifted, background) >= minRatio) return lifted
    }
    return Color.White
}

// --- Fonts ---

/** The bundled font files (copied from the TV app's `res/font` at build time). */
private fun fontFile(font: AppFont): String? = when (font) {
    AppFont.SCHIBSTED_GROTESK -> "fonts/schibsted_grotesk_variable.ttf"
    AppFont.INTER -> "fonts/inter_variable.ttf"
    AppFont.DM_SANS -> "fonts/dm_sans_variable.ttf"
    AppFont.OPEN_SANS -> "fonts/opensans_variable.ttf"
}

private val fontFamilies = mutableMapOf<AppFont, FontFamily>()

/**
 * The app font, one variable font file set to each weight (without the weight axis set, every
 * weight draws as the file's default, as the TV app found). Falls back to the system font when the
 * file isn't on the classpath (a build without the TV app's fonts).
 */
fun fontFamilyFor(font: AppFont): FontFamily = fontFamilies.getOrPut(font) {
    val path = fontFile(font)?.takeIf { Thread.currentThread().contextClassLoader?.getResource(it) != null || AppFont::class.java.classLoader.getResource(it) != null }
        ?: return@getOrPut FontFamily.Default
    val weights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
    runCatching {
        @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
        FontFamily(weights.map { w ->
            androidx.compose.ui.text.platform.Font(path, w, androidx.compose.ui.text.font.FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w.weight)))
        })
    }.getOrDefault(FontFamily.Default)
}

/** [this] with every style in [family]. */
fun Typography.withFontFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f()
    )
}
