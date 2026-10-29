package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme

/**
 * What a theme changes beyond its colours. Most themes are a palette and nothing else; Signal
 * also swaps bars and spinners for dots, sets its large type in a dot-matrix face, keeps one red
 * for state, and has its own corners.
 */
@Immutable
data class FetchThemeStyle(
    /** Progress, loaders and status marks are drawn as rows of dots instead of bars. */
    val dotMatrix: Boolean = false,
    /**
     * A colour kept for state only (ready, failed), never a fill or a label colour. Null means
     * the theme marks state with its ordinary colours.
     */
    val signal: Color? = null,
    /** Corner radii; read through [FetchTheme.radii]. */
    val radii: FetchRadiusTokens = FetchRadii.tokens,
    /** Shapes by role; read through [FetchTheme.shapes]. */
    val shapes: FetchShapeTokens = FetchShapes.tokens
)

val LocalFetchThemeStyle = staticCompositionLocalOf { FetchThemeStyle() }

/** Doto: an open (OFL) dot-matrix face. Only for type at 24sp and above; smaller it turns to noise. */
@OptIn(ExperimentalTextApi::class)
val DotoFamily = FontFamily(
    Font(
        resId = R.font.doto_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Black.weight),
            // Round dots, not squares.
            FontVariation.Setting("ROND", 100f)
        )
    )
)

val SpaceMonoFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold)
)

private val SignalStyle = FetchThemeStyle(
    dotMatrix = true,
    signal = Color(0xFFD71921),
    radii = FetchRadii.signal,
    shapes = FetchShapes.signal
)

fun AppTheme.themeStyle(): FetchThemeStyle = when (this) {
    AppTheme.SIGNAL -> SignalStyle
    else -> FetchThemeStyle()
}

/**
 * Signal's type: dot-matrix display and headline sizes, monospace section titles, specs and
 * badges. Body text, buttons and anything read as a sentence keep the member's chosen font.
 */
fun FetchTextStyleTokens.withSignalType(): FetchTextStyleTokens = copy(
    display = display.copy(fontFamily = DotoFamily, letterSpacing = 1.sp),
    displayCompact = displayCompact.copy(fontFamily = DotoFamily, letterSpacing = 1.sp),
    headline = headline.copy(fontFamily = DotoFamily, letterSpacing = 1.sp),
    sectionTitle = sectionTitle.copy(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        letterSpacing = 1.5.sp
    ),
    spec = spec.copy(fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp),
    badge = badge.copy(fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
)
