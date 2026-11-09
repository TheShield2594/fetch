package com.fetch.tv.ui.screens.player

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SCREEN_TIME_TICK_MS = 5_000L
// While the limit modal is up, check more often so a stray "play" is caught quickly.
private const val SCREEN_TIME_BLOCKED_TICK_MS = 1_000L

/**
 * Counts playback against a kids profile's daily screen time, and stops it at the limit or at
 * bedtime when the parent chose "stop right away". Otherwise the title plays to the end and
 * the next one is held back ([isScreenTimeBlocked]).
 */
internal fun PlayerRuntimeController.startScreenTimeTracking() {
    scope.launch {
        var lastTickMs = SystemClock.elapsedRealtime()
        while (isActive) {
            delay(if (_uiState.value.screenTimeBlock != null) SCREEN_TIME_BLOCKED_TICK_MS else SCREEN_TIME_TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val state = _uiState.value
            if (state.isPlaying) screenTime.recordPlayback(now - lastTickMs)
            lastTickMs = now
            if (state.screenTimeBlock != null) {
                // A MediaSession / remote "play" bypasses the modal; keep playback paused behind it.
                if (hasActivePlayIntent()) pauseForStillWatchingPrompt()
                continue
            }
            if (!screenTime.hardStop()) continue
            val status = screenTime.status()
            if (status.isBlocked) {
                pauseForStillWatchingPrompt()
                _uiState.update { it.copy(screenTimeBlock = status, showControls = false) }
            }
        }
    }
}

/** True when a kids profile can't start anything more now; the next episode isn't autoplayed. */
internal fun PlayerRuntimeController.isScreenTimeBlocked(): Boolean = screenTime.status().isBlocked

/** "That's all for today" was acknowledged: leave the player. */
internal fun PlayerRuntimeController.onScreenTimeExit() {
    _uiState.update { it.copy(screenTimeBlock = null, pendingExitReason = PlayerExitReason.ScreenTimeLimit) }
}

/** A parent added time: carry on where playback stopped. */
internal fun PlayerRuntimeController.onScreenTimeGranted() {
    _uiState.update { it.copy(screenTimeBlock = null) }
    setPlaybackPaused(false)
}
