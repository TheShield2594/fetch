package com.fetch.tv.ui.screens.player

import androidx.media3.common.Player
import com.fetch.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobblePolicyTest {
    @Test
    fun `ready non-playing state emits pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            trackingActionForNonPlayingState(Player.STATE_READY)
        )
    }

    @Test
    fun `ended and idle states emit stop`() {
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_ENDED)
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_IDLE)
        )
    }

    @Test
    fun `buffering state emits no terminal scrobble`() {
        assertNull(trackingActionForNonPlayingState(Player.STATE_BUFFERING))
    }

    @Test
    fun `active pause remains eligible above watched threshold`() {
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 90f))
    }

    @Test
    fun `pause requires active scrobble and accepts early progress`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 45f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
    }

    @Test
    fun `stop closes active sessions below one percent`() {
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 0.5f))
    }

    @Test
    fun `completion stop remains eligible without an active session`() {
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 79.99f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 80f))
    }

    @Test
    fun `leaving past 80 percent but before the TV counts it watched keeps a resume point instead of a play`() {
        // #620: Trakt, AniList and MAL count a stop at 80% as a play; this TV needs 90% (or ~3 min left).
        assertEquals(
            ExitScrobble.PauseInProgress(85f),
            exitScrobbleFor(hasActiveScrobble = true, progressPercent = 85f, finishedLocally = false)
        )
    }

    @Test
    fun `leaving once the TV counts it watched sends the play`() {
        assertEquals(
            ExitScrobble.StopWatched(95f),
            exitScrobbleFor(hasActiveScrobble = true, progressPercent = 95f, finishedLocally = true)
        )
        // Finished by the credits marker under 80%: lifted so the trackers count it too.
        assertEquals(
            ExitScrobble.StopWatched(TRACKER_WATCHED_PERCENT),
            exitScrobbleFor(hasActiveScrobble = true, progressPercent = 74f, finishedLocally = true)
        )
        // A session resumed past 80% sends no start, but finishing it still counts.
        assertEquals(
            ExitScrobble.StopWatched(96f),
            exitScrobbleFor(hasActiveScrobble = false, progressPercent = 96f, finishedLocally = true)
        )
    }

    @Test
    fun `leaving early is an in-progress stop, and nothing without a session`() {
        assertEquals(
            ExitScrobble.StopInProgress(40f),
            exitScrobbleFor(hasActiveScrobble = true, progressPercent = 40f, finishedLocally = false)
        )
        assertEquals(ExitScrobble.None, exitScrobbleFor(hasActiveScrobble = false, progressPercent = 40f, finishedLocally = false))
        assertEquals(ExitScrobble.None, exitScrobbleFor(hasActiveScrobble = false, progressPercent = 85f, finishedLocally = false))
    }
}
