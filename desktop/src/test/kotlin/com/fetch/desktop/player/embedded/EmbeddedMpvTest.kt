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

    /**
     * GPU tests accept a CPU-only OpenGL driver (Mesa's llvmpipe on CI and here), which the app
     * itself refuses, and use the bilinear scaler: with mpv's default lanczos, llvmpipe sometimes
     * draws every frame black, which isn't what these tests are about.
     */
    private fun gpuPlayer() = EmbeddedMpv.create(preferGpu = true, allowSoftwareGl = true)
    private val gpuOptions = listOf("--ao=null", "--hwdec=no", "--scale=bilinear", "--cscale=bilinear")

    /** Waits until the UI thread has run everything posted so far. */
    private fun flushUi() = SwingUtilities.invokeAndWait {}

    @Test
    fun `plays a file into frames, seeks, and reports the end`() = runBlocking {
        assumeTrue("libmpv not installed", LibMpv.available)
        val file = clip(6)
        assumeTrue("ffmpeg not installed", file != null)
        EmbeddedMpv.create(preferGpu = false).use { player ->
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

    /** A clip that's red on top and blue below, to check colours and that nothing is upside down. */
    private fun splitClip(): File? {
        val file = Files.createTempDirectory("mpv-embedded").resolve("split.mp4").toFile()
        val ok = runCatching {
            ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "color=c=blue:s=320x180:d=4,drawbox=x=0:y=0:w=320:h=90:color=red:t=fill",
                "-pix_fmt", "yuv420p", file.absolutePath
            ).start().waitFor() == 0
        }.getOrDefault(false)
        return file.takeIf { ok && it.length() > 0 }
    }

    private fun checkSplitFrame(preferGpu: Boolean) = runBlocking {
        assumeTrue("libmpv not installed", LibMpv.available)
        val file = splitClip()
        assumeTrue("ffmpeg not installed", file != null)
        (if (preferGpu) gpuPlayer() else EmbeddedMpv.create(preferGpu = false)).use { player ->
            if (preferGpu) assumeTrue("no OpenGL here", player.usingGpu)
            player.setViewSize(640, 360)
            player.load(file!!.absolutePath, "Split", start = null, options = if (preferGpu) gpuOptions else listOf("--ao=null", "--hwdec=no"), headers = emptyMap())
            // mpv may show a black frame before the first decoded one.
            withTimeout(15_000) {
                while (player.frame()?.let { (it.getColor(it.width / 2, it.height / 4) and 0xFFFFFF) > 0x202020 } != true) {
                    kotlinx.coroutines.delay(100)
                    flushUi()
                }
            }
            val frame = player.frame()!!
            if (preferGpu) assertEquals("the GPU scales up to the window", 640, frame.width)
            val top = frame.getColor(frame.width / 2, frame.height / 4)
            val bottom = frame.getColor(frame.width / 2, frame.height * 3 / 4)
            fun red(c: Int) = (c shr 16) and 0xFF
            fun blue(c: Int) = c and 0xFF
            assertTrue("top is red: ${Integer.toHexString(top)}", red(top) > 200 && blue(top) < 60)
            assertTrue("bottom is blue: ${Integer.toHexString(bottom)}", blue(bottom) > 200 && red(bottom) < 60)
        }
    }

    @Test
    fun `the GPU renderer draws the picture the right way up and in the right colours`() = checkSplitFrame(preferGpu = true)

    @Test
    fun `the software renderer does too`() = checkSplitFrame(preferGpu = false)

    @Test
    fun `a GPU player opened while another closes still draws`() = runBlocking {
        assumeTrue("libmpv not installed", LibMpv.available)
        val file = splitClip()
        assumeTrue("ffmpeg not installed", file != null)
        // Up Next: the next episode's player starts before the last one has finished closing.
        val first = gpuPlayer()
        assumeTrue("no OpenGL here", first.usingGpu.also { if (!it) first.close() })
        first.load(file!!.absolutePath, "First", start = null, options = gpuOptions, headers = emptyMap())
        gpuPlayer().use { second ->
            first.close()
            second.setViewSize(320, 180)
            second.load(file.absolutePath, "Second", start = null, options = gpuOptions, headers = emptyMap())
            withTimeout(15_000) {
                while (second.frame()?.let { (it.getColor(it.width / 2, it.height / 4) shr 16 and 0xFF) > 200 } != true) {
                    kotlinx.coroutines.delay(100)
                    flushUi()
                }
            }
        }
    }

    @Test
    fun `seeking while paused on the GPU shows the new picture`() = runBlocking {
        assumeTrue("libmpv not installed", LibMpv.available)
        val file = clip(8)
        assumeTrue("ffmpeg not installed", file != null)
        gpuPlayer().use { player ->
            assumeTrue("no OpenGL here", player.usingGpu)
            player.setViewSize(320, 180)
            player.load(file!!.absolutePath, "Clip", start = null, options = gpuOptions, headers = emptyMap())
            withTimeout(15_000) {
                while (player.position < 1.0) {
                    kotlinx.coroutines.delay(100)
                    flushUi()
                }
            }
            player.togglePause()
            withTimeout(5_000) { while (!player.paused) { kotlinx.coroutines.delay(50); flushUi() } }
            kotlinx.coroutines.delay(500)
            flushUi()
            fun signature(): Long { val f = player.frame()!!; var h = 0L; for (x in 0 until f.width step 7) for (y in 0 until f.height step 7) h = h * 31 + f.getColor(x, y); return h }
            val before = signature()
            val version = player.frameVersion
            player.seekTo(6.0)
            withTimeout(10_000) {
                while (player.frameVersion == version || signature() == before) {
                    kotlinx.coroutines.delay(100)
                    flushUi()
                }
            }
            assertTrue("still paused", player.paused)
        }
    }

    @Test
    fun `CPU-only OpenGL drivers are recognised, so the app draws in software there`() {
        assertTrue(GlRenderer.isSoftware("llvmpipe (LLVM 20.1.2, 256 bits)"))
        assertTrue(GlRenderer.isSoftware("softpipe"))
        assertTrue(GlRenderer.isSoftware("Google SwiftShader"))
        assertTrue(!GlRenderer.isSoftware("AMD Radeon RX 6600 (radeonsi, navi23, LLVM 17.0.6, DRM 3.54)"))
        assertTrue(!GlRenderer.isSoftware("NVIDIA GeForce RTX 3060/PCIe/SSE2"))
        assertTrue(!GlRenderer.isSoftware("Mesa Intel(R) UHD Graphics 620 (KBL GT2)"))
    }

    @Test
    fun `the app falls back to software drawing on a CPU-only OpenGL driver`() {
        assumeTrue("libmpv not installed", LibMpv.available)
        EmbeddedMpv.create(preferGpu = true).use { player ->
            val probe = GlRenderer.create(allowSoftware = true)
            val software = probe?.let { GlRenderer.isSoftware(it.rendererName).also { _ -> it.close() } }
            assumeTrue("no software OpenGL here", software == true)
            assertTrue("drawing in software", !player.usingGpu)
        }
    }
}
