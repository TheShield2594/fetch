package com.fetch.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fetch.tv.ui.components.hero.ModernHeroGradientLayer
import com.fetch.tv.ui.components.hero.ModernHeroMediaLayer

/** Home's hero art: the shared backdrop and trailer layer and its scrims, driven by Home's scene. */
@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    onBackdropDisplayed: (String) -> Unit = {}
) {
    ModernHeroMediaLayer(
        heroBackdrop = { state().heroBackdrop },
        shouldPlayHeroTrailer = { state().shouldPlayTrailer },
        heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
        heroTrailerUrl = { state().trailerUrl },
        heroTrailerAudioUrl = { state().trailerAudioUrl },
        heroTrailerPlaybackKey = { state().trailerPlaybackKey },
        muted = { state().trailerMuted },
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        onBackdropDisplayed = {
            // Kept in sync for navigation transitions into Detail.
            HeroBackdropState.update(it)
            onBackdropDisplayed(it)
        },
        modifier = modifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx,
        initialBackdrop = HeroBackdropState.lastDisplayedUrl,
        trailerOverscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM
    )
    val isTrailerPlayingFullScreen = {
        val s = state()
        s.fullScreenBackdrop && s.shouldPlayTrailer && s.trailerFirstFrameRendered
    }
    ModernHeroGradientLayer(
        bgColor = bgColor,
        isFullScreen = isFullScreen,
        isTrailerPlayingFullScreen = isTrailerPlayingFullScreen,
        modifier = modifier
    )
}

