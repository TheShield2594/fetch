package com.fetch.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.fetch.tv.ui.theme.FetchMotion

/** The brand wordmark, crossfading when [drawableOverride] changes. */
@Composable
fun CrossfadingBrandWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    drawableOverride: Int? = null
) {
    Crossfade(
        targetState = drawableOverride,
        modifier = modifier.height(height),
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
        label = "brandWordmarkCrossfade"
    ) { currentDrawable ->
        BrandWordmark(
            modifier = Modifier.height(height),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            drawableOverride = currentDrawable
        )
    }
}
