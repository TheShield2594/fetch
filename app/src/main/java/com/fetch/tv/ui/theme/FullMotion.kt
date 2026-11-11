package com.fetch.tv.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether decorative motion should play. Reduced motion is a preference, so only the member decides
 * it: Fetch's own "Reduce motion" setting, or the system's animations being turned off (the
 * closest Android TV has to a reduced-motion setting).
 *
 * A low-RAM device is a performance tier, not a preference, and no longer turns motion off here:
 * read [isLowRamDevice] (or [FetchTheme.lowRamDevice]) where an effect is too costly for one.
 */
fun isFullMotionAllowed(context: Context, reduceMotionSetting: Boolean = false): Boolean =
    fullMotionAllowed(reduceMotionSetting = reduceMotionSetting, animatorScale = systemAnimatorScale(context))

/** The decision behind [isFullMotionAllowed], separated from Android so it can be tested. */
internal fun fullMotionAllowed(reduceMotionSetting: Boolean, animatorScale: Float): Boolean =
    !reduceMotionSetting && animatorScale != 0f

private fun systemAnimatorScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/** True on devices Android marks as low on memory; heavy effects (blur, video previews) can skip. */
fun isLowRamDevice(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

/** [isFullMotionAllowed], updated when the animator scale changes while the app is open. */
@Composable
fun rememberFullMotionAllowed(reduceMotionSetting: Boolean = false): Boolean {
    val context = LocalContext.current
    var animatorScale by remember(context) { mutableFloatStateOf(systemAnimatorScale(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorScale = systemAnimatorScale(context)
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
    return fullMotionAllowed(reduceMotionSetting, animatorScale)
}

/**
 * True while a screen reader such as TalkBack is running. Things that time out (auto-hiding player
 * controls) wait longer then, because hearing a control read out takes longer than seeing it.
 */
fun isScreenReaderOn(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.isEnabled && manager.isTouchExplorationEnabled
}

/** [isScreenReaderOn], updated when the screen reader is turned on or off while the app is open. */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    var on by remember(context) { mutableStateOf(isScreenReaderOn(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = isScreenReaderOn(context) }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * How long the player's controls stay up: the member's "Hide controls after" setting, and with a
 * screen reader running at least [SCREEN_READER_AUTO_HIDE_FACTOR] times that and never under
 * [SCREEN_READER_MIN_AUTO_HIDE_MS], so focus isn't pulled away while a control is still being read.
 */
fun controlsAutoHideDelayMs(settingMs: Long, screenReaderOn: Boolean): Long =
    if (screenReaderOn) maxOf(settingMs * SCREEN_READER_AUTO_HIDE_FACTOR, SCREEN_READER_MIN_AUTO_HIDE_MS) else settingMs

const val SCREEN_READER_AUTO_HIDE_FACTOR = 3L
const val SCREEN_READER_MIN_AUTO_HIDE_MS = 15_000L

/**
 * The system "Audio description" preference (Android 13+). The player then prefers, among tracks
 * in the chosen language, the one that narrates what's on screen.
 */
fun isAudioDescriptionRequested(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.isAudioDescriptionRequested
}
