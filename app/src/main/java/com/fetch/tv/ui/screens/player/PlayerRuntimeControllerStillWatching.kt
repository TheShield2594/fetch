package com.fetch.tv.ui.screens.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STILL_WATCHING_COUNTDOWN_SECONDS = 60
private const val STILL_WATCHING_COUNTDOWN_TICK_MS = 1_000L

/** Whole seconds left on the still-watching countdown, rounded up, never below zero. */
internal fun stillWatchingSecondsLeft(remainingMs: Long): Int =
    if (remainingMs <= 0L) {
        0
    } else {
        ((remainingMs + STILL_WATCHING_COUNTDOWN_TICK_MS - 1L) / STILL_WATCHING_COUNTDOWN_TICK_MS).toInt()
    }

internal fun shouldEnterStillWatchingPrompt(
    stillWatchingEnabled: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    nextEpisodeHasAired: Boolean,
    consecutiveAutoPlayCount: Int,
    threshold: Int,
): Boolean = stillWatchingEnabled &&
    autoPlayNextEpisodeEnabled &&
    nextEpisodeHasAired &&
    consecutiveAutoPlayCount >= threshold

/**
 * The still-watching prompt as the active profile has it: a kids profile's autoplay cap
 * turns it on whatever the setting, at the cap or the setting's threshold, whichever is lower.
 */
internal fun effectiveStillWatching(
    stillWatchingEnabled: Boolean,
    threshold: Int,
    kidsAutoplayCap: Int?
): Pair<Boolean, Int> = when {
    kidsAutoplayCap == null -> stillWatchingEnabled to threshold
    stillWatchingEnabled -> true to minOf(threshold, kidsAutoplayCap)
    else -> true to kidsAutoplayCap
}

internal fun PlayerRuntimeController.shouldPromptStillWatching(nextEpisodeHasAired: Boolean): Boolean {
    val (enabled, threshold) = effectiveStillWatching(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        threshold = stillWatchingEpisodeThresholdSetting,
        kidsAutoplayCap = parentalControls.autoplayEpisodeCap()
    )
    return shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = enabled,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = nextEpisodeHasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = threshold,
    )
}

internal fun PlayerRuntimeController.enterStillWatchingPromptMode() {
    val nextInfo = _uiState.value.nextEpisode ?: return
    if (_uiState.value.postPlayMode is PostPlayMode.StillWatching && stillWatchingPromptJob?.isActive == true) {
        return
    }
    pauseForStillWatchingPrompt()
    stillWatchingPromptJob?.cancel()
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.StillWatching(
                nextEpisode = nextInfo,
                countdownSec = STILL_WATCHING_COUNTDOWN_SECONDS,
            ),
            showControls = false,
        )
    }
    val job = scope.launch {
        try {
            // Count down against a monotonic deadline (the sleep timer's clock), not by summing
            // delay() ticks: delays run late under load or while the device dozes, which would
            // stretch a 60 s prompt well past a minute.
            val deadlineMs = sleepTimer.now() + STILL_WATCHING_COUNTDOWN_SECONDS * STILL_WATCHING_COUNTDOWN_TICK_MS
            var shownSeconds = STILL_WATCHING_COUNTDOWN_SECONDS
            while (true) {
                val remainingMs = deadlineMs - sleepTimer.now()
                if (remainingMs <= 0L) break
                // Wake on the next whole-second boundary of the remaining time.
                delay((remainingMs - 1L) % STILL_WATCHING_COUNTDOWN_TICK_MS + 1L)
                val secondsLeft = stillWatchingSecondsLeft(deadlineMs - sleepTimer.now())
                if (secondsLeft != shownSeconds) {
                    shownSeconds = secondsLeft
                    _uiState.update { state ->
                        val current = state.postPlayMode as? PostPlayMode.StillWatching
                            ?: return@update state
                        state.copy(postPlayMode = current.copy(countdownSec = secondsLeft))
                    }
                }
            }
            if (_uiState.value.postPlayMode is PostPlayMode.StillWatching) {
                exitFromStillWatching()
            }
        } finally {
            if (stillWatchingPromptJob === coroutineContext.job) {
                stillWatchingPromptJob = null
            }
        }
    }
    stillWatchingPromptJob = job
}

internal fun PlayerRuntimeController.onStillWatchingContinue() {
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    consecutiveAutoPlayCount = 0
    _uiState.update { it.copy(postPlayMode = null) }
    playNextEpisode(userInitiated = true)
}

internal fun PlayerRuntimeController.onDismissStillWatchingPrompt() {
    exitFromStillWatching()
}

internal fun PlayerRuntimeController.exitFromStillWatching() {
    consecutiveAutoPlayCount = 0
    resetPostPlayOverlayState(clearEpisode = true)
    _uiState.update { it.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt) }
}
