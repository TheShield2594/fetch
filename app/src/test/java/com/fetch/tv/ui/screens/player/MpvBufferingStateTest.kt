package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TheShield2594/fetch#732: core-idle alone is not buffering. */
class MpvBufferingStateTest {

    @Test
    fun `a plain pause is not buffering`() {
        assertFalse(isMpvBuffering(pausedForCache = false, coreIdle = true, paused = true, eofReached = false))
    }

    @Test
    fun `the keep-open pause at the end of the file is not buffering`() {
        assertFalse(isMpvBuffering(pausedForCache = false, coreIdle = true, paused = true, eofReached = true))
        assertFalse(isMpvBuffering(pausedForCache = false, coreIdle = true, paused = false, eofReached = true))
    }

    @Test
    fun `waiting on the cache is buffering, paused or not`() {
        assertTrue(isMpvBuffering(pausedForCache = true, coreIdle = true, paused = false, eofReached = false))
        assertTrue(isMpvBuffering(pausedForCache = true, coreIdle = true, paused = true, eofReached = false))
    }

    @Test
    fun `an idle core while playing is buffering`() {
        assertTrue(isMpvBuffering(pausedForCache = false, coreIdle = true, paused = false, eofReached = false))
    }

    @Test
    fun `normal playback is not buffering`() {
        assertFalse(isMpvBuffering(pausedForCache = false, coreIdle = false, paused = false, eofReached = false))
    }
}
