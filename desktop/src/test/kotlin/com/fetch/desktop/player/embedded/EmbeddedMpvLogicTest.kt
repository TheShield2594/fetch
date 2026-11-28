package com.fetch.desktop.player.embedded

import com.fetch.desktop.player.YtDlp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in player's decisions that need no libmpv. */
class EmbeddedMpvLogicTest {

    @Test
    fun `only the latest load's entry ends its follow`() {
        // Entry 7 replaced before it started: its end doesn't end entry 8's follow.
        assertFalse(EmbeddedMpv.isCurrentEnd(currentEntry = 8, endedEntry = 7, awaitingStart = false))
        assertTrue(EmbeddedMpv.isCurrentEnd(currentEntry = 8, endedEntry = 8, awaitingStart = true))
        // No entry ids (an old libmpv): the start flag decides, as before.
        assertFalse(EmbeddedMpv.isCurrentEnd(currentEntry = 0, endedEntry = 0, awaitingStart = true))
        assertTrue(EmbeddedMpv.isCurrentEnd(currentEntry = 0, endedEntry = 0, awaitingStart = false))
        assertTrue(EmbeddedMpv.isCurrentEnd(currentEntry = 8, endedEntry = null, awaitingStart = false))
    }

    @Test
    fun `frames fit the view, and a cap keeps their shape`() {
        // Software: the video's size, never above it.
        assertEquals(1280 to 720, EmbeddedMpv.frameSize(1920 to 1080, 1280, 720, wholeView = false, cap = null))
        assertEquals(960 to 540, EmbeddedMpv.frameSize(960 to 1080, 1920, 1080, wholeView = false, cap = null))
        assertNull(EmbeddedMpv.frameSize(1920 to 1080, 0, 0, wholeView = false, cap = null))
        // GPU: the whole view, here a 4K one under a background video's 1080p cap.
        assertEquals(3840 to 2160, EmbeddedMpv.frameSize(3840 to 2160, 0, 0, wholeView = true, cap = null))
        assertEquals(1920 to 1080, EmbeddedMpv.frameSize(3840 to 2160, 0, 0, wholeView = true, cap = 1920 to 1080))
        assertEquals(1920 to 400, EmbeddedMpv.frameSize(3840 to 800, 0, 0, wholeView = true, cap = 1920 to 1080))
        // Smaller than the cap: unchanged, and always even.
        assertEquals(1000 to 500, EmbeddedMpv.frameSize(1001 to 501, 0, 0, wholeView = true, cap = 1920 to 1080))
    }

    @Test
    fun `an audio-only file has no picture to wait for`() {
        val audio = """[{"id":1,"type":"audio","codec":"aac"},{"id":1,"type":"sub"}]"""
        val video = """[{"id":1,"type":"video","codec":"h264"},{"id":1,"type":"audio"}]"""
        assertFalse(EmbeddedMpv.hasVideoTrack(audio))
        assertTrue(EmbeddedMpv.hasVideoTrack(video))
        assertFalse(EmbeddedMpv.hasVideoTrack(null))
        assertFalse(EmbeddedMpv.hasVideoTrack("not json"))
    }

    @Test
    fun `a YouTube page without yt-dlp says what to install`() {
        val yt = "https://www.youtube.com/watch?v=abc"
        assertEquals(YtDlp.MISSING_MESSAGE, EmbeddedMpv.failureMessage(yt, "loading failed") { false })
        assertEquals("Couldn't play this source (loading failed)", EmbeddedMpv.failureMessage(yt, "loading failed") { true })
        assertEquals("Couldn't play this source (loading failed)", EmbeddedMpv.failureMessage("https://cdn.example/a.mkv", "loading failed") { false })
    }

    @Test
    fun `LC_NUMERIC is glibc's 1 only on Linux`() {
        assertEquals(1, LibC.lcNumeric(linux = true))
        assertEquals(4, LibC.lcNumeric(linux = false))
    }
}
