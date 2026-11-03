package com.fetch.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.desktop.AppState
import com.fetch.desktop.data.LayoutPrefs
import com.fetch.desktop.home.HomeLayout
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS

/**
 * Layout's poster card options, for every poster grid and row: size and corner radius (the TV's
 * Poster Card section), landscape cards everywhere, a backdrop with the logo on landscape cards,
 * and the hovered poster growing into its backdrop after a pause.
 */
data class CardStyle(
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    val corner: Dp = 10.dp,
    /** Landscape Poster Mode: every row and grid uses landscape cards. */
    val landscape: Boolean = false,
    /** Landscape cards show the backdrop with the title's logo instead of the addon's landscape art. */
    val alwaysLogo: Boolean = false,
    /** "Expand Focused Poster to Backdrop", after [expandDelayMs]. */
    val expand: Boolean = false,
    val expandDelayMs: Long = 3_000,
    /** A kids Home's bigger cards. */
    val scale: Float = 1f
) {
    /** A card's size for [shape]: the desktop's default size, scaled as the TV's settings scale theirs. */
    fun size(shape: PosterShape): CardSize {
        val base = cardSize(shape)
        return when (shape) {
            // Only posters follow the width and height settings; the others keep their own ratio.
            PosterShape.POSTER -> CardSize(base.width * widthScale * scale, base.height * heightScale * scale)
            else -> CardSize(base.width * widthScale * scale, base.height * widthScale * scale)
        }
    }

    /** The shape a row of [shape] cards is drawn in. */
    fun shapeFor(shape: PosterShape): PosterShape = if (landscape) PosterShape.LANDSCAPE else shape

    companion object {
        /** The desktop's card corner (the TV's theme default is 8 dp on its 126 dp cards). */
        private val DefaultCorner = 10.dp

        fun from(prefs: LayoutPrefs, kids: Boolean = false): CardStyle = CardStyle(
            widthScale = prefs.cardWidthScale,
            heightScale = prefs.cardHeightScale,
            corner = if (prefs.posterCardCornerRadiusDp == THEME_CARD_CORNER_RADIUS) DefaultCorner
            else (prefs.posterCardCornerRadiusDp.coerceIn(0, 32) * 1.25f).dp,
            landscape = prefs.landscapePosters,
            alwaysLogo = prefs.alwaysShowLandscapeLogo,
            expand = prefs.expandFocusedPoster,
            expandDelayMs = prefs.expandFocusedPosterDelaySeconds.coerceIn(0, 30) * 1_000L,
            scale = if (kids) HomeLayout.KIDS_CARD_SCALE else 1f
        )
    }
}

val LocalCardStyle = staticCompositionLocalOf { CardStyle() }

/** The card style for [state]'s settings everywhere but a kids Home, which scales its own. */
fun cardStyleFor(state: AppState): CardStyle = CardStyle.from(state.settings.layout)
