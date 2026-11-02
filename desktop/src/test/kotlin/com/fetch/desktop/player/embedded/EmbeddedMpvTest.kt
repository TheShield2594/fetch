package com.fetch.desktop.player.embedded

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities

/**
 * Plays a generated clip through the real libmpv. Skipped where libmpv or ffmpeg isn't
 * installed, so CI without them still passes.
 */
class EmbeddedMpvTest {

    private fun clip(seconds: Int): File? {
        val file = Files.createTempDirectory("mpv-embedded").resolve("clip.mp4").toFile()
        val ok = runCatching {
            ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=$seconds:size=320x180:rate=10",
                "-f", "lavfi", "-i", "sine=duration=$seconds",
                "-shortest", file.absolutePath
            ).start().waitFor() == 0
        }.getOrDefault(false)
        return file.takeIf { ok && it.length() > 0 }
    }

    /** Waits until the UI thread has run everything posted so far. */
    private fun flushUi() = SwingUtilities.invokeAndWait {}

    @Test
    fun `plays a file into frames, seeks, and reports the end`() = runBlocking {
        assumeTrue("libmpv not installed", LibMpv.available)
        val file = clip(6)
        assumeTrue("ffmpeg not installed", file != null)
        EmbeddedMpv.create().use { player ->
            player.setViewSize(640, 360)
            player.load(file!!.absolutePath, "Test", start = "1", options = listOf("--ao=null", "--hwdec=no"), headers = emptyMap())
            val ending = async { player.follow(intervalMs = 0) {} }
            withTimeout(15_000) {
                while (player.frame() == null || player.position < 1.5) {
                    kotlinx.coroutines.delay(100)
                    flushUi()
                }
            }
            val frame = player.frame()
            assertNotNull(frame)
            assertEquals("rendered at the video's size", 320, frame!!.width)
            assertTrue("started from --start", player.position >= 1.0)
            assertTrue(player.duration > 5.0)
            player.seekTo(5.5)
            val end = withTimeout(15_000) { ending.await() }
            assertTrue("a file played to its end reports it, for Up Next", end.reachedEnd)
        }
    }
}
