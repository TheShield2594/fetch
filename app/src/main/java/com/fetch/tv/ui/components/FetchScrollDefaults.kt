package com.fetch.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.fetch.tv.ui.theme.FetchFocus
import com.fetch.tv.ui.theme.FetchMotion

@OptIn(ExperimentalFoundationApi::class)
object FetchScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.95f,
            stiffness = FetchMotion.tokens.durations.fast.toFloat()
        )

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
}
