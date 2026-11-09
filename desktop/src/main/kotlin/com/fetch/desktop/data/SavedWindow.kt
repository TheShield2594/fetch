package com.fetch.desktop.data

import kotlinx.serialization.Serializable
import java.io.File

/**
 * The main window's last size and place, in window units (dp), saved in `window.json` so Fetch
 * opens where it was closed. Size and position are the floating window's, kept while maximised
 * so un-maximising goes back to them. Fullscreen (the player's) is never saved.
 */
@Serializable
data class SavedWindow(
    val width: Float = DEFAULT_WIDTH,
    val height: Float = DEFAULT_HEIGHT,
    /** Null until the window has been placed, so the system picks. */
    val x: Float? = null,
    val y: Float? = null,
    val maximized: Boolean = false
) {
    /**
     * The size to open at: the saved one, but no larger than the [screenWidth]×[screenHeight]
     * that's free on the screen, and no smaller than the minimum where the screen allows.
     */
    fun sizeFor(screenWidth: Float?, screenHeight: Float?): Pair<Float, Float> {
        fun fit(value: Float, min: Float, screen: Float?): Float {
            val atLeast = value.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(min) ?: min
            return if (screen != null && screen > 0f) atLeast.coerceAtMost(screen) else atLeast
        }
        return fit(width, MIN_WIDTH, screenWidth) to fit(height, MIN_HEIGHT, screenHeight)
    }

    /**
     * The saved position, when the window would still show at least [VISIBLE] of itself on one of
     * [screens] (x, y, width, height); null (the system's choice) when a monitor has gone.
     */
    fun positionOn(screens: List<FloatArray>, size: Pair<Float, Float>): Pair<Float, Float>? {
        val px = x ?: return null
        val py = y ?: return null
        val (w, h) = size
        val visible = screens.any { (sx, sy, sw, sh) ->
            val overlapW = minOf(px + w, sx + sw) - maxOf(px, sx)
            val overlapH = minOf(py + h, sy + sh) - maxOf(py, sy)
            overlapW >= VISIBLE && overlapH >= VISIBLE
        }
        return if (visible) px to py else null
    }

    companion object {
        const val DEFAULT_WIDTH = 1280f
        const val DEFAULT_HEIGHT = 800f
        const val MIN_WIDTH = 960f
        const val MIN_HEIGHT = 600f
        /** How much of the window must be on a screen for its saved place to be used. */
        const val VISIBLE = 100f
    }
}

fun savedWindowFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "window.json"), SavedWindow.serializer()) { SavedWindow() }
