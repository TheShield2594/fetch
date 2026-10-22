package com.fetch.tv.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether decorative motion should play: off when the system's animations are turned off
 * (the closest Android TV has to a reduced-motion setting) and on low-RAM devices.
 */
fun isFullMotionAllowed(context: Context): Boolean {
    val animatorScale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    if (animatorScale == 0f) return false
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return activityManager?.isLowRamDevice != true
}

@Composable
fun rememberFullMotionAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) { isFullMotionAllowed(context) }
}
