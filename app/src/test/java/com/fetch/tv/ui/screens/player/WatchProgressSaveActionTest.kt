package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.screens.player.WatchProgressSaveAction.MARK_COMPLETED
import com.fetch.tv.ui.screens.player.WatchProgressSaveAction.SAVE_IN_PROGRESS
import com.fetch.tv.ui.screens.player.WatchProgressSaveAction.SKIP
import com.fetch.tv.ui.screens.player.WatchProgressSaveReason.LEAVING
import com.fetch.tv.ui.screens.player.WatchProgressSaveReason.NATURAL_END
import com.fetch.tv.ui.screens.player.WatchProgressSaveReason.PLAYBACK
import org.junit.Assert.assertEquals
import org.junit.Test

/** TheShield2594/fetch#690: seeking into the credits must not mark the title watched. */
class WatchProgressSaveActionTest {

    private fun action(
        reachedEnd: Boolean,
        reason: WatchProgressSaveReason,
        marked: Boolean = false,
        final: Boolean = false
    ) = watchProgressSaveAction(reachedEnd, reason, marked, final)

    @Test
    fun `a seek or pause save past the threshold does not mark watched`() {
        assertEquals(SKIP, action(reachedEnd = true, reason = PLAYBACK))
    }

    @Test
    fun `overshoot then seek back then exit keeps a resume point`() {
        // Seek debounce at 1:58 of 2:00.
        assertEquals(SKIP, action(reachedEnd = true, reason = PLAYBACK))
        // Seek back, periodic saves while watching to 1:00.
        assertEquals(SAVE_IN_PROGRESS, action(reachedEnd = false, reason = PLAYBACK))
        // Exit flush at 1:00.
        assertEquals(SAVE_IN_PROGRESS, action(reachedEnd = false, reason = LEAVING))
    }

    @Test
    fun `natural end and leaving past the threshold mark watched`() {
        assertEquals(MARK_COMPLETED, action(reachedEnd = true, reason = NATURAL_END))
        assertEquals(MARK_COMPLETED, action(reachedEnd = true, reason = LEAVING))
    }

    @Test
    fun `stale low values after the natural end do not overwrite the completed entry`() {
        assertEquals(SKIP, action(reachedEnd = false, reason = PLAYBACK, marked = true, final = true))
        assertEquals(SKIP, action(reachedEnd = false, reason = LEAVING, marked = true, final = true))
    }

    @Test
    fun `a mark made when backgrounded is withdrawn once playback continues below the threshold`() {
        assertEquals(SAVE_IN_PROGRESS, action(reachedEnd = false, reason = PLAYBACK, marked = true, final = false))
        assertEquals(SAVE_IN_PROGRESS, action(reachedEnd = false, reason = LEAVING, marked = true, final = false))
    }
}
