@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.fetch.tv.R

internal object PlayerDisplayModeUtils {

    /**
     * Tunneled video ignores view scale, so the surface size is the only aspect control.
     * Fill is opt-in for that session; every other playback path stays FIT so the aspect
     * scale modes keep a video-sized frame.
     */
    fun exoSurfaceResizeMode(tunnelingEnabled: Boolean, tunneledSurfaceFill: Boolean): Int {
        return if (tunnelingEnabled && tunneledSurfaceFill) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun resizeModeLabel(mode: Int, context: Context): String {
        return when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> context.getString(R.string.player_aspect_fit)
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> context.getString(R.string.player_aspect_stretch)
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> context.getString(R.string.player_aspect_fit_width)
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> context.getString(R.string.player_aspect_fit_height)
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> context.getString(R.string.player_aspect_crop)
            else -> context.getString(R.string.player_aspect_fit)
        }
    }
}
