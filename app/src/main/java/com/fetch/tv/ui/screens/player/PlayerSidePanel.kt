package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.FetchPrimitives

/** Two-column panels (Audio's tracks beside its options, the subtitle style grid) need this much. */
internal val PlayerSidePanelWidth = 640.dp

/**
 * Single-list panels (Sources, Queue, Episodes): a little over half the screen, so the paused
 * frame stays readable beside the list instead of two thirds of it going behind the panel.
 */
internal val PlayerSidePanelListWidth = 520.dp
internal val PlayerSidePanelContentPadding = 20.dp

/**
 * The right-hand panel frame the player's panels share: a scrim, a slide in from the right, and
 * a focus trap so D-pad moves stay inside the panel while it is open. Back is handled by the
 * player screen, which closes an open panel before anything else and gives focus back to the
 * button that opened it.
 */
@Composable
internal fun PlayerSidePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.45f,
    /** Draws the standard panel surface and padding; panels that style themselves leave it off. */
    drawSurface: Boolean = false,
    width: Dp = PlayerSidePanelWidth,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.quick)),
            exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FetchPrimitives.black.copy(alpha = scrimAlpha))
            )
        }
        val fullMotion = FetchTheme.fullMotion
        AnimatedVisibility(
            visible = visible,
            // Drawer curve for the slide; reduced motion fades the panel in place instead.
            enter = if (fullMotion) {
                slideInHorizontally(
                    animationSpec = tween(FetchMotion.tokens.durations.enter, easing = FetchMotion.tokens.easings.drawer),
                    initialOffsetX = { it }
                )
            } else {
                fadeIn(animationSpec = FetchMotion.enterTween())
            },
            exit = if (fullMotion) {
                slideOutHorizontally(
                    animationSpec = tween(FetchMotion.tokens.durations.exit, easing = FetchMotion.tokens.easings.drawer),
                    targetOffsetX = { it }
                )
            } else {
                fadeOut(animationSpec = FetchMotion.exitTween())
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width)
                    .playerSidePanelFocusTrap(open = visible)
                    .then(
                        if (drawSurface) {
                            Modifier
                                .clip(RoundedCornerShape(topStart = FetchTheme.spacing.lg, bottomStart = FetchTheme.spacing.lg))
                                .background(FetchTheme.colors.BackgroundElevated)
                                .padding(horizontal = PlayerSidePanelContentPadding, vertical = FetchTheme.spacing.xl)
                        } else {
                            Modifier
                        }
                    ),
                content = content
            )
        }
    }
}

/**
 * Keeps focus inside the panel while [open]. Once the panel starts closing the trap lets go, so
 * the screen can move focus back to the button that opened it during the exit animation.
 */
@Composable
internal fun Modifier.playerSidePanelFocusTrap(open: Boolean): Modifier {
    val isOpen by rememberUpdatedState(open)
    return this
        .focusProperties {
            onExit = { if (isOpen) cancelFocusChange() }
        }
        .focusGroup()
}
