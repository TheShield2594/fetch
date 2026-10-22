package com.fetch.tv.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where the last Play or Resume press happened on screen, so the Fetching panel can grow out of
 * that button on the next screen. Screens share one window, so window bounds carry over.
 */
object PlayLaunchOrigin {
    /** A launch older than this is not the one the opening screen came from. */
    const val MAX_AGE_MS = 3_000L

    private var offered: Rect? = null
    private var launched: Rect? = null
    private var launchedAtMs = 0L

    /** A press of the button at [bounds] is starting playback. */
    fun launchFrom(bounds: Rect, nowMs: Long = SystemClock.elapsedRealtime()) {
        launched = bounds
        launchedAtMs = nowMs
    }

    /**
     * Shows where OK will start playback from when the control that starts it is not itself the
     * button (Home's Resume pill names what OK on the focused card does). Null withdraws it.
     */
    fun offer(bounds: Rect?) {
        offered = bounds
    }

    /** Playback is starting from the offered bounds, if any are shown. */
    fun launchFromOffer(nowMs: Long = SystemClock.elapsedRealtime()) {
        offered?.let { launchFrom(it, nowMs) }
    }

    /** The latest launch's bounds, once; null if there was none in the last [MAX_AGE_MS]. */
    fun take(nowMs: Long = SystemClock.elapsedRealtime()): Rect? {
        val bounds = launched ?: return null
        launched = null
        return bounds.takeIf { nowMs - launchedAtMs in 0L..MAX_AGE_MS }
    }
}

/** Offers this element's bounds as the place Resume starts from, while it is shown. */
@Composable
fun Modifier.offersPlayLaunchOrigin(): Modifier {
    DisposableEffect(Unit) {
        onDispose { PlayLaunchOrigin.offer(null) }
    }
    return onGloballyPositioned { PlayLaunchOrigin.offer(it.boundsInWindow()) }
}
