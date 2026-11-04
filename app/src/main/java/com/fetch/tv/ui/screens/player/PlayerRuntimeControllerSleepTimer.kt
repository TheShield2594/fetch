package com.fetch.tv.ui.screens.player

import com.fetch.tv.core.player.SleepTimer
import com.fetch.tv.core.player.SleepTimerChoice
import com.fetch.tv.core.player.SleepTimerSetting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SLEEP_TIMER_TICK_MS = 1_000L

/** How long "Still watching?" waits for an answer before the player closes. */
internal const val SLEEP_TIMER_PROMPT_SECONDS = 60

/** Minutes "15 more minutes" adds from the prompt. */
internal const val SLEEP_TIMER_EXTEND_MINUTES = 15

internal fun sleepTimerLabel(setting: SleepTimerSetting?, remainingMs: Long?): SleepTimerLabel? = when (setting) {
    null -> null
    SleepTimerSetting.EndOfTitle -> SleepTimerLabel.EndOfTitle
    is SleepTimerSetting.Minutes -> SleepTimerLabel.MinutesLeft(SleepTimer.minutesLeft(remainingMs ?: 0L))
}

/** The prompt's next countdown value, or null when it has run out and the player should close. */
internal fun nextSleepTimerPromptSec(current: Int): Int? = (current - 1).takeIf { it > 0 }

/**
 * Watches the app's [SleepTimer] while this player is open: keeps the button's label current,
 * pauses and asks "Still watching?" when the time is up, and closes the player when nobody
 * answers. A timer that ran out before this player opened is dropped instead of acting at once.
 */
internal fun PlayerRuntimeController.startSleepTimerTracking() {
    sleepTimer.clearIfExpired()
    scope.launch {
        while (isActive) {
            val state = _uiState.value
            val promptSec = state.sleepTimerPromptSec
            when {
                promptSec != null -> {
                    val next = nextSleepTimerPromptSec(promptSec)
                    if (next == null) {
                        sleepTimer.cancel()
                        _uiState.update {
                            it.copy(sleepTimerPromptSec = null, sleepTimer = null, pendingExitReason = PlayerExitReason.SleepTimer)
                        }
                    } else {
                        _uiState.update { it.copy(sleepTimerPromptSec = next) }
                    }
                }
                sleepTimer.hasExpired() && state.screenTimeBlock == null && state.pendingExitReason == null -> {
                    pauseForStillWatchingPrompt()
                    _uiState.update {
                        it.copy(
                            sleepTimerPromptSec = SLEEP_TIMER_PROMPT_SECONDS,
                            sleepTimer = null,
                            showControls = false,
                            showMoreDialog = false
                        )
                    }
                }
                else -> refreshSleepTimerLabel()
            }
            delay(SLEEP_TIMER_TICK_MS)
        }
    }
}

private fun PlayerRuntimeController.refreshSleepTimerLabel() {
    val label = sleepTimerLabel(sleepTimer.setting.value, sleepTimer.remainingMs())
    if (_uiState.value.sleepTimer != label) _uiState.update { it.copy(sleepTimer = label) }
}

/** One press on the button: the next duration, then "end of this title", then off. */
internal fun PlayerRuntimeController.onCycleSleepTimer() {
    val setting = sleepTimer.cycle()
    if (setting == SleepTimerSetting.EndOfTitle) {
        // Nothing follows this title now, so take down an Up next card that is already counting.
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
        _uiState.update {
            if (it.postPlayMode is PostPlayMode.AutoPlay) {
                it.copy(postPlayMode = null, postPlayDismissedForCurrentEpisode = true)
            } else {
                it
            }
        }
    }
    refreshSleepTimerLabel()
    scheduleHideControls()
}

/** "Keep watching": the timer is off and playback carries on. */
internal fun PlayerRuntimeController.onSleepTimerKeepWatching() {
    sleepTimer.cancel()
    _uiState.update { it.copy(sleepTimerPromptSec = null, sleepTimer = null) }
    setPlaybackPaused(false)
}

/** "15 more minutes": a fresh timer, and playback carries on. */
internal fun PlayerRuntimeController.onSleepTimerExtend() {
    sleepTimer.set(SleepTimerChoice.Minutes(SLEEP_TIMER_EXTEND_MINUTES))
    _uiState.update { it.copy(sleepTimerPromptSec = null) }
    refreshSleepTimerLabel()
    setPlaybackPaused(false)
}

/** "Stop": leave the player now. */
internal fun PlayerRuntimeController.onSleepTimerStop() {
    sleepTimer.cancel()
    _uiState.update { it.copy(sleepTimerPromptSec = null, sleepTimer = null, pendingExitReason = PlayerExitReason.SleepTimer) }
}

/** At a natural end with "end of this title" set: close the player instead of playing on. */
internal fun PlayerRuntimeController.stopForSleepTimerAtEnd(): Boolean {
    if (!sleepTimer.stopsAtEndOfTitle) return false
    sleepTimer.cancel()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    _uiState.update {
        it.copy(postPlayMode = null, sleepTimer = null, pendingExitReason = PlayerExitReason.SleepTimer)
    }
    return true
}
