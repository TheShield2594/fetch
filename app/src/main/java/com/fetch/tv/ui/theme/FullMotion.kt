package com.fetch.tv.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** [isFullMotionAllowed], updated when the animator scale changes while the app is open. */
@Composable
fun rememberFullMotionAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember(context) { mutableStateOf(isFullMotionAllowed(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                allowed = isFullMotionAllowed(context)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }.isSuccess
        onDispose {
            if (registered) resolver.unregisterContentObserver(observer)
        }
    }
    return allowed
}
