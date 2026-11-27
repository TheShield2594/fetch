package com.fetch.tv.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Motion for a browse grid's card (#853): after a filter, sort or removal the cards that stay
 * glide to their new places on the `move` token, and cards that come or go fade. None under
 * reduced motion or on a low-RAM device, where the grid changes in one frame as before.
 */
// An extension on the grid item's scope, not on Modifier: animateItem only exists there.
@Suppress("ModifierFactoryExtensionFunction")
@Composable
fun LazyGridItemScope.browseGridItemMotion(): Modifier =
    if (FetchTheme.fullMotion && !FetchTheme.lowRamDevice) {
        Modifier.animateItem(
            fadeInSpec = FetchMotion.enterTween(),
            placementSpec = FetchMotion.moveTween(),
            fadeOutSpec = FetchMotion.exitTween()
        )
    } else {
        Modifier
    }
