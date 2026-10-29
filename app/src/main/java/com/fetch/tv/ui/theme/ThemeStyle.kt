package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme

/**
 * What a theme changes beyond its colours. Every theme shares Fetch's corners today; the hook
 * stays so a theme can bring its own without screens changing.
 */
@Immutable
data class FetchThemeStyle(
    /** Corner radii; read through [FetchTheme.radii]. */
    val radii: FetchRadiusTokens = FetchRadii.tokens,
    /** Shapes by role; read through [FetchTheme.shapes]. */
    val shapes: FetchShapeTokens = FetchShapes.tokens
)

val LocalFetchThemeStyle = staticCompositionLocalOf { FetchThemeStyle() }

private val DefaultStyle = FetchThemeStyle()

@Suppress("UnusedReceiverParameter")
fun AppTheme.themeStyle(): FetchThemeStyle = DefaultStyle

/**
 * Doto, an open (OFL) dot-matrix face. Fetch's identity, used sparingly: the wordmark and the
 * Ambient Mode clock, never content titles. Only at 24sp and above; smaller it turns to noise.
 */
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
