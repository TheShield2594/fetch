package com.fetch.desktop.player

import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioExtrasTest {

    @Test
    fun `dialogue boost is swresample's centre level around -3 dB`() {
        assertNull(AudioExtras.centerMixLevel(0))
        assertEquals(1.0, AudioExtras.centerMixLevel(3)!!, 1e-9)
        assertEquals(Math.pow(10.0, 27.0 / 20.0), AudioExtras.centerMixLevel(99)!!, 1e-9)
        assertEquals("clev=1.4125", AudioExtras.swresampleOptions(6))
        assertEquals("", AudioExtras.swresampleOptions(0))
        val args = MpvArgs.build(PlayerSettings(extras = PlayerExtras(centerMixLevelDb = 6)))
        assertTrue(args.contains("--audio-swresample-o=clev=1.4125"))
        assertFalse(MpvArgs.build(PlayerSettings()).any { it.startsWith("--audio-swresample-o") })
    }

    @Test
    fun `dialogue boost is for surround tracks`() {
        assertTrue(AudioExtras.centerMixApplies("5.1(side)"))
        assertTrue(AudioExtras.centerMixApplies("6ch"))
        assertFalse(AudioExtras.centerMixApplies("stereo"))
        assertFalse(AudioExtras.centerMixApplies("2ch"))
        assertFalse(AudioExtras.centerMixApplies(null))
    }

    @Test
    fun `the level meter's reading comes out of af-metadata`() {
        assertEquals(-23.5, AudioExtras.rmsLevel("""{"lavfi.astats.Overall.RMS_level":"-23.500000"}""")!!, 1e-9)
        assertEquals(Double.NEGATIVE_INFINITY, AudioExtras.rmsLevel("lavfi.astats.Overall.RMS_level=-inf")!!, 0.0)
        assertNull(AudioExtras.rmsLevel("{}"))
        assertNull(AudioExtras.rmsLevel(null))
    }

    @Test
    fun `silence is hurried through once it lasts`() {
        val skipper = SilenceSkipper(thresholdDb = -50.0, minSilenceSec = 0.6, fastSpeed = 3.0)
        assertNull(skipper.sample(-20.0, 0.0))
        assertNull(skipper.sample(-70.0, 1.0))
        assertNull("not long enough yet", skipper.sample(-70.0, 1.4))
        assertEquals(3.0, skipper.sample(-70.0, 1.7)!!, 0.0)
        assertNull("already hurrying", skipper.sample(-80.0, 2.0))
        assertNull("no reading changes nothing", skipper.sample(null, 2.1))
        assertEquals("sound again: normal speed", 1.0, skipper.sample(-30.0, 2.2)!!, 0.0)
        assertFalse(skipper.hurrying)
        skipper.sample(-70.0, 3.0)
        skipper.sample(-70.0, 4.0)
        assertEquals(1.0, skipper.reset()!!, 0.0)
        assertNull(skipper.reset())
    }

    @Test
    fun `the output is mpv's device, or the audio system and its default sink`() {
        assertEquals("alsa/hdmi:CARD=PCH", AudioExtras.routeKey("alsa/hdmi:CARD=PCH", "alsa", null))
        assertEquals("pipewire/alsa_output.usb-headset", AudioExtras.routeKey("auto", "pipewire", "alsa_output.usb-headset"))
        assertEquals("pulse/default", AudioExtras.routeKey("auto", "pulse", null))
        assertNull(AudioExtras.routeKey(null, null, null))
    }

    @Test
    fun `delays are kept per output`() {
        val file = File.createTempFile("audio-delays", ".json").apply { delete(); deleteOnExit() }
        val store = AudioDelayStore(file)
        assertNull(store.delayMs("pulse/a"))
        store.save("pulse/a", 250)
        store.save("pulse/b", -999_999)
        val reloaded = AudioDelayStore(file)
        assertEquals(250, reloaded.delayMs("pulse/a"))
        assertEquals("clamped to the TV's range", -60_000, reloaded.delayMs("pulse/b"))
    }

    @Test
    fun `the stats overlay reads proc and mpv`() {
        assertEquals((1500L + 300L) to 42L, DebugStatsSampler.parseProcStat("1234 (java main) S 1 2 3 4 5 6 7 8 42 9 1500 300 0 0"))
        assertEquals(204800L, DebugStatsSampler.parseRssKb("Name:\tjava\nVmRSS:\t  204800 kB\n"))
        var t = 1_000L
        var stat = "1 (x) S 0 0 0 0 0 0 0 0 0 0 100 0"
        val sampler = DebugStatsSampler(procStat = { stat }, procStatus = { "VmRSS: 1024 kB" }, thermal = { listOf(55_000, 90_000) }, clockTicks = 100, cores = 2, now = { t })
        val first = sampler.sample(MpvSnapshot(aheadSec = 12.0, fileSizeBytes = 1_000_000_000, durationSec = 1000.0, readBytesPerSec = 1_250_000.0, droppedFrames = 0))
        assertEquals("...", first.first { it.label == "cpu" }.value)
        t = 2_000L
        stat = "1 (x) S 0 0 0 0 0 0 0 0 0 0 200 0"
        val second = sampler.sample(MpvSnapshot(aheadSec = 2.0, videoBitrate = 4_000_000.0, droppedFrames = 3))
        val byLabel = second.associateBy { it.label }
        assertEquals("50%   avg 50%", byLabel["cpu"]!!.value)
        assertEquals(DebugStatSeverity.WARNING, byLabel["buffer"]!!.severity)
        assertEquals("4.0 Mbps tracks", byLabel["bitrate"]!!.value)
        assertEquals("3 frames", byLabel["dropped"]!!.value)
        assertEquals("90 °C", byLabel["thermal"]!!.value)
        assertEquals("8.0 Mbps file   0.9 GB", first.first { it.label == "bitrate" }.value)
        assertEquals("10.0 Mbps   avg 10.0", first.first { it.label == "network" }.value)
        assertFalse("no reading, no row", second.any { it.label == "network" })
    }
}
