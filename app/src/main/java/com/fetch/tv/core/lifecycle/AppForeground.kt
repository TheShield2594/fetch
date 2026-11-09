package com.fetch.tv.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the app is on screen. False until the activity starts, so a process woken by a
 * background job or the boot receiver does no foreground-only work (polling, say).
 *
 * MainActivity sets it from onStart and onStop.
 */
object AppForeground {
    private val state = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = state.asStateFlow()

    fun set(foreground: Boolean) {
        state.value = foreground
    }

    /** Returns at once in the foreground; otherwise suspends until the app is back on screen. */
    suspend fun awaitForeground() {
        state.first { it }
    }
}
