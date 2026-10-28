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

@Immutable
data class FetchMotionDurations(
    val instant: Int,
    val quick: Int,
    val fast: Int,
    /** Something appearing on screen. */
    val enter: Int,
    /** Something leaving the screen; shorter than [enter]. */
    val exit: Int,
    /** On-screen movement or a morph between two resting states. */
    val move: Int,
    val medium: Int,
    val slow: Int,
    /** Large, deliberate moves such as the detail hero growing to full screen. */
    val long: Int,
    val overlay: Int,
    val sidebarLabelIn: Int,
    val sidebarLabelOut: Int,
    val sidebarPanelIn: Int,
    val sidebarPanelOut: Int,
    val sidebarBloomOut: Int,
    val sidebarEnter: Int,
    val sidebarExit: Int,
    val hero: Int,
    val shimmer: Int
)

@Immutable
data class FetchMotionEasings(
    val standard: Easing,
    val emphasized: Easing,
    val decelerate: Easing,
    val accelerate: Easing,
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
    val focusScale: Float,
    val selectedScale: Float,
    val pressedScale: Float,
    val reducedMotionScale: Float
)

@Immutable
data class FetchFocusTokens(
    val scale: Float,
    val subtleScale: Float,
    val pressedScale: Float,
    val disabledScale: Float,
    val scrollViewportTarget: Float,
    val longPressInitialDelayMillis: Long,
    val longPressRepeatMillis: Long
)

object FetchMotion {
    val tokens = FetchMotionTokens(
        durations = FetchMotionDurations(
            instant = 0,
            quick = 125,
            fast = 180,
            enter = 200,
            exit = 150,
            move = 250,
            medium = 350,
            slow = 450,
            long = 600,
            overlay = 400,
            sidebarLabelIn = 125,
            sidebarLabelOut = 145,
            sidebarPanelIn = 345,
            sidebarPanelOut = 385,
            sidebarBloomOut = 395,
            sidebarEnter = 385,
            sidebarExit = 145,
            hero = 450,
            shimmer = 1200
        ),
        easings = FetchMotionEasings(
            standard = FastOutSlowInEasing,
            emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            decelerate = LinearOutSlowInEasing,
            accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f),
            out = CubicBezierEasing(0.23f, 1f, 0.32f, 1f),
            inOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f),
            drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
        ),
        focusScale = 1.02f,
        selectedScale = 1.01f,
        pressedScale = 0.98f,
        reducedMotionScale = 1f
    )

    fun <T> quickTween(): TweenSpec<T> = tween(tokens.durations.quick, easing = tokens.easings.standard)

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
        pressedScale = 0.98f,
        disabledScale = 1f,
        scrollViewportTarget = 0.42f,
        longPressInitialDelayMillis = 360L,
        longPressRepeatMillis = 72L
    )
}
