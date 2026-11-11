package com.fetch.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager

val LocalFastHorizontalNavigationEnabled = compositionLocalOf { false }

/**
 * The one set of key-repeat gates every list in the app uses, so holding a direction moves at the
 * same pace everywhere. Home, the detail page, folders and Discover add a vertical drag on top
 * ([dpadVerticalFastScroll]), which uses [HORIZONTAL_GATE_MS] for its horizontal steps.
 */
object DpadRepeat {
    /** Minimum interval between horizontal repeats. */
    const val HORIZONTAL_GATE_MS = 80L

    /** Horizontal interval when "Fast horizontal navigation" is on. */
    const val FAST_HORIZONTAL_GATE_MS = 48L

    /** Minimum interval between vertical repeats. */
    const val VERTICAL_GATE_MS = 112L

    /**
     * Whether a repeat arriving [sinceLastMs] after the previous accepted one should be dropped.
     * Pure so the timing rule is unit-tested without a key event.
     */
    fun shouldDrop(sinceLastMs: Long, gateMs: Long): Boolean = sinceLastMs < gateMs

    fun gateFor(isVertical: Boolean, fastHorizontal: Boolean, horizontalGateMs: Long, verticalGateMs: Long): Long =
        when {
            isVertical -> verticalGateMs
            fastHorizontal -> FAST_HORIZONTAL_GATE_MS
            else -> horizontalGateMs
        }
}

/**
 * Throttles D-pad key repeats on a scrolling container so a held direction steps at a steady,
 * readable pace and doesn't overload HWUI. First presses always pass through untouched.
 *
 * Apply it to every scrolling list or grid. It is the single key-repeat scheme; don't hand-roll
 * a repeat gate in a screen.
 *
 * @param horizontalGateMs  minimum interval between horizontal repeats
 * @param verticalGateMs    minimum interval between vertical repeats
 * @param moveFocusOnRepeat true (the default) consumes each accepted repeat and moves focus itself,
 *                          which keeps long lists smooth. False only drops repeats that arrive too
 *                          soon and lets the accepted ones through, for containers whose own key
 *                          handler gives Left/Right a meaning (switching an addon filter, say).
 */
fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = DpadRepeat.HORIZONTAL_GATE_MS,
    verticalGateMs: Long = DpadRepeat.VERTICAL_GATE_MS,
    moveFocusOnRepeat: Boolean = true,
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val fastHorizontalNavigationEnabled = LocalFastHorizontalNavigationEnabled.current
    val lastRepeatTime = remember { longArrayOf(0L) }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN &&
            native.repeatCount > 0 &&
            (native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP
            val gateMs = DpadRepeat.gateFor(
                isVertical = isVertical,
                fastHorizontal = fastHorizontalNavigationEnabled,
                horizontalGateMs = horizontalGateMs,
                verticalGateMs = verticalGateMs,
            )
            val now = SystemClock.uptimeMillis()
            if (DpadRepeat.shouldDrop(now - lastRepeatTime[0], gateMs)) {
                return@onPreviewKeyEvent true
            }
            lastRepeatTime[0] = now
            if (!moveFocusOnRepeat) return@onPreviewKeyEvent false
            val direction = when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (direction != null) focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
        false
    }
}
