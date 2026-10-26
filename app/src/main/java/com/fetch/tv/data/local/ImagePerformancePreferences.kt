package com.fetch.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePerformancePreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val rgb565Enabled: Boolean
        get() = preferences.getBoolean(KEY_RGB565_ENABLED, true)

    fun setRgb565Enabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_RGB565_ENABLED, enabled)
            .commit()
    }

    /**
     * Decode images straight into GPU memory (Android 8+). Off by default until it has been tried
     * on low-end Fire TV and Google TV devices. Requests that read pixels (blur, colour sampling)
     * still get software bitmaps: Coil falls back for transformations, and the rest opt out.
     */
    val hardwareBitmapsEnabled: Boolean
        get() = preferences.getBoolean(KEY_HARDWARE_BITMAPS_ENABLED, false)

    fun setHardwareBitmapsEnabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_HARDWARE_BITMAPS_ENABLED, enabled)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "image_performance"
        const val KEY_RGB565_ENABLED = "rgb565_enabled"
        const val KEY_HARDWARE_BITMAPS_ENABLED = "hardware_bitmaps_enabled"
    }
}
