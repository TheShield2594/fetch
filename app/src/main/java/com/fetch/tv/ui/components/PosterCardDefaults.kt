package com.fetch.tv.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.fetch.tv.ui.theme.FetchComponents

val LocalLandscapePosterMode = staticCompositionLocalOf { false }
val LocalAlwaysBackdropWithLogo = staticCompositionLocalOf { false }

/** Whether background trailers zoom to fill their frame; false plays them whole, letterboxed. */
val LocalTrailerCropToFill = staticCompositionLocalOf { true }

@Immutable
data class PosterCardStyle(
    val width: Dp = FetchComponents.tokens.posterCard.width,
    val height: Dp = FetchComponents.tokens.posterCard.height,
    val cornerRadius: Dp = FetchComponents.tokens.posterCard.cornerRadius,
    val focusedBorderWidth: Dp = FetchComponents.tokens.posterCard.focusedBorderWidth,
    val focusedScale: Float = FetchComponents.tokens.posterCard.focusedScale
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()
}
