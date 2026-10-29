@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.accentBrush

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 18.dp
private val ROW_GAP = FetchTheme.spacing.xxs
/** Gap between consecutive items starting to fade, on the way in and out. */
internal const val START_OVERLAY_ITEM_STAGGER_MS = 50L
/** How long the playback-start overlays stay up before hiding themselves. */
internal const val START_OVERLAY_HOLD_MS = 5_000L

/**
 * Parental guide overlay showing content warnings with animated vertical line + staggered items.
 *
 * Triggered by ViewModel when video first starts playing.
 * Completely independent of player controls.
 * Auto-hides after 5 seconds with reverse animation.
 */
@Composable
fun ParentalGuideOverlay(
    warnings: List<ParentalWarning>,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (warnings.isEmpty()) return

    val count = warnings.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))
    val accentBrush = FetchTheme.palette.accentBrush()

    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(count) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    val fullMotion = FetchTheme.fullMotion

    LaunchedEffect(isVisible) {
        if (isVisible && !animating) {
            animating = true
            playStartOverlayIn(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
            delay(START_OVERLAY_HOLD_MS)
            playStartOverlayOut(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            // Quick hide if something forces it off (e.g. controls shown during animation)
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    // Recompose only when the overlay appears or disappears, not on every fade frame.
    val shown by remember { derivedStateOf { containerAlpha.value > 0f } }
    if (!shown) return

    Row(
        modifier = modifier
            .graphicsLayer { alpha = containerAlpha.value }
            .padding(start = FetchTheme.spacing.xxl, top = FetchTheme.spacing.xl),
        verticalAlignment = Alignment.Top
    ) {
        // Animated vertical line
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(totalLineHeight.dp)
                // Grows top-down as a transform, so the line never relayouts.
                .graphicsLayer {
                    scaleY = lineHeightFraction.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .clip(RoundedCornerShape(FetchTheme.spacing.hairline))
                .background(accentBrush)
        )

        // Warning items
        Column(
            modifier = Modifier.padding(start = FetchTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            warnings.forEachIndexed { index, warning ->
                Row(
                    modifier = Modifier
                        .height(ROW_HEIGHT)
                        .graphicsLayer { alpha = itemAlphas.getOrNull(index)?.value ?: 0f },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = warning.label,
                        fontSize = 12.sp,
                        color = FetchTheme.colors.Player.TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = " · ",
                        fontSize = 12.sp,
                        color = FetchTheme.colors.Player.TextDisabled,
                    )
                    Text(
                        text = warning.severity,
                        fontSize = 12.sp,
                        color = FetchTheme.colors.Player.TextDisabled,
                    )
                }
            }
        }
    }
}

/** Container fades in, the accent line grows top-down, then the items stagger in. */
internal suspend fun playStartOverlayIn(
    fullMotion: Boolean,
    containerAlpha: Animatable<Float, AnimationVector1D>,
    lineHeightFraction: Animatable<Float, AnimationVector1D>,
    itemAlphas: List<Animatable<Float, AnimationVector1D>>
) {
    if (!fullMotion) {
        lineHeightFraction.snapTo(1f)
        itemAlphas.forEach { it.snapTo(1f) }
        containerAlpha.animateTo(1f, FetchMotion.enterTween())
        return
    }
    containerAlpha.animateTo(1f, FetchMotion.enterTween())
    lineHeightFraction.animateTo(
        1f,
        tween(FetchMotion.tokens.durations.move, easing = FetchMotion.tokens.easings.out)
    )
    coroutineScope {
        itemAlphas.forEachIndexed { index, alpha ->
            launch {
                delay(index * START_OVERLAY_ITEM_STAGGER_MS)
                alpha.animateTo(1f, FetchMotion.enterTween())
            }
        }
    }
}

/** Items stagger out bottom-up, then the line and the container leave together. */
internal suspend fun playStartOverlayOut(
    fullMotion: Boolean,
    containerAlpha: Animatable<Float, AnimationVector1D>,
    lineHeightFraction: Animatable<Float, AnimationVector1D>,
    itemAlphas: List<Animatable<Float, AnimationVector1D>>
) {
    if (!fullMotion) {
        containerAlpha.animateTo(0f, FetchMotion.exitTween())
        lineHeightFraction.snapTo(0f)
        itemAlphas.forEach { it.snapTo(0f) }
        return
    }
    coroutineScope {
        itemAlphas.asReversed().forEachIndexed { order, alpha ->
            launch {
                delay(order * START_OVERLAY_ITEM_STAGGER_MS)
                alpha.animateTo(0f, FetchMotion.exitTween())
            }
        }
    }
    coroutineScope {
        launch { lineHeightFraction.animateTo(0f, FetchMotion.exitTween()) }
        launch { containerAlpha.animateTo(0f, FetchMotion.exitTween()) }
    }
}
