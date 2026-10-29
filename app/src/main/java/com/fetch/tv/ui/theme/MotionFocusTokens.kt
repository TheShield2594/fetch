package com.fetch.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchMotionDurations(
    val quick: Int,
    val fast: Int,
    /** Something appearing on screen. */
    val enter: Int,
    /** Something leaving the screen; shorter than [enter]. */
    val exit: Int,
    /** On-screen movement or a morph between two resting states. */
    val move: Int,
    /** 350ms: over the 300ms UI budget. For new UI use [enter], [exit] or [move]; this remains for existing callers. */
    val medium: Int,
    /** 450ms: over the 300ms UI budget. For new UI use [enter], [exit] or [move]; this remains for existing callers. */
    val slow: Int,
    /** Large, deliberate moves such as the detail hero growing to full screen. */
    val long: Int,
    /** 400ms: over the 300ms UI budget. For new UI use [enter], [exit] or [move]; this remains for existing callers. */
    val overlay: Int,
    /** 450ms: over the 300ms UI budget. For new UI use [enter], [exit] or [move]; this remains for existing callers. */
    val hero: Int,
    /** One full turn of a loading spinner glyph (linear, repeating). */
    val spinner: Int,
    val shimmer: Int,
    /** The startup splash fading out over Home: once per session, so it may exceed the 300ms UI budget. */
    val splashExit: Int,
    /** The hero backdrop crossfading to the focused title's artwork. */
    val backdropCrossfade: Int,
    /** Player controls hide after this long without a key press. */
    val playerControlsAutoHide: Int
)

@Immutable
data class FetchMotionEasings(
    val standard: Easing,
    val emphasized: Easing,
    val decelerate: Easing,
    /** Strong ease-out for things entering or leaving. */
    val out: Easing,
    /** Strong ease-in-out for things moving on screen. */
    val inOut: Easing,
    /** Drawer and side-panel slides. */
    val drawer: Easing
)

@Immutable
data class FetchMotionTokens(
    val durations: FetchMotionDurations,
    val easings: FetchMotionEasings,
    val reducedMotionScale: Float
)

@Immutable
data class FetchFocusTokens(
    val scale: Float,
    val subtleScale: Float,
    /** How much a focused media card grows. */
    val cardScale: Float,
    /** Shadow under a focused media card, so it lifts off the row instead of only growing. */
    val cardShadow: Dp,
    /** How far content dims while the navigation rail has focus. */
    val railContentDim: Float,
    val scrollViewportTarget: Float,
    val longPressInitialDelayMillis: Long,
    val longPressRepeatMillis: Long
)

object FetchMotion {
    val tokens = FetchMotionTokens(
        durations = FetchMotionDurations(
            quick = 125,
            fast = 180,
            enter = 200,
            exit = 150,
            move = 250,
            medium = 350,
            slow = 450,
            long = 600,
            overlay = 400,
            hero = 450,
            spinner = 1000,
            shimmer = 1200,
            splashExit = 400,
            backdropCrossfade = 400,
            playerControlsAutoHide = 4000
        ),
        easings = FetchMotionEasings(
            standard = FastOutSlowInEasing,
            emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            decelerate = LinearOutSlowInEasing,
            out = CubicBezierEasing(0.23f, 1f, 0.32f, 1f),
            inOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f),
            drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
        ),
        reducedMotionScale = 1f
    )

    fun <T> focusTween(): TweenSpec<T> = tween(tokens.durations.fast, easing = tokens.easings.standard)

    fun <T> mediumTween(): TweenSpec<T> = tween(tokens.durations.medium, easing = tokens.easings.standard)

    fun <T> slowTween(): TweenSpec<T> = tween(tokens.durations.slow, easing = tokens.easings.decelerate)

    /** Something appearing: overlays, panels, labels, crossfades the user is waiting on. */
    fun <T> enterTween(): TweenSpec<T> = tween(tokens.durations.enter, easing = tokens.easings.out)

    /** Something leaving. Shorter than [enterTween] so the response feels immediate. */
    fun <T> exitTween(): TweenSpec<T> = tween(tokens.durations.exit, easing = tokens.easings.out)

    /** Something moving or morphing between two on-screen resting states. */
    fun <T> moveTween(): TweenSpec<T> = tween(tokens.durations.move, easing = tokens.easings.inOut)

    fun <T> focusSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

object FetchFocus {
    val tokens = FetchFocusTokens(
        scale = 1.02f,
        subtleScale = 1.01f,
        cardScale = 1.08f,
        cardShadow = 16.dp,
        railContentDim = 0.5f,
        scrollViewportTarget = 0.42f,
        longPressInitialDelayMillis = 360L,
        longPressRepeatMillis = 72L
    )
}
