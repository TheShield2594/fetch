package com.fetch.tv.core.player

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A sleep timer the viewer set from the player's More row. */
sealed interface SleepTimerSetting {
    /** Stops playback once [endsAtMs] (on [SleepTimer]'s clock) has passed. */
    data class Minutes(val minutes: Int, val endsAtMs: Long) : SleepTimerSetting

    /** Lets the current title play to its end, then leaves the player instead of playing the next. */
    data object EndOfTitle : SleepTimerSetting
}

/** One press on the sleep timer button moves to the next of these, then back to off. */
sealed interface SleepTimerChoice {
    data class Minutes(val minutes: Int) : SleepTimerChoice
    data object EndOfTitle : SleepTimerChoice
}

/**
 * The app's sleep timer. It lives outside the player so it keeps running when the next episode
 * opens a new player, and it uses the monotonic clock so a time-zone or clock change can't move
 * it. Only the player acts on it: a timer that ran out while nothing was playing is dropped the
 * next time a player opens ([clearIfExpired]) rather than stopping that new title at once.
 */
@Singleton
class SleepTimer internal constructor(private val clock: () -> Long) {
    @Inject constructor() : this(SystemClock::elapsedRealtime)

    private val _setting = MutableStateFlow<SleepTimerSetting?>(null)
    val setting: StateFlow<SleepTimerSetting?> = _setting.asStateFlow()

    fun now(): Long = clock()

    /** Moves to the choice after the current one: off, 15, 30, 45, 60, 90 minutes, end of title, off. */
    fun cycle(): SleepTimerSetting? {
        val index = when (val current = _setting.value) {
            null -> -1
            is SleepTimerSetting.Minutes -> CHOICES.indexOfFirst { it == SleepTimerChoice.Minutes(current.minutes) }
            SleepTimerSetting.EndOfTitle -> CHOICES.indexOf(SleepTimerChoice.EndOfTitle)
        }
        val next = CHOICES.getOrNull(index + 1)
        return set(next)
    }

    fun set(choice: SleepTimerChoice?): SleepTimerSetting? {
        val setting = when (choice) {
            null -> null
            is SleepTimerChoice.Minutes -> SleepTimerSetting.Minutes(choice.minutes, clock() + choice.minutes * MINUTE_MS)
            SleepTimerChoice.EndOfTitle -> SleepTimerSetting.EndOfTitle
        }
        _setting.value = setting
        return setting
    }

    fun cancel() {
        _setting.value = null
    }

    /** Milliseconds left on a minutes timer, never below zero; null for no timer or end of title. */
    fun remainingMs(): Long? = (_setting.value as? SleepTimerSetting.Minutes)?.let { (it.endsAtMs - clock()).coerceAtLeast(0L) }

    fun hasExpired(): Boolean = remainingMs() == 0L

    val stopsAtEndOfTitle: Boolean get() = _setting.value == SleepTimerSetting.EndOfTitle

    fun clearIfExpired() {
        if (hasExpired()) cancel()
    }

    companion object {
        const val MINUTE_MS = 60_000L

        val CHOICES: List<SleepTimerChoice> = listOf(
            SleepTimerChoice.Minutes(15),
            SleepTimerChoice.Minutes(30),
            SleepTimerChoice.Minutes(45),
            SleepTimerChoice.Minutes(60),
            SleepTimerChoice.Minutes(90),
            SleepTimerChoice.EndOfTitle
        )

        /** Minutes left, rounded up, so a timer never reads "0 min" while it is still running. */
        fun minutesLeft(remainingMs: Long): Int = ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }
}
