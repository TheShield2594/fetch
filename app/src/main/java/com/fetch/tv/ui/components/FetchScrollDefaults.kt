package com.fetch.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.fetch.tv.ui.theme.FetchFocus
import com.fetch.tv.ui.theme.FetchMotion

@OptIn(ExperimentalFoundationApi::class)
object FetchScrollDefaults {
    /** Focus scrolling with full motion: a critically damped spring that keeps up with key repeat. */
    val smoothScrollSpec: BringIntoViewSpec = FetchBringIntoViewSpec(FetchMotion.focusSpring())

    /** Reduced motion: the same scroll targets, reached almost at once. */
    val reducedMotionScrollSpec: BringIntoViewSpec = FetchBringIntoViewSpec(
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
    )
}

@OptIn(ExperimentalFoundationApi::class)
private class FetchBringIntoViewSpec(
    private val animationSpec: AnimationSpec<Float>
) : BringIntoViewSpec {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = animationSpec

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        if (containerSize <= 0f || size <= 0f) return 0f
        val itemCenter = offset + size / 2f
        val viewportTarget = containerSize * FetchFocus.tokens.scrollViewportTarget
        val distance = itemCenter - viewportTarget
        if (offset >= 0f && offset + size <= containerSize && distance > 0f && distance < 12f) {
            return 0f
        }
        return distance
    }
}
